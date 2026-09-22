package com.mendhak.gpslogger.loggers.fsae;

import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MountTransformTest {

    @Test
    public void identity_passesThrough() {
        double[] out = new double[3];
        MountTransform.IDENTITY.apply(1, 2, 3, out);
        assertTrue(MountTransform.IDENTITY.isIdentity());
        assertArrayEquals(new double[]{1, 2, 3}, out, 0);
    }

    @Test
    public void rotation_isRowMajor() {
        // 90 degrees about z: x -> y
        MountTransform m = new MountTransform(new double[]{
                0, -1, 0,
                1, 0, 0,
                0, 0, 1});
        double[] out = new double[3];
        m.apply(1, 0, 0, out);
        assertFalse(m.isIdentity());
        assertArrayEquals(new double[]{0, 1, 0}, out, 0);
    }
}
