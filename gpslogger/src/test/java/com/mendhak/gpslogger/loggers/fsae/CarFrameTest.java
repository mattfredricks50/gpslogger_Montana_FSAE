package com.mendhak.gpslogger.loggers.fsae;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Same conventions as tools/plotjuggler/car_frame.py (checked there on real phone data). */
public class CarFrameTest {

    private static final double G = CarFrame.G;

    @Test
    public void FlatTopForward_MapsPhoneAxesToCar() {
        // Phone flat, screen up, top toward the nose: +y forward, +x right, +z up
        CarFrame f = CarFrame.fromGravity(new double[]{0, 0, G}, "+y");
        assertEquals(0.5, f.longG(0, 0.5 * G, G), 1e-9);      // accelerating
        assertEquals(-0.8, f.longG(0, -0.8 * G, G), 1e-9);    // braking
        assertEquals(1.2, f.latG(1.2 * G, 0, G), 1e-9);       // right turn
        assertEquals(0.0, f.vertG(0, 0, G), 1e-9);            // gravity removed
        assertEquals(Math.toDegrees(0.5), f.yawDps(0, 0, 0.5), 1e-9); // CCW = left turn +
    }

    @Test
    public void UprightFacingDriver_ForwardIsBackOfPhone() {
        // Phone upright in a dash mount, screen toward the driver: gravity along +y, back (-z) at the nose
        CarFrame f = CarFrame.fromGravity(new double[]{0, G, 0}, "-z");
        assertEquals(1.0, f.longG(0, G, -G), 1e-9);
        // The driver sees the screen normally, so the phone's +x is the driver's (and car's) right
        assertEquals(0.7, f.latG(0.7 * G, G, 0), 1e-9);
    }

    @Test
    public void TiltedMount_ForwardMadeLevel() {
        // Top of phone points forward but tilted 20 degrees nose-up
        double a = Math.toRadians(20);
        double[] gravity = {0, G * Math.sin(a), G * Math.cos(a)};
        CarFrame f = CarFrame.fromGravity(gravity, "+y");
        // Pure level forward acceleration of 0.5 g plus gravity, expressed in phone axes
        double fx = 0, fy = 0.5 * G * Math.cos(a) + gravity[1], fz = -0.5 * G * Math.sin(a) + gravity[2];
        assertEquals(0.5, f.longG(fx, fy, fz), 1e-9);
        assertEquals(0.0, f.vertG(fx, fy, fz), 1e-9);
    }

    @Test
    public void VerticalForwardAxis_Rejected() {
        assertThat(CarFrame.fromGravity(new double[]{0, 0, G}, "+z"), is(nullValue()));
    }

    @Test
    public void CalibrationJson_Parsed() {
        CarFrame f = CarFrame.fromCalibrationJson(
                "{\"gravity_mps2\":[0.09,-0.16,9.73],\"accel_std_mps2\":[0.03,0.01,0.08]}", "+y");
        assertEquals(0.0, f.vertG(0.09, -0.16, 9.73), 1e-9);
        assertThat(CarFrame.fromCalibrationJson("", "+y"), is(nullValue()));
    }
}
