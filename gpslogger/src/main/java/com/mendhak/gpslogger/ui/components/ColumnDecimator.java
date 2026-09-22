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

package com.mendhak.gpslogger.ui.components;

/**
 * Buckets a time series into one entry per pixel column, keeping min, max, first and last so
 * that spikes survive when thousands of samples share a few hundred pixels.
 */
public final class ColumnDecimator {

    public int columns;
    public boolean[] has = new boolean[0];
    public float[] min = new float[0], max = new float[0], first = new float[0], last = new float[0];
    public long[] firstT = new long[0], lastT = new long[0];

    /**
     * Samples outside [startNs, endNs] and NaN values are skipped. Times must be increasing.
     */
    public void run(int n, long[] t, float[] v, long startNs, long endNs, int cols) {
        ensure(cols);
        columns = cols;
        for (int c = 0; c < cols; c++) {
            has[c] = false;
        }
        long span = endNs - startNs;
        if (cols <= 0 || span <= 0) {
            return;
        }
        for (int i = 0; i < n; i++) {
            long ti = t[i];
            float vi = v[i];
            if (ti < startNs || ti > endNs || Float.isNaN(vi)) {
                continue;
            }
            int c = (int) ((ti - startNs) * cols / span);
            if (c >= cols) {
                c = cols - 1;
            }
            if (!has[c]) {
                has[c] = true;
                min[c] = max[c] = first[c] = vi;
                firstT[c] = ti;
            } else {
                if (vi < min[c]) min[c] = vi;
                if (vi > max[c]) max[c] = vi;
            }
            last[c] = vi;
            lastT[c] = ti;
        }
    }

    private void ensure(int cols) {
        if (has.length >= cols) {
            return;
        }
        has = new boolean[cols];
        min = new float[cols];
        max = new float[cols];
        first = new float[cols];
        last = new float[cols];
        firstT = new long[cols];
        lastT = new long[cols];
    }
}
