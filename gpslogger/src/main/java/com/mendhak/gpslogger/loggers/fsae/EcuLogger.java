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
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.SystemClock;

import com.mendhak.gpslogger.common.slf4j.Logs;

import org.json.JSONException;
import org.json.JSONObject;
import org.slf4j.Logger;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.Call;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okio.BufferedSource;

/**
 * Logs Speeduino data from an AirBear in Web Dash mode to {@code <base>.ecu}.
 * <p>
 * In that mode AirBear polls the ECU's secondary serial 'A' packet at 30 Hz and pushes it as JSON
 * over Server-Sent Events at {@code http://<airbear>/events}, to up to 3 clients. There is no ECU
 * timestamp, so {@code time_s} is the phone's receive time on the session clock; {@code airbear_ms}
 * is AirBear's millis() at send time, useful for judging Wi-Fi jitter.
 * <p>
 * Requires Speeduino's secondary serial protocol set to "Generic (Fixed List)"; AirBear decodes
 * the legacy fixed byte order. Scaling follows Speeduino's getLegacySecondarySerialLogEntry and
 * corrects AirBear's JSON where it treats signed or scaled fields as raw bytes.
 */
class EcuLogger {

    private static final Logger LOG = Logs.of(EcuLogger.class);

    static final String ECU_HEADER = "time_s,airbear_ms,rpm,map_kpa,tps_pct,afr,afr_target,clt_c,iat_c,batt_v,"
            + "advance_deg,pw1_ms,ve_pct,dwell_ms,baro_kpa,rpm_dot,tps_dot_pctps,"
            + "ego_corr_pct,wue_corr_pct,ae_corr_pct,gamma_corr_pct,running,dfco";

    // AirBear sends a ping every second, so a silent stream this long is dead
    private static final long READ_TIMEOUT_S = 5;
    private static final long FLUSH_INTERVAL_NS = 1_000_000_000L;

    private final ConnectivityManager connectivityManager;
    private final List<String> hosts;
    private final long sessionStartNs;
    private final CsvStreamWriter writer = new CsvStreamWriter(ECU_HEADER);

    private volatile boolean running;
    private volatile Call currentCall;
    private volatile Network wifiNetwork;
    private volatile String connectedHost;
    private volatile String lastError;
    private Thread thread;
    private ConnectivityManager.NetworkCallback wifiCallback;

    // Per-chunk, reset on openChunk
    private final AtomicLong duplicatesSkipped = new AtomicLong();
    private final AtomicLong droppedBackwards = new AtomicLong();
    private final AtomicLong parseErrors = new AtomicLong();
    private final AtomicLong noDataEvents = new AtomicLong();
    private final AtomicLong connects = new AtomicLong();

    // Stream thread only
    private final StringBuilder line = new StringBuilder(256);
    private String lastPayload;
    private long lastRowNs = Long.MIN_VALUE;
    private long lastFlushNs;

    /**
     * @param hostsCsv AirBear addresses to try in order, e.g. "speeduino.local,192.168.4.1"
     */
    EcuLogger(Context context, String hostsCsv, long sessionStartNs) {
        this.connectivityManager = (ConnectivityManager) context.getApplicationContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        this.sessionStartNs = sessionStartNs;
        this.hosts = new ArrayList<>();
        for (String h : hostsCsv.split(",")) {
            if (!h.trim().isEmpty()) {
                hosts.add(h.trim());
            }
        }
    }

    void openChunk(File file) {
        duplicatesSkipped.set(0);
        droppedBackwards.set(0);
        parseErrors.set(0);
        noDataEvents.set(0);
        connects.set(0);
        writer.open(file);
    }

    void closeChunk() {
        writer.close();
    }

    void flush() {
        writer.flush();
    }

    void start() {
        if (running || hosts.isEmpty()) {
            return;
        }
        running = true;
        requestWifiNetwork();
        thread = new Thread(new Runnable() {
            @Override
            public void run() {
                streamLoop();
            }
        }, "EcuLogger");
        thread.start();
    }

    void stop() {
        running = false;
        Call call = currentCall;
        if (call != null) {
            call.cancel();
        }
        if (thread != null) {
            thread.interrupt();
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        if (wifiCallback != null) {
            try {
                connectivityManager.unregisterNetworkCallback(wifiCallback);
            } catch (IllegalArgumentException ignored) {
            }
            wifiCallback = null;
        }
        writer.flush();
    }

    /**
     * When the phone is joined to AirBear's own access point, that Wi-Fi has no internet and
     * Android routes app traffic over mobile data instead. Requesting the Wi-Fi network (without
     * requiring internet) keeps it up and lets us bind the ECU socket to it, while Drive uploads
     * carry on over mobile data. If AirBear instead joins the phone's hotspot there is no Wi-Fi
     * network to bind to and the default route reaches it.
     */
    private void requestWifiNetwork() {
        NetworkRequest request = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        wifiCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                wifiNetwork = network;
            }

            @Override
            public void onLost(Network network) {
                if (network.equals(wifiNetwork)) {
                    wifiNetwork = null;
                }
            }
        };
        try {
            connectivityManager.requestNetwork(request, wifiCallback);
        } catch (RuntimeException e) {
            LOG.warn("Could not request Wi-Fi network for AirBear", e);
            wifiCallback = null;
        }
    }

    private void streamLoop() {
        long backoffMs = 1000;
        while (running) {
            for (String host : hosts) {
                if (!running) {
                    return;
                }
                try {
                    stream(host);
                    backoffMs = 1000;
                } catch (IOException e) {
                    if (running) {
                        lastError = host + ": " + e.getMessage();
                        LOG.debug("AirBear stream ended: " + lastError);
                    }
                } finally {
                    connectedHost = null;
                }
            }
            try {
                Thread.sleep(backoffMs);
            } catch (InterruptedException e) {
                return;
            }
            backoffMs = Math.min(backoffMs * 2, 5000);
        }
    }

    private OkHttpClient buildClient() {
        OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .retryOnConnectionFailure(false);
        final Network network = wifiNetwork;
        if (network != null) {
            builder.socketFactory(network.getSocketFactory());
            builder.dns(new okhttp3.Dns() {
                @Override
                public List<InetAddress> lookup(String hostname) throws java.net.UnknownHostException {
                    return Arrays.asList(network.getAllByName(hostname));
                }
            });
        }
        return builder.build();
    }

    private void stream(String host) throws IOException {
        Request request = new Request.Builder()
                .url("http://" + host + "/events")
                .header("Accept", "text/event-stream")
                .build();
        Call call = buildClient().newCall(request);
        currentCall = call;
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            connectedHost = host;
            connects.incrementAndGet();
            lastPayload = null;
            LOG.info("Connected to AirBear at " + host);

            BufferedSource source = response.body().source();
            SseParser parser = new SseParser(new SseParser.Listener() {
                @Override
                public void onEvent(String event, String id, String data, long receivedNs) {
                    dispatch(event, id, data, receivedNs);
                }
            });
            while (running) {
                String l = source.readUtf8Line();
                if (l == null) {
                    throw new IOException("stream closed");
                }
                parser.line(l, SystemClock.elapsedRealtimeNanos());
            }
        } finally {
            currentCall = null;
        }
    }

    private void dispatch(String event, String id, String data, long receivedNs) {
        if ("nodata".equals(event)) {
            noDataEvents.incrementAndGet();
            lastError = "AirBear reports no data from the ECU";
            return;
        }
        if (!"reading".equals(event)) {
            return; // hello / ping
        }
        // AirBear re-sends its last reading every tick even if no new ECU packet arrived
        if (data.equals(lastPayload)) {
            duplicatesSkipped.incrementAndGet();
            return;
        }
        lastPayload = data;
        if (receivedNs <= lastRowNs) {
            droppedBackwards.incrementAndGet();
            return;
        }

        JSONObject r;
        try {
            r = new JSONObject(data);
        } catch (JSONException e) {
            parseErrors.incrementAndGet();
            return;
        }
        lastRowNs = receivedNs;

        StringBuilder sb = line;
        sb.setLength(0);
        CsvStreamWriter.appendTime(sb, receivedNs, sessionStartNs);
        sb.append(',');
        if (id != null && id.matches("\\d+")) sb.append(id);
        appendRow(sb, r);
        sb.append('\n');
        writer.writeRow(receivedNs, sb);

        if (receivedNs - lastFlushNs > FLUSH_INTERVAL_NS) {
            lastFlushNs = receivedNs;
            writer.flush();
        }
    }

    /**
     * Converts AirBear's JSON (field names and scaling from its serialParser.cpp) to the .ecu
     * columns after time_s and airbear_ms.
     */
    static void appendRow(StringBuilder sb, JSONObject r) {
        field(sb, r.optDouble("rpm", Double.NaN), 0);
        field(sb, r.optDouble("MAP", Double.NaN), 0);
        // Speeduino TPS is 0-200 in 0.5% steps; AirBear passes the raw byte
        field(sb, r.optDouble("TPS", Double.NaN) * 0.5, 1);
        field(sb, r.optDouble("AFR1", Double.NaN), 1);
        field(sb, r.optDouble("afr_target", Double.NaN), 1);
        field(sb, r.optDouble("CLT", Double.NaN), 0);
        field(sb, r.optDouble("IAT", Double.NaN), 0);
        field(sb, r.optDouble("Battery_Voltage", Double.NaN), 1);
        // advance is int8 in the ECU; AirBear reads it unsigned
        field(sb, signed(r.optDouble("advance", Double.NaN), 8), 0);
        // PW is sent in microseconds; AirBear divides by 10
        field(sb, r.optDouble("PW1", Double.NaN) / 100.0, 3);
        field(sb, r.optDouble("VE", Double.NaN), 0);
        field(sb, r.optDouble("dwell", Double.NaN), 1);
        field(sb, r.optDouble("baro", Double.NaN), 0);
        // rpmDOT is int16; AirBear combines the bytes unsigned
        field(sb, signed(r.optDouble("rpmDOT", Double.NaN), 16), 0);
        // The ECU sends (int8)(tpsDOT / 10); AirBear multiplies the unsigned byte by 10
        field(sb, signed(r.optDouble("tps_DOT", Double.NaN) / 10.0, 8) * 10.0, 0);
        field(sb, r.optDouble("correction_o2", Double.NaN), 0);
        field(sb, r.optDouble("correction_wue", Double.NaN), 0);
        field(sb, r.optDouble("correction_ae", Double.NaN), 0);
        field(sb, r.optDouble("correction_total", Double.NaN), 0);
        field(sb, flag(r, "running"), 0);
        field(sb, flag(r, "dfco_active"), 0);
    }

    private static void field(StringBuilder sb, double v, int decimals) {
        sb.append(',');
        CsvStreamWriter.appendFixed(sb, v, decimals);
    }

    private static double signed(double unsigned, int bits) {
        if (Double.isNaN(unsigned)) {
            return unsigned;
        }
        long v = Math.round(unsigned);
        long half = 1L << (bits - 1);
        return v >= half ? v - (1L << bits) : v;
    }

    /** AirBear writes bit flags as the masked bit value or a boolean depending on version. */
    private static double flag(JSONObject r, String key) {
        Object o = r.opt(key);
        if (o instanceof Boolean) {
            return ((Boolean) o) ? 1 : 0;
        }
        if (o instanceof Number) {
            return ((Number) o).doubleValue() != 0 ? 1 : 0;
        }
        return Double.NaN;
    }

    void putMeta(JSONObject chunk, JSONObject meta) throws JSONException {
        chunk.put("ecu_rows", writer.getRows());
        chunk.put("ecu_dropped_backwards", droppedBackwards.get());
        chunk.put("ecu_duplicates_skipped", duplicatesSkipped.get());
        chunk.put("ecu_parse_errors", parseErrors.get());
        chunk.put("ecu_nodata_events", noDataEvents.get());
        chunk.put("ecu_connects", connects.get());
        if (writer.getRows() > 0) {
            chunk.put("ecu_first_time_s", (writer.getFirstNs() - sessionStartNs) / 1e9);
            chunk.put("ecu_last_time_s", (writer.getLastNs() - sessionStartNs) / 1e9);
        }

        JSONObject ecu = new JSONObject();
        ecu.put("source", "AirBear Web Dash SSE (Speeduino secondary serial 'A', Generic Fixed List)");
        ecu.put("hosts", new org.json.JSONArray(hosts));
        ecu.put("time_s_is", "phone receive time");
        ecu.put("tps_scale", "raw * 0.5 (Speeduino 0-200 TPS)");
        ecu.put("pw1_scale", "microseconds / 1000");
        if (connectedHost != null) ecu.put("connected_host", connectedHost);
        if (lastError != null) ecu.put("last_error", lastError);
        meta.put("ecu", ecu);
    }

    String getConnectedHost() {
        return connectedHost;
    }

    long getRows() {
        return writer.getRows();
    }
}
