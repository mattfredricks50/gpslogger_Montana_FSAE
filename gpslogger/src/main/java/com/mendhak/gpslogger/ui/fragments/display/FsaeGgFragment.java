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

package com.mendhak.gpslogger.ui.fragments.display;

import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.loggers.fsae.CarFrame;
import com.mendhak.gpslogger.loggers.fsae.GgTracker;
import com.mendhak.gpslogger.loggers.fsae.LiveTelemetry;
import com.mendhak.gpslogger.ui.components.GgPlotView;

import java.util.Locale;

/**
 * Live G-G diagram with the g pulled this session: peak braking, acceleration, left and right
 * cornering and combined. Peaks are tracked by the logger ({@link GgTracker}) whether or not this
 * screen is open; the trail is the last few seconds from the live telemetry buffer.
 */
public class FsaeGgFragment extends GenericViewFragment implements Choreographer.FrameCallback {

    private static final long FRAME_INTERVAL_NS = 33_000_000L;
    private static final long TRAIL_NS = 4_000_000_000L;
    private static final long TRAIL_STEP_NS = 20_000_000L;
    private static final double TAU_S = 0.05;

    private final LiveTelemetry live = LiveTelemetry.getInstance();
    private final GgTracker tracker = GgTracker.getInstance();
    private final GgTracker.Peaks peaks = new GgTracker.Peaks();
    private final long[] accT = new long[LiveTelemetry.IMU_CAPACITY];
    private final double[][] accRaw = new double[3][LiveTelemetry.IMU_CAPACITY];
    private final float[] trailLat = new float[(int) (TRAIL_NS / TRAIL_STEP_NS) + 2];
    private final float[] trailLong = new float[trailLat.length];

    private GgPlotView plot;
    private TextView current, brake, accel, left, right, combined, notice;
    private boolean visible;
    private long lastFrameNs;

    public static FsaeGgFragment newInstance() {
        return new FsaeGgFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (12 * d);
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        notice = text(13, Gravity.CENTER);
        plot = new GgPlotView(requireContext(), null);
        current = text(20, Gravity.CENTER);

        LinearLayout stats = new LinearLayout(requireContext());
        stats.setOrientation(LinearLayout.VERTICAL);
        GridLayout grid = new GridLayout(requireContext());
        grid.setColumnCount(2);
        brake = cell(grid, "Max braking");
        accel = cell(grid, "Max accel");
        left = cell(grid, "Max left");
        right = cell(grid, "Max right");
        combined = cell(grid, "Max combined");
        Button reset = new Button(requireContext());
        reset.setText("Reset peaks");
        reset.setOnClickListener(v -> tracker.reset());

        if (landscape) {
            // Plot on the left sized to the screen height, numbers on the right
            LinearLayout root = new LinearLayout(requireContext());
            root.setOrientation(LinearLayout.HORIZONTAL);
            root.setPadding(pad, pad / 2, pad, pad / 2);
            root.addView(plot, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            stats.addView(notice);
            stats.addView(current);
            stats.addView(grid);
            stats.addView(reset);
            ScrollView side = new ScrollView(requireContext());
            side.addView(stats);
            root.addView(side, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
            clearBottomToolbar(root);
            return root;
        }

        stats.setPadding(pad, pad, pad, pad);
        stats.addView(notice);
        stats.addView(plot, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        stats.addView(current);
        stats.addView(grid);
        stats.addView(reset);
        ScrollView scroll = new ScrollView(requireContext());
        scroll.addView(stats);
        clearBottomToolbar(stats);
        return scroll;
    }

    /** The activity's bottom toolbar overlaps the content area; pad so nothing hides behind it. */
    private void clearBottomToolbar(View v) {
        View toolbar = requireActivity().findViewById(R.id.toolbarBottom);
        v.post(() -> v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(),
                v.getPaddingBottom() + (toolbar != null && toolbar.getVisibility() == View.VISIBLE ? toolbar.getHeight() : 0)));
    }

    private TextView text(int sp, int gravity) {
        TextView t = new TextView(requireContext());
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setGravity(gravity);
        return t;
    }

    private TextView cell(GridLayout grid, String title) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        int p = (int) (6 * getResources().getDisplayMetrics().density);
        box.setPadding(p, p, p, p);
        TextView name = text(12, Gravity.START);
        name.setText(title);
        TextView value = text(24, Gravity.START);
        box.addView(name);
        box.addView(value);
        GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED), GridLayout.spec(GridLayout.UNDEFINED, 1f));
        lp.width = 0;
        grid.addView(box, lp);
        return value;
    }

    @Override
    public void onResume() {
        super.onResume();
        visible = true;
        lastFrameNs = 0;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    public void onPause() {
        visible = false;
        Choreographer.getInstance().removeFrameCallback(this);
        super.onPause();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (!visible) {
            return;
        }
        Choreographer.getInstance().postFrameCallback(this);
        if (frameTimeNanos - lastFrameNs < FRAME_INTERVAL_NS) {
            return;
        }
        lastFrameNs = frameTimeNanos;
        update();
    }

    private void update() {
        tracker.snapshot(peaks);
        CarFrame frame = tracker.getFrame();
        int n = frame == null ? 0 : buildTrail(frame);

        if (!peaks.ready) {
            notice.setText(live.acc.isEmpty()
                    ? "Start logging to see g. Keep the car still for the first 2 s (calibration)."
                    : "Calibrating: keep the car still…");
        } else {
            notice.setText("Forward = phone " + PreferenceHelper.getInstance().getFsaeMountForward()
                    + " axis (Settings → Logging details)");
        }

        plot.setData(trailLat, trailLong, n, peaks.ready, (float) peaks.latG, (float) peaks.longG,
                (float) peaks.maxCombined);
        current.setText(peaks.ready
                ? String.format(Locale.US, "%s %.2f g    %s %.2f g",
                peaks.longG >= 0 ? "Accel" : "Brake", Math.abs(peaks.longG),
                peaks.latG >= 0 ? "Right" : "Left", Math.abs(peaks.latG))
                : "");
        brake.setText(g(peaks.maxBrake));
        accel.setText(g(peaks.maxAccel));
        left.setText(g(peaks.maxLeft));
        right.setText(g(peaks.maxRight));
        combined.setText(g(peaks.maxCombined));
    }

    /** The last few seconds, smoothed like the tracker and thinned to one point per 20 ms. */
    private int buildTrail(CarFrame frame) {
        long from = SystemClock.elapsedRealtimeNanos() - TRAIL_NS;
        int m = live.acc.copySince(from, accT, accRaw);
        double[] x = accRaw[LiveTelemetry.X], y = accRaw[LiveTelemetry.Y], z = accRaw[LiveTelemetry.Z];
        int n = 0;
        double lg = 0, la = 0;
        long prev = 0, nextEmit = Long.MIN_VALUE;
        for (int i = 0; i < m; i++) {
            double l = frame.longG(x[i], y[i], z[i]);
            double t = frame.latG(x[i], y[i], z[i]);
            if (i == 0) {
                lg = l;
                la = t;
            } else {
                double a = 1 - Math.exp(-((accT[i] - prev) / 1e9) / TAU_S);
                lg += a * (l - lg);
                la += a * (t - la);
            }
            prev = accT[i];
            if (accT[i] >= nextEmit && n < trailLat.length) {
                trailLat[n] = (float) la;
                trailLong[n] = (float) lg;
                n++;
                nextEmit = accT[i] + TRAIL_STEP_NS;
            }
        }
        return n;
    }

    private static String g(double v) {
        return String.format(Locale.US, "%.2f g", v);
    }
}
