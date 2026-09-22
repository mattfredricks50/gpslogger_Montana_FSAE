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

import android.os.SystemClock;

import com.mendhak.gpslogger.common.EventBusHook;
import com.mendhak.gpslogger.common.events.UploadEvents;

import de.greenrobot.event.EventBus;

/**
 * In-process tap on the FSAE streams for the live views. {@link FsaeLogger} writes samples into
 * the rings as it logs them; views poll the rings at their own frame rate. Nothing here touches
 * the log files.
 */
public final class LiveTelemetry {

    public static final int X = 0, Y = 1, Z = 2;
    public static final int GPS_SPEED_MPS = 0, GPS_LAT = 1, GPS_LON = 2, GPS_HACC_M = 3, GPS_SATS = 4;

    // ~40 s at 400 Hz, ~30 min of 1 Hz GPS
    public static final int IMU_CAPACITY = 1 << 14;
    public static final int GPS_CAPACITY = 1 << 11;

    private static final LiveTelemetry INSTANCE = new LiveTelemetry();

    public final SampleRing acc = new SampleRing(IMU_CAPACITY, 3);
    public final SampleRing gyr = new SampleRing(IMU_CAPACITY, 3);
    public final SampleRing gps = new SampleRing(GPS_CAPACITY, 5);

    private volatile FsaeLogger source;
    private volatile MountTransform mount = MountTransform.IDENTITY;

    private volatile long lastUploadElapsedMs;
    private volatile boolean lastUploadOk;
    private volatile String lastUploadMessage;

    private LiveTelemetry() {
        EventBus.getDefault().register(this);
    }

    public static LiveTelemetry getInstance() {
        return INSTANCE;
    }

    void attach(FsaeLogger logger) {
        source = logger;
    }

    void detach(FsaeLogger logger) {
        if (source == logger) {
            source = null;
        }
    }

    void clear() {
        acc.clear();
        gyr.clear();
        gps.clear();
    }

    public MountTransform getMountTransform() {
        return mount;
    }

    public void setMountTransform(MountTransform transform) {
        mount = transform == null ? MountTransform.IDENTITY : transform;
    }

    /**
     * Current logger state. Main thread only, like the logger's chunk bookkeeping.
     */
    public void fillStats(Stats out) {
        FsaeLogger logger = source;
        if (logger == null) {
            out.running = false;
            return;
        }
        logger.fillStats(out);
    }

    /** Elapsed-realtime ms of the last Drive upload result, 0 if none since the app started. */
    public long getLastUploadElapsedMs() {
        return lastUploadElapsedMs;
    }

    public boolean wasLastUploadOk() {
        return lastUploadOk;
    }

    public String getLastUploadMessage() {
        return lastUploadMessage;
    }

    @EventBusHook
    public void onEvent(UploadEvents.GoogleDrive upload) {
        lastUploadOk = upload.success;
        lastUploadMessage = upload.message;
        lastUploadElapsedMs = SystemClock.elapsedRealtime();
    }

    public static final class Stats {
        public boolean running;
        public String chunkName;
        public long sessionStartNs;
        public long chunkStartNs;
        public int requestedHz;
        public long accRows, gyrRows, gpsRows;
        public long accDropped, gyrDropped, gpsDropped;
    }
}
