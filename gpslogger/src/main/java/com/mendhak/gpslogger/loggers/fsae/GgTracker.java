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

import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;

/**
 * Car-frame g and the peak g pulled this session, fed from the accelerometer on the logger's
 * sensor thread so the peaks count even when no screen is open. Values are smoothed with a
 * 50 ms time constant, which keeps engine vibration from registering as grip.
 */
public final class GgTracker {

    private static final GgTracker INSTANCE = new GgTracker();
    private static final double TAU_S = 0.05;
    private static final long FRAME_RETRY_NS = 500_000_000L;

    public static GgTracker getInstance() {
        return INSTANCE;
    }

    /** Snapshot for the UI. */
    public static final class Peaks {
        public boolean ready;
        public double longG, latG;
        public double maxAccel, maxBrake, maxRight, maxLeft, maxCombined;
    }

    // Sensor thread
    private CarFrame frame;
    private long lastNs;
    private long lastFrameAttemptNs;
    private double longS, latS;
    private boolean primed;

    // Written on the sensor thread, read on the UI thread
    private volatile boolean ready;
    private volatile double longG, latG;
    private volatile double maxAccel, maxBrake, maxRight, maxLeft, maxCombined;
    private volatile boolean resetRequested = true;
    private volatile CarFrame frameForUi;

    private GgTracker() {
    }

    /** Called when logging starts, and by the UI's reset button. */
    public void reset() {
        resetRequested = true;
    }

    /** The car frame in use, or null until the Start calibration has finished. */
    public CarFrame getFrame() {
        return frameForUi;
    }

    /** Sensor thread only. */
    void addAccel(long ns, double x, double y, double z) {
        if (resetRequested) {
            resetRequested = false;
            frame = null;
            frameForUi = null;
            primed = false;
            ready = false;
            maxAccel = maxBrake = maxRight = maxLeft = maxCombined = 0;
            lastFrameAttemptNs = 0;
        }
        if (frame == null) {
            // The calibration finishes about 2 s after Start; check for it a couple of times a second
            if (ns - lastFrameAttemptNs < FRAME_RETRY_NS) {
                return;
            }
            lastFrameAttemptNs = ns;
            frame = CarFrame.fromCalibrationJson(Session.getInstance().getFsaeCalibration(),
                    PreferenceHelper.getInstance().getFsaeMountForward());
            frameForUi = frame;
            if (frame == null) {
                return;
            }
        }

        double lg = frame.longG(x, y, z);
        double la = frame.latG(x, y, z);
        if (!primed) {
            longS = lg;
            latS = la;
            primed = true;
        } else {
            double dt = (ns - lastNs) / 1e9;
            double a = dt <= 0 ? 0 : 1 - Math.exp(-dt / TAU_S);
            longS += a * (lg - longS);
            latS += a * (la - latS);
        }
        lastNs = ns;

        longG = longS;
        latG = latS;
        if (longS > maxAccel) maxAccel = longS;
        if (-longS > maxBrake) maxBrake = -longS;
        if (latS > maxRight) maxRight = latS;
        if (-latS > maxLeft) maxLeft = -latS;
        double c = Math.hypot(longS, latS);
        if (c > maxCombined) maxCombined = c;
        ready = true;
    }

    public void snapshot(Peaks out) {
        out.ready = ready;
        out.longG = longG;
        out.latG = latG;
        out.maxAccel = maxAccel;
        out.maxBrake = maxBrake;
        out.maxRight = maxRight;
        out.maxLeft = maxLeft;
        out.maxCombined = maxCombined;
    }
}
