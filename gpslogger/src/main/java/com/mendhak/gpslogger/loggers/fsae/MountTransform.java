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

/**
 * Rotates phone-frame accel/gyro vectors into the car frame for the live views only; the log
 * files always stay raw. Identity until the phone's mount in the car is known.
 * <p>
 * TODO(mount): build the real rotation (and pick the car axis convention) once the mount is
 * fixed, then install it with {@link LiveTelemetry#setMountTransform}. Gravity removal or
 * filtering, if wanted, belong in their own step after this rotation.
 */
public final class MountTransform {

    public static final MountTransform IDENTITY = new MountTransform(new double[]{
            1, 0, 0,
            0, 1, 0,
            0, 0, 1});

    private final double[] m;
    private final boolean identity;

    /**
     * @param rowMajor3x3 car = M * phone
     */
    public MountTransform(double[] rowMajor3x3) {
        if (rowMajor3x3.length != 9) {
            throw new IllegalArgumentException("need a 3x3 matrix");
        }
        m = rowMajor3x3.clone();
        identity = m[0] == 1 && m[1] == 0 && m[2] == 0
                && m[3] == 0 && m[4] == 1 && m[5] == 0
                && m[6] == 0 && m[7] == 0 && m[8] == 1;
    }

    /** True while no mount is configured, so views can label axes as phone axes. */
    public boolean isIdentity() {
        return identity;
    }

    /** out[0..2] = M * (x, y, z) */
    public void apply(double x, double y, double z, double[] out) {
        out[0] = m[0] * x + m[1] * y + m[2] * z;
        out[1] = m[3] * x + m[4] * y + m[5] * z;
        out[2] = m[6] * x + m[7] * y + m[8] * z;
    }
}
