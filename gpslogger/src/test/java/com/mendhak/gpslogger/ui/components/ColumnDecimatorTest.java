package com.mendhak.gpslogger.ui.components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ColumnDecimatorTest {

    @Test
    public void keepsSpikeInsideAColumn() {
        long[] t = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9};
        float[] v = {0, 0, 0, 9, 0, -4, 0, 0, 0, 1};
        ColumnDecimator d = new ColumnDecimator();

        d.run(t.length, t, v, 0, 10, 2);

        assertEquals(9f, d.max[0], 0);
        assertEquals(0f, d.min[0], 0);
        assertEquals(-4f, d.min[1], 0);
        assertEquals(0f, d.first[0], 0);
        assertEquals(1f, d.last[1], 0);
        assertEquals(9, d.lastT[1]);
    }

    @Test
    public void skipsNaNAndOutOfWindow() {
        long[] t = {-5, 2, 4, 20};
        float[] v = {100, Float.NaN, 3, 100};
        ColumnDecimator d = new ColumnDecimator();

        d.run(t.length, t, v, 0, 10, 5);

        assertFalse(d.has[0]);
        assertFalse(d.has[1]);
        assertTrue(d.has[2]);
        assertEquals(3f, d.max[2], 0);
        assertFalse(d.has[4]);
    }

    @Test
    public void endTimeLandsInLastColumn() {
        long[] t = {10};
        float[] v = {1};
        ColumnDecimator d = new ColumnDecimator();

        d.run(1, t, v, 0, 10, 4);

        assertTrue(d.has[3]);
    }

    @Test
    public void niceStep_isOneTwoOrFive() {
        assertEquals(0.5, StripChartView.niceStep(2.0, 4), 1e-12);
        assertEquals(2.0, StripChartView.niceStep(10.0, 5), 1e-12);
        assertEquals(5.0, StripChartView.niceStep(30.0, 5), 1e-12);
        assertEquals(100.0, StripChartView.niceStep(400.0, 4), 1e-12);
    }
}
