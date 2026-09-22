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

import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.content.ContextCompat;

import com.mendhak.gpslogger.R;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.loggers.fsae.LiveTelemetry;
import com.mendhak.gpslogger.loggers.fsae.MountTransform;
import com.mendhak.gpslogger.loggers.fsae.SampleRing;
import com.mendhak.gpslogger.ui.components.StripChartView;

/**
 * Rolling accel, gyro and GPS speed charts fed from {@link LiveTelemetry}. Redraws at about
 * 30 fps while visible and does nothing otherwise, so it never slows the logger.
 */
public class FsaeChartsFragment extends GenericViewFragment implements Choreographer.FrameCallback {

    private static final float[] WINDOWS_S = {5, 10, 30};
    private static final long FRAME_INTERVAL_NS = 33_000_000L;
    // Copy a little more than the window so the left edge doesn't flicker
    private static final long MARGIN_NS = 250_000_000L;
    private static final double MPS2_PER_G = 9.80665;
    private static final double DEG_PER_RAD = 180 / Math.PI;

    private final LiveTelemetry live = LiveTelemetry.getInstance();
    private final PreferenceHelper preferenceHelper = PreferenceHelper.getInstance();
    private final LiveTelemetry.Stats stats = new LiveTelemetry.Stats();

    private final long[] accT = new long[LiveTelemetry.IMU_CAPACITY];
    private final long[] gyrT = new long[LiveTelemetry.IMU_CAPACITY];
    private final long[] gpsT = new long[LiveTelemetry.GPS_CAPACITY];
    private final double[][] imuRaw = new double[3][LiveTelemetry.IMU_CAPACITY];
    private final double[][] gpsRaw = new double[5][LiveTelemetry.GPS_CAPACITY];
    private final float[][] accOut = new float[3][LiveTelemetry.IMU_CAPACITY];
    private final float[][] gyrOut = new float[3][LiveTelemetry.IMU_CAPACITY];
    private final float[][] speedOut = new float[1][LiveTelemetry.GPS_CAPACITY];
    private final double[] rotated = new double[3];

    private StripChartView accChart, gyrChart, speedChart;
    private TextView notice;
    private int windowIndex = 1;
    private boolean visible;
    private long lastFrameNs;
    private Boolean titlesInCarFrame;
    private int shownNotice = -1;

    public static FsaeChartsFragment newInstance() {
        return new FsaeChartsFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_fsae_charts, container, false);
        notice = root.findViewById(R.id.fsae_charts_notice);
        accChart = root.findViewById(R.id.fsae_chart_accel);
        gyrChart = root.findViewById(R.id.fsae_chart_gyro);
        speedChart = root.findViewById(R.id.fsae_chart_speed);

        int[] xyz = {
                ContextCompat.getColor(requireContext(), R.color.fsae_series_x),
                ContextCompat.getColor(requireContext(), R.color.fsae_series_y),
                ContextCompat.getColor(requireContext(), R.color.fsae_series_z)};
        String[] names = {"x", "y", "z"};

        accChart.setSeries(names, xyz);
        accChart.setMinSpan(0.2f);
        accChart.setMaxGapSeconds(0.5f);
        accChart.setValueDecimals(2);

        gyrChart.setSeries(names, xyz);
        gyrChart.setMinSpan(10f);
        gyrChart.setMaxGapSeconds(0.5f);
        gyrChart.setValueDecimals(1);

        speedChart.setTitle(getString(R.string.fsae_chart_speed));
        speedChart.setSeries(new String[]{"v"}, new int[]{ContextCompat.getColor(requireContext(), R.color.fsae_series_speed)});
        speedChart.setMinSpan(20f);
        speedChart.setIncludeZero(true);
        // GPS runs at 1-10 Hz; only break the line on a real dropout
        speedChart.setMaxGapSeconds(2.5f);
        speedChart.setValueDecimals(1);

        applyWindow();
        root.setOnClickListener(v -> {
            windowIndex = (windowIndex + 1) % WINDOWS_S.length;
            applyWindow();
            Toast.makeText(requireContext(), (int) WINDOWS_S[windowIndex] + " s", Toast.LENGTH_SHORT).show();
        });
        Toast.makeText(requireContext(), R.string.fsae_charts_hint, Toast.LENGTH_SHORT).show();
        fitToScreen(root);
        return root;
    }

    /**
     * Keeps the last chart clear of the activity's bottom toolbar, and in landscape makes each
     * chart a full screen tall (scroll between them) instead of squashing all three.
     */
    private void fitToScreen(View root) {
        View charts = root.findViewById(R.id.fsae_charts_root);
        View toolbar = requireActivity().findViewById(R.id.toolbarBottom);
        boolean landscape = getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        root.post(() -> {
            int toolbarH = toolbar != null && toolbar.getVisibility() == View.VISIBLE ? toolbar.getHeight() : 0;
            charts.setPadding(charts.getPaddingLeft(), charts.getPaddingTop(), charts.getPaddingRight(),
                    charts.getPaddingBottom() + toolbarH);
            if (landscape) {
                int minH = (int) (160 * getResources().getDisplayMetrics().density);
                int h = Math.max(minH, root.getHeight() - toolbarH - charts.getPaddingTop());
                for (View chart : new View[]{accChart, gyrChart, speedChart}) {
                    android.widget.LinearLayout.LayoutParams lp =
                            (android.widget.LinearLayout.LayoutParams) chart.getLayoutParams();
                    lp.height = h;
                    lp.weight = 0;
                    chart.setLayoutParams(lp);
                }
            }
        });
    }

    private void applyWindow() {
        float s = WINDOWS_S[windowIndex];
        accChart.setWindowSeconds(s);
        gyrChart.setWindowSeconds(s);
        speedChart.setWindowSeconds(s);
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
        long now = SystemClock.elapsedRealtimeNanos();
        long from = now - (long) (WINDOWS_S[windowIndex] * 1e9) - MARGIN_NS;
        MountTransform mount = live.getMountTransform();

        updateTitles(!mount.isIdentity());
        updateNotice();

        int na = copyImu(live.acc, from, accT, accOut, mount, 1 / MPS2_PER_G);
        accChart.setData(na, accT, accOut, now);

        int ng = copyImu(live.gyr, from, gyrT, gyrOut, mount, DEG_PER_RAD);
        gyrChart.setData(ng, gyrT, gyrOut, now);

        int ns = live.gps.copySince(from, gpsT, gpsRaw);
        double[] speed = gpsRaw[LiveTelemetry.GPS_SPEED_MPS];
        for (int i = 0; i < ns; i++) {
            speedOut[0][i] = (float) (speed[i] * 3.6);
        }
        speedChart.setData(ns, gpsT, speedOut, now);
    }

    private int copyImu(SampleRing ring, long from, long[] t, float[][] out, MountTransform mount, double scale) {
        int n = ring.copySince(from, t, imuRaw);
        double[] x = imuRaw[LiveTelemetry.X], y = imuRaw[LiveTelemetry.Y], z = imuRaw[LiveTelemetry.Z];
        for (int i = 0; i < n; i++) {
            mount.apply(x[i], y[i], z[i], rotated);
            out[0][i] = (float) (rotated[0] * scale);
            out[1][i] = (float) (rotated[1] * scale);
            out[2][i] = (float) (rotated[2] * scale);
        }
        return n;
    }

    private void updateTitles(boolean carFrame) {
        if (titlesInCarFrame != null && titlesInCarFrame == carFrame) {
            return;
        }
        titlesInCarFrame = carFrame;
        String axes = getString(carFrame ? R.string.fsae_axes_car : R.string.fsae_axes_phone);
        accChart.setTitle(getString(R.string.fsae_chart_accel) + ", " + axes);
        gyrChart.setTitle(getString(R.string.fsae_chart_gyro) + ", " + axes);
    }

    private void updateNotice() {
        live.fillStats(stats);
        int text = 0;
        if (!preferenceHelper.shouldLogFsaeStreams()) {
            text = R.string.fsae_streams_off;
        } else if (!stats.running) {
            text = R.string.fsae_not_logging;
        }
        if (text == shownNotice) {
            // setText relayouts even when nothing changed
            return;
        }
        shownNotice = text;
        if (text == 0) {
            notice.setVisibility(View.GONE);
        } else {
            notice.setText(text);
            notice.setVisibility(View.VISIBLE);
        }
    }
}
