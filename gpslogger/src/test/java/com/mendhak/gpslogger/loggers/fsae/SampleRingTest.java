package com.mendhak.gpslogger.loggers.fsae;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class SampleRingTest {

    private static long[] t(SampleRing r) {
        return new long[r.capacity()];
    }

    private static double[][] out(SampleRing r) {
        return new double[r.channelCount()][r.capacity()];
    }

    @Test
    public void copySince_returnsSamplesAtOrAfterTime_oldestFirst() {
        SampleRing r = new SampleRing(8, 3);
        for (int i = 1; i <= 5; i++) {
            r.add(i * 100L, i, -i, i * 10);
        }
        long[] t = t(r);
        double[][] o = out(r);

        int n = r.copySince(300, t, o);

        assertEquals(3, n);
        assertEquals(300, t[0]);
        assertEquals(500, t[2]);
        assertEquals(3.0, o[0][0], 0);
        assertEquals(-5.0, o[1][2], 0);
        assertEquals(50.0, o[2][2], 0);
    }

    @Test
    public void copySince_afterWrap_keepsNewestLessThanCapacity() {
        SampleRing r = new SampleRing(8, 3);
        for (int i = 1; i <= 20; i++) {
            r.add(i, i, 0, 0);
        }
        long[] t = t(r);
        double[][] o = out(r);

        int n = r.copySince(Long.MIN_VALUE, t, o);

        // One slot is held back for the writer's next sample
        assertEquals(7, n);
        assertEquals(14, t[0]);
        assertEquals(20, t[6]);
        assertEquals(20.0, o[0][6], 0);
    }

    @Test
    public void clear_hidesOldSamples() {
        SampleRing r = new SampleRing(8, 3);
        r.add(1, 1, 1, 1);
        r.add(2, 2, 2, 2);
        r.clear();

        assertTrue(r.isEmpty());
        assertEquals(Long.MIN_VALUE, r.latestTime());
        assertTrue(Double.isNaN(r.latest(0)));
        assertEquals(0, r.copySince(Long.MIN_VALUE, t(r), out(r)));

        r.add(3, 3, 3, 3);
        assertEquals(1, r.copySince(Long.MIN_VALUE, t(r), out(r)));
        assertEquals(3, r.latestTime());
    }

    @Test
    public void rateHz_usesSampleTimestamps() {
        SampleRing r = new SampleRing(1024, 3);
        // 200 Hz for 3 s
        for (int i = 0; i <= 600; i++) {
            r.add(i * 5_000_000L, 0, 0, 0);
        }
        assertEquals(200.0, r.rateHz(1_000_000_000L), 1e-9);
    }

    @Test
    public void rateHz_emptyOrSingleSample_isZero() {
        SampleRing r = new SampleRing(8, 3);
        assertEquals(0.0, r.rateHz(1_000_000_000L), 0);
        r.add(10, 0, 0, 0);
        assertEquals(0.0, r.rateHz(1_000_000_000L), 0);
    }

    @Test
    public void fiveChannelAdd_storesAllChannels() {
        SampleRing r = new SampleRing(4, 5);
        r.add(7, 1, 2, 3, 4, 5);
        for (int ch = 0; ch < 5; ch++) {
            assertEquals(ch + 1.0, r.latest(ch), 0);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void capacity_mustBePowerOfTwo() {
        new SampleRing(10, 3);
    }
}
