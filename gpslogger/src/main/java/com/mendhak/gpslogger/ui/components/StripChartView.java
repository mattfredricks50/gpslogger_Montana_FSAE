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

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import java.util.Locale;

/**
 * Scrolling time-series chart for high-rate streams. The newest data sits at the right edge;
 * the x axis is seconds before now. Draws with preallocated buffers only, so it can redraw every
 * frame. Data arrays passed to {@link #setData} belong to the caller and are read in onDraw.
 */
public class StripChartView extends View {

    private final ColumnDecimator decimator = new ColumnDecimator();
    private final Paint gridPaint = new Paint();
    private final Paint axisTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private Paint[] seriesPaints = new Paint[0];
    private String[] seriesNames = new String[0];
    private float[] lines = new float[0];

    private String title = "";
    private long windowNs = 10_000_000_000L;
    private long maxGapNs = 500_000_000L;
    private float minSpan = 1f;
    private boolean includeZero;
    private int valueDecimals = 2;

    private int n;
    private long[] times;
    private float[][] values;
    private long nowNs;

    private boolean rangeSet;
    private float yLo, yHi;

    private final float dp;

    public StripChartView(Context context) {
        this(context, null);
    }

    public StripChartView(Context context, AttributeSet attrs) {
        super(context, attrs);
        dp = getResources().getDisplayMetrics().density;

        int textColor = themeColor(context, android.R.attr.textColorSecondary, Color.GRAY);
        int titleColor = themeColor(context, android.R.attr.textColorPrimary, Color.BLACK);

        gridPaint.setColor(textColor);
        gridPaint.setAlpha(60);
        gridPaint.setStrokeWidth(1);

        axisTextPaint.setColor(textColor);
        axisTextPaint.setTextSize(10 * getResources().getDisplayMetrics().scaledDensity);

        titlePaint.setColor(titleColor);
        titlePaint.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
        titlePaint.setFakeBoldText(true);

        legendPaint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
        legendPaint.setTextAlign(Paint.Align.RIGHT);
    }

    private static int themeColor(Context context, int attr, int fallback) {
        TypedArray a = context.obtainStyledAttributes(new int[]{attr});
        try {
            return a.getColor(0, fallback);
        } finally {
            a.recycle();
        }
    }

    public void setTitle(String title) {
        this.title = title;
        invalidate();
    }

    public void setSeries(String[] names, int[] colors) {
        seriesNames = names.clone();
        seriesPaints = new Paint[names.length];
        for (int i = 0; i < names.length; i++) {
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(colors[i]);
            p.setStrokeWidth(1.5f * dp);
            p.setStrokeCap(Paint.Cap.ROUND);
            seriesPaints[i] = p;
        }
        invalidate();
    }

    public void setWindowSeconds(float seconds) {
        windowNs = (long) (seconds * 1e9);
        invalidate();
    }

    /** Samples further apart than this are drawn with a break between them. */
    public void setMaxGapSeconds(float seconds) {
        maxGapNs = (long) (seconds * 1e9);
    }

    /** Smallest y span the auto range will zoom to, so sensor noise doesn't fill the chart. */
    public void setMinSpan(float span) {
        minSpan = span;
    }

    public void setIncludeZero(boolean includeZero) {
        this.includeZero = includeZero;
    }

    public void setValueDecimals(int decimals) {
        valueDecimals = decimals;
    }

    /**
     * @param n      samples in use
     * @param times  elapsedRealtimeNanos per sample, increasing
     * @param values one array per series
     * @param nowNs  the right edge of the chart
     */
    public void setData(int n, long[] times, float[][] values, long nowNs) {
        this.n = n;
        this.times = times;
        this.values = values;
        this.nowNs = nowNs;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        float titleH = titlePaint.getTextSize() * 1.4f;
        float axisH = axisTextPaint.getTextSize() * 1.6f;
        float left = axisTextPaint.measureText("-0000.0") + 4 * dp;
        float right = getWidth() - 4 * dp;
        float top = titleH + 2 * dp;
        float bottom = getHeight() - axisH;

        canvas.drawText(title, 4 * dp, titlePaint.getTextSize(), titlePaint);
        drawLegend(canvas, right, titlePaint.getTextSize());

        if (right - left < 10 || bottom - top < 10) {
            return;
        }

        long startNs = nowNs - windowNs;
        updateRange(startNs);

        drawYGrid(canvas, left, right, top, bottom);
        drawXGrid(canvas, left, right, top, bottom);

        if (values == null) {
            return;
        }
        int cols = Math.max(1, (int) ((right - left) / (1 * dp)));
        float colW = (right - left) / cols;
        float ySpan = yHi - yLo;
        for (int s = 0; s < seriesPaints.length && s < values.length; s++) {
            decimator.run(n, times, values[s], startNs, nowNs, cols);
            int k = 0;
            ensureLines(cols * 8);
            int prev = -1;
            for (int c = 0; c < cols; c++) {
                if (!decimator.has[c]) {
                    continue;
                }
                float x = left + (c + 0.5f) * colW;
                if (prev >= 0 && decimator.firstT[c] - decimator.lastT[prev] <= maxGapNs) {
                    lines[k++] = left + (prev + 0.5f) * colW;
                    lines[k++] = toY(decimator.last[prev], top, bottom, ySpan);
                    lines[k++] = x;
                    lines[k++] = toY(decimator.first[c], top, bottom, ySpan);
                }
                if (decimator.max[c] > decimator.min[c]) {
                    lines[k++] = x;
                    lines[k++] = toY(decimator.min[c], top, bottom, ySpan);
                    lines[k++] = x;
                    lines[k++] = toY(decimator.max[c], top, bottom, ySpan);
                }
                prev = c;
            }
            canvas.save();
            canvas.clipRect(left, top, right, bottom);
            canvas.drawLines(lines, 0, k, seriesPaints[s]);
            if (k == 0 && prev >= 0) {
                // A lone sample has no segment to draw
                canvas.drawPoint(left + (prev + 0.5f) * colW, toY(decimator.last[prev], top, bottom, ySpan), seriesPaints[s]);
            }
            canvas.restore();
        }
    }

    private float toY(float v, float top, float bottom, float ySpan) {
        return bottom - (v - yLo) / ySpan * (bottom - top);
    }

    private void ensureLines(int size) {
        if (lines.length < size) {
            lines = new float[size];
        }
    }

    private void drawLegend(Canvas canvas, float right, float baseline) {
        float x = right;
        for (int s = seriesPaints.length - 1; s >= 0; s--) {
            float v = latestValue(s);
            String label = seriesNames[s] + " " + (Float.isNaN(v) ? "--" : String.format(Locale.US, "%." + valueDecimals + "f", v));
            legendPaint.setColor(seriesPaints[s].getColor());
            canvas.drawText(label, x, baseline, legendPaint);
            x -= legendPaint.measureText(label) + 10 * dp;
        }
    }

    private float latestValue(int s) {
        if (values == null || s >= values.length) {
            return Float.NaN;
        }
        for (int i = n - 1; i >= 0; i--) {
            if (!Float.isNaN(values[s][i])) {
                return values[s][i];
            }
        }
        return Float.NaN;
    }

    /** Grows at once to fit new data, shrinks slowly so the scale doesn't jump around. */
    private void updateRange(long startNs) {
        float lo = Float.POSITIVE_INFINITY, hi = Float.NEGATIVE_INFINITY;
        if (values != null) {
            for (int s = 0; s < values.length; s++) {
                float[] v = values[s];
                for (int i = 0; i < n; i++) {
                    if (times[i] < startNs || Float.isNaN(v[i])) continue;
                    if (v[i] < lo) lo = v[i];
                    if (v[i] > hi) hi = v[i];
                }
            }
        }
        if (lo > hi) {
            if (!rangeSet) {
                lo = includeZero ? 0 : -minSpan / 2;
                hi = lo + minSpan;
            } else {
                return;
            }
        }
        boolean nonNegative = lo >= 0;
        if (includeZero) {
            lo = Math.min(lo, 0);
            hi = Math.max(hi, 0);
        }
        float pad = (hi - lo) * 0.08f;
        lo -= pad;
        hi += pad;
        if (hi - lo < minSpan) {
            float mid = (hi + lo) / 2;
            lo = mid - minSpan / 2;
            hi = mid + minSpan / 2;
        }
        if (includeZero && nonNegative && lo < 0) {
            // Keep zero on the bottom edge for quantities like speed
            hi -= lo;
            lo = 0;
        }

        if (!rangeSet) {
            yLo = lo;
            yHi = hi;
            rangeSet = true;
            return;
        }
        yLo = lo < yLo ? lo : yLo + (lo - yLo) * 0.05f;
        yHi = hi > yHi ? hi : yHi + (hi - yHi) * 0.05f;
    }

    private void drawYGrid(Canvas canvas, float left, float right, float top, float bottom) {
        float ySpan = yHi - yLo;
        double step = niceStep(ySpan, 4);
        double firstTick = Math.ceil(yLo / step) * step;
        int decimals = Math.max(0, (int) -Math.floor(Math.log10(step)));
        axisTextPaint.setTextAlign(Paint.Align.RIGHT);
        for (double v = firstTick; v <= yHi; v += step) {
            float y = toY((float) v, top, bottom, ySpan);
            canvas.drawLine(left, y, right, y, gridPaint);
            canvas.drawText(String.format(Locale.US, "%." + decimals + "f", v + 0.0), left - 3 * dp,
                    y + axisTextPaint.getTextSize() * 0.35f, axisTextPaint);
        }
    }

    private void drawXGrid(Canvas canvas, float left, float right, float top, float bottom) {
        float windowS = windowNs / 1e9f;
        double step = niceStep(windowS, 5);
        axisTextPaint.setTextAlign(Paint.Align.CENTER);
        float textY = bottom + axisTextPaint.getTextSize() * 1.2f;
        for (double s = 0; s <= windowS + 1e-6; s += step) {
            float x = right - (float) (s / windowS) * (right - left);
            canvas.drawLine(x, top, x, bottom, gridPaint);
            String label = s == 0 ? "0 s" : String.format(Locale.US, "-%s", trim(s));
            canvas.drawText(label, x, textY, axisTextPaint);
        }
    }

    private static String trim(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.format(Locale.US, "%.1f", v);
    }

    /** 1, 2 or 5 times a power of ten, giving about {@code targetTicks} ticks across span. */
    static double niceStep(double span, int targetTicks) {
        if (span <= 0 || Double.isNaN(span) || Double.isInfinite(span)) {
            return 1;
        }
        double raw = span / targetTicks;
        double mag = Math.pow(10, Math.floor(Math.log10(raw)));
        double norm = raw / mag;
        double nice = norm < 1.5 ? 1 : norm < 3.5 ? 2 : norm < 7.5 ? 5 : 10;
        return nice * mag;
    }
}
