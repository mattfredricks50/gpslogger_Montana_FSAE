/*
 * This file is part of GPSLogger for Android.
 *
 * GPSLogger for Android is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * GPSLogger for Android is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with GPSLogger for Android.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.mendhak.gpslogger.loggers.fsae;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import com.mendhak.gpslogger.BuildConfig;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.slf4j.Logs;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Writes the FSAE stream files for each chunk, laid out for PlotJuggler:
 * <ul>
 * <li>{@code <base>.gps} GPS fixes</li>
 * <li>{@code <base>.acc} accelerometer at the requested rate</li>
 * <li>{@code <base>.gyr} gyroscope at the requested rate</li>
 * <li>{@code <base>.meta} JSON: session clock, device, sensors, rates, stationary calibration</li>
 * </ul>
 * Column 1 of every stream is {@code time_s}: seconds since the session started, on the
 * elapsedRealtimeNanos clock. The session start stays fixed across chunks, so chunk files join
 * end to end. Every file is dense (no empty cells in sensor rows) and values are raw; axis
 * transforms happen in post-processing.
 */
public class FsaeLogger implements SensorEventListener {

    private static final Logger LOG = Logs.of(FsaeLogger.class);

    static final String GPS_HEADER = "time_s,utc_ms,lat_deg,lon_deg,alt_m,speed_mps,bearing_deg,hacc_m,vacc_m,speed_acc_mps,sats";
    static final String ACC_HEADER = "time_s,accel_x_mps2,accel_y_mps2,accel_z_mps2";
    static final String GYR_HEADER = "time_s,gyro_x_rads,gyro_y_rads,gyro_z_rads";

    // Let the sensor hub batch samples; timestamps stay per-sample, delivery is just bursty.
    private static final int MAX_REPORT_LATENCY_US = 100_000;
    private static final long FLUSH_INTERVAL_NS = 1_000_000_000L;
    private static final long CALIBRATION_NS = 2_000_000_000L;

    private final Context context;
    private final SensorManager sensorManager;
    private final Session session = Session.getInstance();
    private final CsvStreamWriter gpsWriter = new CsvStreamWriter(GPS_HEADER);
    private final CsvStreamWriter accWriter = new CsvStreamWriter(ACC_HEADER);
    private final CsvStreamWriter gyrWriter = new CsvStreamWriter(GYR_HEADER);

    private File folder;
    private String baseName;
    private int requestedRateHz;
    private long sessionStartNs;
    private HandlerThread sensorThread;
    private Handler sensorHandler;
    private String ecuHosts;
    private EcuLogger ecu;

    // Per-chunk rows discarded because their timestamp went backwards; reset on rotate
    private final AtomicLong accDropped = new AtomicLong();
    private final AtomicLong gyrDropped = new AtomicLong();
    private final AtomicLong gpsDropped = new AtomicLong();

    // Sensor thread only
    private final StringBuilder sensorLine = new StringBuilder(64);
    private long lastAccNs = Long.MIN_VALUE;
    private long lastGyrNs = Long.MIN_VALUE;
    private long lastFlushNs;
    private Calibration calibration;

    // Main thread only
    private long lastGpsNs = Long.MIN_VALUE;

    public FsaeLogger(Context context) {
        this.context = context.getApplicationContext();
        this.sensorManager = (SensorManager) this.context.getSystemService(Context.SENSOR_SERVICE);
    }

    /**
     * Log Speeduino data from an AirBear in Web Dash mode to {@code <base>.ecu}. Call before
     * {@link #start}; null or empty disables the ECU stream.
     *
     * @param hostsCsv AirBear addresses to try in order, e.g. "speeduino.local,192.168.4.1"
     */
    public void setEcuHosts(String hostsCsv) {
        this.ecuHosts = hostsCsv;
    }

    public boolean isRunning() {
        return sensorThread != null;
    }

    /**
     * @param newSession true when logging was freshly started (as opposed to the service being
     *                   restarted mid-session), which resets t=0 and re-runs the calibration
     */
    public void start(File folder, String baseName, int imuRateHz, boolean newSession) {
        if (isRunning()) {
            rotate(baseName);
            return;
        }

        this.folder = folder;
        this.requestedRateHz = imuRateHz;

        long nowNs = SystemClock.elapsedRealtimeNanos();
        long storedStartNs = session.getFsaeSessionStartNs();
        // elapsedRealtime resets on reboot, so a stored start in the future means a stale session
        if (newSession || storedStartNs <= 0 || storedStartNs > nowNs) {
            session.setFsaeSessionStart(nowNs, System.currentTimeMillis());
        }
        sessionStartNs = session.getFsaeSessionStartNs();

        if (Strings.isNullOrEmpty(session.getFsaeCalibration())) {
            calibration = new Calibration(Math.max(nowNs, sessionStartNs));
        }

        ecu = Strings.isNullOrEmpty(ecuHosts) ? null : new EcuLogger(context, ecuHosts, sessionStartNs);
        openChunk(baseName);
        if (ecu != null) {
            ecu.start();
        }

        sensorThread = new HandlerThread("FsaeLogger", Process.THREAD_PRIORITY_URGENT_DISPLAY);
        sensorThread.start();
        sensorHandler = new Handler(sensorThread.getLooper());

        int samplingPeriodUs = 1_000_000 / Math.max(1, imuRateHz);
        register(Sensor.TYPE_ACCELEROMETER, samplingPeriodUs);
        register(Sensor.TYPE_GYROSCOPE, samplingPeriodUs);
        LOG.info("FSAE logging started at " + imuRateHz + " Hz, session t0=" + sessionStartNs + ", chunk " + baseName);
    }

    /**
     * Finishes the current chunk (files flushed and closed, metadata final) and continues into
     * new files. When this returns the old chunk is safe to upload.
     */
    public void rotate(String newBaseName) {
        if (newBaseName.equals(baseName)) {
            return;
        }
        closeChunk();
        openChunk(newBaseName);
        LOG.info("FSAE logging rotated to " + newBaseName);
    }

    public void stop() {
        if (!isRunning()) {
            return;
        }
        sensorManager.unregisterListener(this);
        sensorThread.quitSafely();
        try {
            sensorThread.join(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        sensorThread = null;
        sensorHandler = null;
        if (ecu != null) {
            ecu.stop();
        }
        closeChunk();
        ecu = null;
        LOG.info("FSAE logging stopped");
    }

    public void logLocation(Location loc) {
        // Network / cell fixes are too coarse to be useful on track
        if (!LocationManager.GPS_PROVIDER.equals(loc.getProvider())) {
            return;
        }
        long ns = loc.getElapsedRealtimeNanos();
        if (ns <= lastGpsNs) {
            gpsDropped.incrementAndGet();
            return;
        }
        lastGpsNs = ns;

        StringBuilder sb = new StringBuilder(160);
        CsvStreamWriter.appendTime(sb, ns, sessionStartNs);
        sb.append(',').append(loc.getTime());
        sb.append(',');
        CsvStreamWriter.appendFixed(sb, loc.getLatitude(), 8);
        sb.append(',');
        CsvStreamWriter.appendFixed(sb, loc.getLongitude(), 8);
        sb.append(',');
        if (loc.hasAltitude()) CsvStreamWriter.appendFixed(sb, loc.getAltitude(), 2);
        sb.append(',');
        if (loc.hasSpeed()) CsvStreamWriter.appendFixed(sb, loc.getSpeed(), 3);
        sb.append(',');
        if (loc.hasBearing()) CsvStreamWriter.appendFixed(sb, loc.getBearing(), 2);
        sb.append(',');
        if (loc.hasAccuracy()) CsvStreamWriter.appendFixed(sb, loc.getAccuracy(), 2);
        sb.append(',');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasVerticalAccuracy()) {
            CsvStreamWriter.appendFixed(sb, loc.getVerticalAccuracyMeters(), 2);
        }
        sb.append(',');
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && loc.hasSpeedAccuracy()) {
            CsvStreamWriter.appendFixed(sb, loc.getSpeedAccuracyMetersPerSecond(), 3);
        }
        sb.append(',');
        int sats = loc.getExtras() != null ? loc.getExtras().getInt("satellites", -1) : -1;
        if (sats >= 0) sb.append(sats);
        sb.append('\n');

        gpsWriter.writeRow(ns, sb);
        gpsWriter.flush();
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        boolean isAccel = event.sensor.getType() == Sensor.TYPE_ACCELEROMETER;
        long ns = event.timestamp;

        // Each sensor's timestamps are already increasing; anything else is a driver glitch
        if (isAccel) {
            if (ns <= lastAccNs) {
                accDropped.incrementAndGet();
                return;
            }
            lastAccNs = ns;
        } else {
            if (ns <= lastGyrNs) {
                gyrDropped.incrementAndGet();
                return;
            }
            lastGyrNs = ns;
        }

        if (calibration != null) {
            calibration.add(isAccel, ns, event.values);
        }

        StringBuilder sb = sensorLine;
        sb.setLength(0);
        CsvStreamWriter.appendTime(sb, ns, sessionStartNs);
        int decimals = isAccel ? 5 : 6;
        for (int i = 0; i < 3; i++) {
            sb.append(',');
            CsvStreamWriter.appendFixed(sb, event.values[i], decimals);
        }
        sb.append('\n');
        (isAccel ? accWriter : gyrWriter).writeRow(ns, sb);

        if (ns - lastFlushNs > FLUSH_INTERVAL_NS) {
            lastFlushNs = ns;
            accWriter.flush();
            gyrWriter.flush();
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    private void register(int sensorType, int samplingPeriodUs) {
        Sensor sensor = sensorManager.getDefaultSensor(sensorType);
        if (sensor == null) {
            LOG.warn("Sensor type " + sensorType + " not available on this device");
            return;
        }
        sensorManager.registerListener(this, sensor, samplingPeriodUs, MAX_REPORT_LATENCY_US, sensorHandler);
    }

    private void openChunk(String newBaseName) {
        baseName = newBaseName;
        accDropped.set(0);
        gyrDropped.set(0);
        gpsDropped.set(0);
        gpsWriter.open(new File(folder, baseName + ".gps"));
        accWriter.open(new File(folder, baseName + ".acc"));
        gyrWriter.open(new File(folder, baseName + ".gyr"));
        if (ecu != null) {
            ecu.openChunk(new File(folder, baseName + ".ecu"));
        }
        writeMeta(false);
    }

    private void closeChunk() {
        gpsWriter.flush();
        accWriter.flush();
        gyrWriter.flush();
        if (ecu != null) {
            ecu.flush();
        }
        writeMeta(true);
        gpsWriter.close();
        accWriter.close();
        gyrWriter.close();
        if (ecu != null) {
            ecu.closeChunk();
        }
    }

    private void writeMeta(boolean chunkComplete) {
        File metaFile = new File(folder, baseName + ".meta");
        try {
            JSONObject meta = new JSONObject();
            meta.put("format", "gpslogger-fsae 3");
            meta.put("app_version", BuildConfig.VERSION_NAME);
            meta.put("time_column", "time_s = (elapsedRealtimeNanos - session_start_ns) / 1e9");
            meta.put("session_start_ns", sessionStartNs);
            meta.put("session_start_utc_ms", session.getFsaeSessionStartUtcMs());

            JSONObject chunk = new JSONObject();
            chunk.put("base_name", baseName);
            chunk.put("complete", chunkComplete);
            putStream(chunk, "gps", gpsWriter, gpsDropped);
            putStream(chunk, "acc", accWriter, accDropped);
            putStream(chunk, "gyr", gyrWriter, gyrDropped);
            meta.put("chunk", chunk);
            if (ecu != null) {
                ecu.putMeta(chunk, meta);
            }

            JSONObject device = new JSONObject();
            device.put("manufacturer", Build.MANUFACTURER);
            device.put("model", Build.MODEL);
            device.put("android_sdk", Build.VERSION.SDK_INT);
            meta.put("device", device);

            JSONObject sensors = new JSONObject();
            sensors.put("accel", describeSensor(Sensor.TYPE_ACCELEROMETER));
            sensors.put("gyro", describeSensor(Sensor.TYPE_GYROSCOPE));
            meta.put("sensors", sensors);

            JSONObject rates = new JSONObject();
            rates.put("requested_hz", requestedRateHz);
            putActualRate(rates, "acc_actual_hz", accWriter);
            putActualRate(rates, "gyr_actual_hz", gyrWriter);
            meta.put("rates", rates);

            String cal = session.getFsaeCalibration();
            if (!Strings.isNullOrEmpty(cal)) {
                meta.put("calibration", new JSONObject(cal));
            }

            try (Writer w = new OutputStreamWriter(new FileOutputStream(metaFile, false), StandardCharsets.UTF_8)) {
                w.write(meta.toString(2));
                w.write('\n');
            }
        } catch (JSONException | IOException e) {
            LOG.error("Could not write " + metaFile.getName(), e);
        }
    }

    private void putStream(JSONObject o, String name, CsvStreamWriter w, AtomicLong dropped) throws JSONException {
        o.put(name + "_rows", w.getRows());
        o.put(name + "_dropped_backwards", dropped.get());
        if (w.getRows() > 0) {
            o.put(name + "_first_time_s", (w.getFirstNs() - sessionStartNs) / 1e9);
            o.put(name + "_last_time_s", (w.getLastNs() - sessionStartNs) / 1e9);
        }
    }

    private void putActualRate(JSONObject o, String key, CsvStreamWriter w) throws JSONException {
        double seconds = (w.getLastNs() - w.getFirstNs()) / 1e9;
        if (w.getRows() > 1 && seconds > 0) {
            o.put(key, Math.round((w.getRows() - 1) / seconds * 10) / 10.0);
        }
    }

    private JSONObject describeSensor(int sensorType) throws JSONException {
        JSONObject o = new JSONObject();
        Sensor s = sensorManager.getDefaultSensor(sensorType);
        if (s == null) {
            o.put("present", false);
            return o;
        }
        o.put("present", true);
        o.put("name", s.getName());
        o.put("vendor", s.getVendor());
        o.put("max_range", s.getMaximumRange());
        o.put("resolution", s.getResolution());
        o.put("min_delay_us", s.getMinDelay());
        o.put("fifo_max_events", s.getFifoMaxEventCount());
        return o;
    }

    /**
     * Mean and standard deviation of each axis over the first seconds of a session. Only valid
     * if the car was stationary; the std values let post-processing check that.
     */
    private class Calibration {
        private final long startNs;
        private final double[] accelSum = new double[3], accelSq = new double[3];
        private final double[] gyroSum = new double[3], gyroSq = new double[3];
        private long accelN, gyroN;

        Calibration(long startNs) {
            this.startNs = startNs;
        }

        void add(boolean isAccel, long ns, float[] v) {
            if (ns < startNs) {
                return;
            }
            if (ns > startNs + CALIBRATION_NS) {
                finish();
                return;
            }
            double[] sum = isAccel ? accelSum : gyroSum;
            double[] sq = isAccel ? accelSq : gyroSq;
            for (int i = 0; i < 3; i++) {
                sum[i] += v[i];
                sq[i] += v[i] * v[i];
            }
            if (isAccel) accelN++; else gyroN++;
        }

        private void finish() {
            calibration = null;
            try {
                JSONObject o = new JSONObject();
                o.put("window_start_time_s", (startNs - sessionStartNs) / 1e9);
                o.put("window_s", CALIBRATION_NS / 1e9);
                o.put("assumes_stationary", true);
                o.put("accel_samples", accelN);
                o.put("gyro_samples", gyroN);
                if (accelN > 1) {
                    o.put("gravity_mps2", mean(accelSum, accelN));
                    o.put("accel_std_mps2", std(accelSum, accelSq, accelN));
                }
                if (gyroN > 1) {
                    o.put("gyro_bias_rads", mean(gyroSum, gyroN));
                    o.put("gyro_std_rads", std(gyroSum, gyroSq, gyroN));
                }
                session.setFsaeCalibration(o.toString());
                LOG.info("FSAE calibration: " + o);
            } catch (JSONException e) {
                LOG.error("Could not record calibration", e);
            }
        }

        private JSONArray mean(double[] sum, long n) throws JSONException {
            JSONArray a = new JSONArray();
            for (int i = 0; i < 3; i++) a.put(sum[i] / n);
            return a;
        }

        private JSONArray std(double[] sum, double[] sq, long n) throws JSONException {
            JSONArray a = new JSONArray();
            for (int i = 0; i < 3; i++) {
                double m = sum[i] / n;
                a.put(Math.sqrt(Math.max(0, sq[i] / n - m * m)));
            }
            return a;
        }
    }
}
