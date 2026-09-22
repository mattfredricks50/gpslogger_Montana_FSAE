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
 * Fixed-size ring of timestamped samples for the live views. One writer thread, any number of
 * reader threads, no locks and no allocation per sample, so it can sit on the sensor thread.
 * <p>
 * Readers never block the writer. If the writer laps a reader mid-copy, the overwritten oldest
 * samples are dropped from the copy instead of being returned torn.
 */
public final class SampleRing {

    private final int capacity;
    private final int mask;
    private final long[] times;
    private final double[][] channels;

    // Total samples ever written; slot = index & mask. Volatile write publishes the slot contents.
    private volatile long written;
    // Samples below this index were cleared
    private volatile long floor;

    /**
     * @param capacity power of two
     */
    public SampleRing(int capacity, int channelCount) {
        if (capacity <= 0 || Integer.bitCount(capacity) != 1) {
            throw new IllegalArgumentException("capacity must be a power of two");
        }
        this.capacity = capacity;
        this.mask = capacity - 1;
        this.times = new long[capacity];
        this.channels = new double[channelCount][capacity];
    }

    public int capacity() {
        return capacity;
    }

    public int channelCount() {
        return channels.length;
    }

    /** Writer thread only. */
    void add(long ns, double a, double b, double c) {
        int i = (int) (written & mask);
        channels[0][i] = a;
        channels[1][i] = b;
        channels[2][i] = c;
        commit(i, ns);
    }

    /** Writer thread only. */
    void add(long ns, double a, double b, double c, double d, double e) {
        int i = (int) (written & mask);
        channels[0][i] = a;
        channels[1][i] = b;
        channels[2][i] = c;
        channels[3][i] = d;
        channels[4][i] = e;
        commit(i, ns);
    }

    private void commit(int slot, long ns) {
        times[slot] = ns;
        written = written + 1;
    }

    /** Forgets everything written so far. Safe to call while readers are copying. */
    void clear() {
        floor = written;
    }

    public boolean isEmpty() {
        return written <= floor;
    }

    /** Time of the newest sample, or {@link Long#MIN_VALUE} if empty. */
    public long latestTime() {
        long w = written;
        if (w <= floor) {
            return Long.MIN_VALUE;
        }
        return times[(int) ((w - 1) & mask)];
    }

    /** Channel value of the newest sample, or NaN if empty. */
    public double latest(int channel) {
        long w = written;
        if (w <= floor) {
            return Double.NaN;
        }
        return channels[channel][(int) ((w - 1) & mask)];
    }

    /**
     * Copies every sample with time &gt;= fromNs, oldest first.
     *
     * @param tOut one entry per sample, length &gt;= capacity
     * @param out  one array per channel, each length &gt;= capacity
     * @return number of samples copied
     */
    public int copySince(long fromNs, long[] tOut, double[][] out) {
        long end = written;
        long start = findStart(end, fromNs);

        int n = 0;
        for (long k = start; k < end; k++) {
            int i = (int) (k & mask);
            tOut[n] = times[i];
            for (int ch = 0; ch < channels.length; ch++) {
                out[ch][n] = channels[ch][i];
            }
            n++;
        }

        // The writer may have lapped us while copying; its next slot could be half written too.
        long firstIntact = written - capacity + 1;
        if (start < firstIntact) {
            int skip = (int) Math.min(n, firstIntact - start);
            n -= skip;
            System.arraycopy(tOut, skip, tOut, 0, n);
            for (int ch = 0; ch < channels.length; ch++) {
                System.arraycopy(out[ch], skip, out[ch], 0, n);
            }
        }
        return n;
    }

    /**
     * Sample rate over the newest {@code windowNs} of data, from sample timestamps rather than
     * wall time, so bursty batched delivery doesn't skew it. 0 if fewer than two samples.
     */
    public double rateHz(long windowNs) {
        long end = written;
        long latest = latestTime();
        if (latest == Long.MIN_VALUE) {
            return 0;
        }
        long start = findStart(end, latest - windowNs);
        long n = end - start;
        if (n < 2) {
            return 0;
        }
        long first = times[(int) (start & mask)];
        long spanNs = latest - first;
        return spanNs > 0 ? (n - 1) * 1e9 / spanNs : 0;
    }

    private long findStart(long end, long fromNs) {
        // One short of a full lap: the slot at written - capacity is the writer's next target
        long lo = Math.max(floor, end - capacity + 1);
        long start = end;
        while (start > lo && times[(int) ((start - 1) & mask)] >= fromNs) {
            start--;
        }
        return start;
    }
}
