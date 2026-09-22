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

/**
 * G-G diagram: lateral g across (right +), longitudinal g up (accelerating +, braking down),
 * rings every 0.5 g, a fading trail and the current point. The scale grows in 0.5 g steps to fit
 * the session's peak so a big hit doesn't leave the plot.
 */
public class GgPlotView extends View {

    private static final int TRAIL_COLOR = 0xFF1E88E5;
    private static final int DOT_COLOR = 0xFFE53935;
    private static final int PEAK_COLOR = 0xFFFB8C00;

    private final Paint grid = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint trail = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint peak = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float[] lat = new float[0];
    private float[] lng = new float[0];
    private int count;
    private float curLat, curLong;
    private boolean hasCurrent;
    private float peakRadius;
    private float rangeG = 1.0f;

    public GgPlotView(Context context, AttributeSet attrs) {
        super(context, attrs);
        TypedArray a = context.obtainStyledAttributes(new int[]{android.R.attr.textColorPrimary});
        int fg = a.getColor(0, Color.GRAY);
        a.recycle();

        float d = getResources().getDisplayMetrics().density;
        grid.setColor(fg);
        grid.setAlpha(70);
        grid.setStyle(Paint.Style.STROKE);
        grid.setStrokeWidth(1 * d);
        label.setColor(fg);
        label.setAlpha(170);
        label.setTextSize(12 * d);
        trail.setColor(TRAIL_COLOR);
        trail.setStrokeWidth(2.5f * d);
        trail.setStrokeCap(Paint.Cap.ROUND);
        dot.setColor(DOT_COLOR);
        peak.setColor(PEAK_COLOR);
        peak.setStyle(Paint.Style.STROKE);
        peak.setStrokeWidth(1.5f * d);
        peak.setPathEffect(new android.graphics.DashPathEffect(new float[]{6 * d, 6 * d}, 0));
    }

    /**
     * @param n          points in lat/lng to draw as the trail, oldest first
     * @param peakG      largest combined g this session, drawn as a dashed ring
     */
    public void setData(float[] latG, float[] longG, int n, boolean current, float currentLat,
                        float currentLong, float peakG) {
        lat = latG;
        lng = longG;
        count = n;
        hasCurrent = current;
        curLat = currentLat;
        curLong = currentLong;
        peakRadius = peakG;
        float need = Math.max(peakG, Math.max(Math.abs(currentLat), Math.abs(currentLong)));
        rangeG = Math.max(1.0f, (float) Math.ceil((need + 0.1f) / 0.5f) * 0.5f);
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int w = MeasureSpec.getSize(widthMeasureSpec);
        int h = MeasureSpec.getSize(heightMeasureSpec);
        int side = MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED ? w : Math.min(w, h);
        setMeasuredDimension(w, side);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float d = getResources().getDisplayMetrics().density;
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 22 * d;
        float perG = r / rangeG;

        canvas.drawLine(cx - r, cy, cx + r, cy, grid);
        canvas.drawLine(cx, cy - r, cx, cy + r, grid);
        for (float g = 0.5f; g <= rangeG + 1e-3f; g += 0.5f) {
            canvas.drawCircle(cx, cy, g * perG, grid);
            canvas.drawText(String.format(java.util.Locale.US, "%.1f", g), cx + g * perG * 0.707f + 2 * d,
                    cy - g * perG * 0.707f - 2 * d, label);
        }
        label.setTextAlign(Paint.Align.CENTER);
        canvas.drawText("ACCEL", cx, cy - r - 6 * d, label);
        canvas.drawText("BRAKE", cx, cy + r + 16 * d, label);
        label.setTextAlign(Paint.Align.LEFT);
        canvas.drawText("L", 2 * d, cy - 4 * d, label);
        label.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText("R", getWidth() - 2 * d, cy - 4 * d, label);
        label.setTextAlign(Paint.Align.LEFT);

        if (peakRadius > 0.05f) {
            canvas.drawCircle(cx, cy, peakRadius * perG, peak);
        }

        for (int i = 1; i < count; i++) {
            trail.setAlpha(30 + 200 * i / count);
            canvas.drawLine(cx + lat[i - 1] * perG, cy - lng[i - 1] * perG,
                    cx + lat[i] * perG, cy - lng[i] * perG, trail);
        }
        if (hasCurrent) {
            canvas.drawCircle(cx + curLat * perG, cy - curLong * perG, 7 * d, dot);
        }
    }
}
