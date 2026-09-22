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

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Car axes in phone coordinates, the same way tools/plotjuggler/car_frame.py does it:
 * up from the stationary calibration's gravity vector, forward from the phone axis that points
 * at the car's nose (made level), right = forward x up.
 * <p>
 * Signs: long + accelerating, lat + accelerating toward the right (right turn), yaw + turning left.
 */
public final class CarFrame {

    public static final double G = 9.80665;

    private final double[] up;
    private final double[] forward;
    private final double[] right;
    private final double gravity;

    private CarFrame(double[] up, double[] forward, double gravity) {
        this.up = up;
        this.forward = forward;
        this.right = cross(forward, up);
        this.gravity = gravity;
    }

    /**
     * @param gravityPhone stationary accelerometer mean, m/s^2, phone axes
     * @param forwardAxis  "+x", "-x", "+y", "-y", "+z" or "-z"
     * @return null if the axis is (nearly) vertical in this mount or gravity is unusable
     */
    public static CarFrame fromGravity(double[] gravityPhone, String forwardAxis) {
        double g = norm(gravityPhone);
        if (!(g > 1)) {
            return null;
        }
        double[] up = scale(gravityPhone, 1 / g);
        double[] f = axis(forwardAxis);
        double d = dot(f, up);
        double[] level = {f[0] - d * up[0], f[1] - d * up[1], f[2] - d * up[2]};
        double n = norm(level);
        if (n < 0.3) {
            return null;
        }
        return new CarFrame(up, scale(level, 1 / n), g);
    }

    /** From the calibration JSON the logger stores in the session; null until it exists. */
    public static CarFrame fromCalibrationJson(String json, String forwardAxis) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            JSONArray a = new JSONObject(json).getJSONArray("gravity_mps2");
            return fromGravity(new double[]{a.getDouble(0), a.getDouble(1), a.getDouble(2)}, forwardAxis);
        } catch (Exception e) {
            return null;
        }
    }

    /** Longitudinal acceleration, g. */
    public double longG(double x, double y, double z) {
        return (x * forward[0] + y * forward[1] + z * forward[2]) / G;
    }

    /** Lateral acceleration, g, + toward the car's right. */
    public double latG(double x, double y, double z) {
        return (x * right[0] + y * right[1] + z * right[2]) / G;
    }

    /** Vertical acceleration with gravity removed, g. */
    public double vertG(double x, double y, double z) {
        return (x * up[0] + y * up[1] + z * up[2] - gravity) / G;
    }

    /** Yaw rate from gyro rad/s, deg/s, + turning left. */
    public double yawDps(double x, double y, double z) {
        return Math.toDegrees(x * up[0] + y * up[1] + z * up[2]);
    }

    private static double[] axis(String name) {
        double s = name != null && name.startsWith("-") ? -1 : 1;
        char c = name == null || name.isEmpty() ? 'y' : name.charAt(name.length() - 1);
        switch (c) {
            case 'x':
                return new double[]{s, 0, 0};
            case 'z':
                return new double[]{0, 0, s};
            default:
                return new double[]{0, s, 0};
        }
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    private static double norm(double[] a) {
        return Math.sqrt(dot(a, a));
    }

    private static double[] scale(double[] a, double s) {
        return new double[]{a[0] * s, a[1] * s, a[2] * s};
    }

    private static double[] cross(double[] a, double[] b) {
        return new double[]{
                a[1] * b[2] - a[2] * b[1],
                a[2] * b[0] - a[0] * b[2],
                a[0] * b[1] - a[1] * b[0]};
    }
}
