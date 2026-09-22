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

package com.mendhak.gpslogger.ui.fragments.display;

import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.loggers.fsae.LiveTelemetry;
import com.mendhak.gpslogger.loggers.fsae.SampleRing;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Locale;

/**
 * Pit-lane health check for the FSAE logger: are the streams running at rate, is GPS fixed,
 * did calibration see a still car, did the last chunk reach Drive.
 */
public class FsaeStatusFragment extends GenericViewFragment {

    private static final long REFRESH_MS = 250;
    private static final long IMU_RATE_WINDOW_NS = 2_000_000_000L;
    private static final long GPS_RATE_WINDOW_NS = 5_000_000_000L;

    // Rough limits for colouring values; tune once we have data from the car
    private static final double RATE_WARN_FRACTION = 0.9;
    private static final double FIX_AGE_ERROR_S = 2.0;
    private static final double HACC_WARN_M = 10.0;
    private static final double CAL_ACCEL_STD_WARN_MPS2 = 0.3;
    private static final double CAL_GYRO_STD_WARN_RADS = 0.05;

    private final LiveTelemetry live = LiveTelemetry.getInstance();
    private final PreferenceHelper preferenceHelper = PreferenceHelper.getInstance();
    private final Session session = Session.getInstance();
    private final LiveTelemetry.Stats stats = new LiveTelemetry.Stats();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            update();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private LinearLayout rows;
    private int normalColor, warnColor, errorColor;

    private TextView notice;
    private TextView state, sessionTime, chunk, chunkAge, nextRotation;
    private TextView accRate, accRows, accDropped;
    private TextView gyrRate, gyrRows, gyrDropped;
    private TextView fixAge, accuracy, satellites, speed, gpsRate, gpsRows, gpsDropped;
    private TextView calStatus, calGravity, calAccelStd, calGyroStd;
    private TextView uploadLast, uploadWhen, uploadMessage;

    private String parsedCalibration;
    private double calGravityMps2 = Double.NaN, calAccelStdMax = Double.NaN, calGyroStdMax = Double.NaN;

    public static FsaeStatusFragment newInstance() {
        return new FsaeStatusFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_fsae_status, container, false);
        rows = root.findViewById(R.id.fsae_status_rows);

        warnColor = ContextCompat.getColor(requireContext(), R.color.warningColor);
        errorColor = ContextCompat.getColor(requireContext(), R.color.errorColor);

        notice = new TextView(requireContext());
        notice.setTextColor(warnColor);
        notice.setPadding(0, dp(8), 0, 0);
        notice.setVisibility(View.GONE);
        rows.addView(notice);

        section(R.string.fsae_section_logger);
        state = row(R.string.fsae_state);
        normalColor = state.getCurrentTextColor();
        sessionTime = row(R.string.fsae_session_time);
        chunk = row(R.string.fsae_chunk);
        chunkAge = row(R.string.fsae_chunk_age);
        nextRotation = row(R.string.fsae_next_rotation);

        section(R.string.fsae_section_accel);
        accRate = row(R.string.fsae_rate);
        accRows = row(R.string.fsae_rows);
        accDropped = row(R.string.fsae_dropped);

        section(R.string.fsae_section_gyro);
        gyrRate = row(R.string.fsae_rate);
        gyrRows = row(R.string.fsae_rows);
        gyrDropped = row(R.string.fsae_dropped);

        section(R.string.fsae_section_gps);
        fixAge = row(R.string.fsae_fix_age);
        accuracy = row(R.string.fsae_accuracy);
        satellites = row(R.string.fsae_satellites);
        speed = row(R.string.fsae_speed);
        gpsRate = row(R.string.fsae_rate);
        gpsRows = row(R.string.fsae_rows);
        gpsDropped = row(R.string.fsae_dropped);

        section(R.string.fsae_section_calibration);
        calStatus = row(R.string.fsae_cal_status);
        calGravity = row(R.string.fsae_cal_gravity);
        calAccelStd = row(R.string.fsae_cal_accel_std);
        calGyroStd = row(R.string.fsae_cal_gyro_std);

        section(R.string.fsae_section_upload);
        uploadLast = row(R.string.fsae_upload_last);
        uploadWhen = row(R.string.fsae_upload_when);
        uploadMessage = row(R.string.fsae_upload_message);

        return root;
    }

    @Override
    public void onResume() {
        super.onResume();
        handler.post(refresh);
    }

    @Override
    public void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }

    private void update() {
        long now = SystemClock.elapsedRealtimeNanos();
        live.fillStats(stats);

        if (!preferenceHelper.shouldLogFsaeStreams()) {
            set(notice, getString(R.string.fsae_streams_off), warnColor);
            notice.setVisibility(View.VISIBLE);
        } else {
            notice.setVisibility(View.GONE);
        }

        // Logger
        set(state, getString(stats.running ? R.string.fsae_state_logging : R.string.fsae_state_stopped),
                stats.running ? normalColor : warnColor);
        if (stats.running) {
            set(sessionTime, duration(now - stats.sessionStartNs), normalColor);
            set(chunk, Strings.isNullOrEmpty(stats.chunkName) ? "--" : stats.chunkName, normalColor);
            set(chunkAge, duration(now - stats.chunkStartNs), normalColor);
            if (preferenceHelper.shouldCreateNewFileInChunks()) {
                long nextNs = stats.chunkStartNs + preferenceHelper.getNewFileChunkMinutes() * 60_000_000_000L;
                set(nextRotation, duration(Math.max(0, nextNs - now)), normalColor);
            } else {
                set(nextRotation, getString(R.string.fsae_rotation_off), normalColor);
            }
        } else {
            set(sessionTime, "--", normalColor);
            set(chunk, "--", normalColor);
            set(chunkAge, "--", normalColor);
            set(nextRotation, "--", normalColor);
        }

        // IMU
        imuRows(live.acc, accRate, accRows, accDropped, stats.accRows, stats.accDropped);
        imuRows(live.gyr, gyrRate, gyrRows, gyrDropped, stats.gyrRows, stats.gyrDropped);

        // GPS
        SampleRing gps = live.gps;
        long fixNs = gps.latestTime();
        if (fixNs == Long.MIN_VALUE) {
            set(fixAge, getString(R.string.fsae_no_fix), stats.running ? errorColor : normalColor);
            set(accuracy, "--", normalColor);
            set(satellites, "--", normalColor);
            set(speed, "--", normalColor);
        } else {
            double ageS = (now - fixNs) / 1e9;
            set(fixAge, String.format(Locale.US, "%.1f s", ageS),
                    stats.running && ageS > FIX_AGE_ERROR_S ? errorColor : normalColor);
            double hacc = gps.latest(LiveTelemetry.GPS_HACC_M);
            set(accuracy, Double.isNaN(hacc) ? "--" : String.format(Locale.US, "%.1f m", hacc),
                    hacc > HACC_WARN_M ? warnColor : normalColor);
            double sats = gps.latest(LiveTelemetry.GPS_SATS);
            set(satellites, Double.isNaN(sats) ? "--" : String.valueOf((int) sats), normalColor);
            double v = gps.latest(LiveTelemetry.GPS_SPEED_MPS);
            set(speed, Double.isNaN(v) ? "--" : String.format(Locale.US, "%.1f km/h", v * 3.6), normalColor);
        }
        set(gpsRate, getString(R.string.fsae_gps_rate_format, gps.rateHz(GPS_RATE_WINDOW_NS)), normalColor);
        set(gpsRows, stats.running ? String.valueOf(stats.gpsRows) : "--", normalColor);
        set(gpsDropped, stats.running ? String.valueOf(stats.gpsDropped) : "--",
                stats.running && stats.gpsDropped > 0 ? warnColor : normalColor);

        // Calibration
        String cal = session.getFsaeCalibration();
        if (Strings.isNullOrEmpty(cal)) {
            set(calStatus, getString(R.string.fsae_cal_pending), stats.running ? warnColor : normalColor);
            set(calGravity, "--", normalColor);
            set(calAccelStd, "--", normalColor);
            set(calGyroStd, "--", normalColor);
        } else {
            parseCalibration(cal);
            set(calStatus, getString(R.string.fsae_cal_done), normalColor);
            set(calGravity, fmt(calGravityMps2, "%.3f m/s²"), normalColor);
            set(calAccelStd, fmt(calAccelStdMax, "%.3f m/s²"),
                    calAccelStdMax > CAL_ACCEL_STD_WARN_MPS2 ? warnColor : normalColor);
            set(calGyroStd, fmt(calGyroStdMax * 180 / Math.PI, "%.2f deg/s"),
                    calGyroStdMax > CAL_GYRO_STD_WARN_RADS ? warnColor : normalColor);
        }

        // Upload
        long uploadMs = live.getLastUploadElapsedMs();
        if (uploadMs == 0) {
            set(uploadLast, getString(R.string.fsae_upload_none), normalColor);
            set(uploadWhen, "--", normalColor);
            set(uploadMessage, "--", normalColor);
        } else {
            boolean ok = live.wasLastUploadOk();
            set(uploadLast, getString(ok ? R.string.fsae_upload_ok : R.string.fsae_upload_failed), ok ? normalColor : errorColor);
            set(uploadWhen, getString(R.string.fsae_ago_format,
                    duration((SystemClock.elapsedRealtime() - uploadMs) * 1_000_000L)), normalColor);
            String msg = live.getLastUploadMessage();
            set(uploadMessage, Strings.isNullOrEmpty(msg) ? "--" : msg, normalColor);
        }
    }

    private void imuRows(SampleRing ring, TextView rate, TextView rowCount, TextView dropped, long rowsN, long droppedN) {
        double hz = ring.rateHz(IMU_RATE_WINDOW_NS);
        boolean slow = stats.running && stats.requestedHz > 0 && hz < stats.requestedHz * RATE_WARN_FRACTION;
        set(rate, stats.running
                        ? getString(R.string.fsae_rate_format, hz, stats.requestedHz)
                        : String.format(Locale.US, "%.1f Hz", hz),
                slow ? errorColor : normalColor);
        set(rowCount, stats.running ? String.valueOf(rowsN) : "--", normalColor);
        set(dropped, stats.running ? String.valueOf(droppedN) : "--",
                stats.running && droppedN > 0 ? warnColor : normalColor);
    }

    private void parseCalibration(String cal) {
        if (cal.equals(parsedCalibration)) {
            return;
        }
        parsedCalibration = cal;
        calGravityMps2 = calAccelStdMax = calGyroStdMax = Double.NaN;
        try {
            JSONObject o = new JSONObject(cal);
            JSONArray g = o.optJSONArray("gravity_mps2");
            if (g != null && g.length() == 3) {
                calGravityMps2 = Math.sqrt(sq(g.getDouble(0)) + sq(g.getDouble(1)) + sq(g.getDouble(2)));
            }
            calAccelStdMax = maxOf(o.optJSONArray("accel_std_mps2"));
            calGyroStdMax = maxOf(o.optJSONArray("gyro_std_rads"));
        } catch (JSONException e) {
            // Leave as NaN, shown as "--"
        }
    }

    private static double maxOf(JSONArray a) throws JSONException {
        if (a == null || a.length() == 0) {
            return Double.NaN;
        }
        double m = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < a.length(); i++) {
            m = Math.max(m, a.getDouble(i));
        }
        return m;
    }

    private static double sq(double v) {
        return v * v;
    }

    private static String fmt(double v, String format) {
        return Double.isNaN(v) ? "--" : String.format(Locale.US, format, v);
    }

    /** h:mm:ss or m:ss */
    static String duration(long ns) {
        long s = Math.max(0, ns / 1_000_000_000L);
        long h = s / 3600, m = (s / 60) % 60, sec = s % 60;
        return h > 0
                ? String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
                : String.format(Locale.US, "%d:%02d", m, sec);
    }

    private void set(TextView view, String text, int color) {
        // Skip no-op updates so the scroll view isn't relaid out four times a second
        if (!text.contentEquals(view.getText())) {
            view.setText(text);
        }
        if (view.getCurrentTextColor() != color) {
            view.setTextColor(color);
        }
    }

    private void section(int titleRes) {
        TextView t = new TextView(requireContext());
        t.setText(titleRes);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        t.setPadding(0, dp(16), 0, dp(4));
        rows.addView(t);
    }

    private TextView row(int labelRes) {
        LinearLayout line = new LinearLayout(requireContext());
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(0, dp(2), 0, dp(2));

        TextView label = new TextView(requireContext());
        label.setText(labelRes);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        line.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView value = new TextView(requireContext());
        value.setText("--");
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        value.setTypeface(Typeface.MONOSPACE);
        value.setGravity(android.view.Gravity.END);
        line.addView(value, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        rows.addView(line);
        return value;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
