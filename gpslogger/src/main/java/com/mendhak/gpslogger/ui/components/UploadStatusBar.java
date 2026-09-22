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
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.os.Build;
import android.text.format.DateFormat;
import android.text.format.Formatter;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.Observer;
import androidx.work.Data;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import com.mendhak.gpslogger.MainPreferenceActivity;
import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.senders.googledrive.DriveUploadStatus;
import com.mendhak.gpslogger.senders.googledrive.GoogleDriveManager;
import com.mendhak.gpslogger.senders.googledrive.GoogleDriveWorker;
import com.mendhak.gpslogger.senders.googledrive.UploadedLogCleaner;

import java.io.File;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * One-line Google Drive upload status under the toolbar: uploading (with progress), queued or
 * waiting for internet, last upload failed (tap to retry), not signed in (tap to sign in), or
 * all uploaded. Hidden when Drive auto-send is off and nothing is queued.
 */
public class UploadStatusBar extends LinearLayout {

    private static final long REFRESH_MS = 5000;

    private static final int COLOR_OK = 0xFF2E7D32;
    private static final int COLOR_BUSY = 0xFF1565C0;
    private static final int COLOR_WAITING = 0xFFE65100;
    private static final int COLOR_ERROR = 0xFFC62828;
    private static final int COLOR_IDLE = 0xFF455A64;

    private final TextView text;
    private final ProgressBar progress;
    private List<WorkInfo> uploads = Collections.emptyList();

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            render();
            postDelayed(this, REFRESH_MS);
        }
    };

    public UploadStatusBar(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        setOrientation(VERTICAL);
        setVisibility(GONE);

        text = new TextView(context);
        text.setTextColor(0xFFFFFFFF);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        text.setGravity(Gravity.CENTER_VERTICAL);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        int padH = dp(12), padV = dp(6);
        text.setPadding(padH, padV, padH, padV);
        addView(text, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));

        progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(1000);
        progress.setVisibility(GONE);
        addView(progress, new LayoutParams(LayoutParams.MATCH_PARENT, dp(4)));
    }

    /** Start observing Drive uploads for as long as the owner is alive. */
    public void bind(LifecycleOwner owner) {
        WorkManager.getInstance(getContext())
                .getWorkInfosByTagLiveData(GoogleDriveWorker.class.getName())
                .observe(owner, new Observer<List<WorkInfo>>() {
                    @Override
                    public void onChanged(List<WorkInfo> workInfos) {
                        uploads = workInfos == null ? Collections.<WorkInfo>emptyList() : workInfos;
                        render();
                    }
                });
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        post(refresh);
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(refresh);
        super.onDetachedFromWindow();
    }

    private void render() {
        PreferenceHelper ph = PreferenceHelper.getInstance();
        WorkInfo running = null;
        int queued = 0;
        for (WorkInfo w : uploads) {
            if (w.getState() == WorkInfo.State.RUNNING) {
                running = w;
            } else if (w.getState() == WorkInfo.State.ENQUEUED || w.getState() == WorkInfo.State.BLOCKED) {
                queued++;
            }
        }

        boolean driveOn = ph.isAutoSendEnabled() && ph.isGoogleDriveAutoSendEnabled();
        if (!driveOn && running == null && queued == 0) {
            setVisibility(GONE);
            return;
        }
        setVisibility(VISIBLE);
        setOnClickListener(null);
        progress.setVisibility(GONE);

        if (!GoogleDriveManager.getAuthState().isAuthorized()) {
            show(COLOR_ERROR, "Google Drive: not signed in. Tap to sign in");
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    Intent i = new Intent(getContext(), MainPreferenceActivity.class);
                    i.putExtra("preference_fragment", MainPreferenceActivity.PREFERENCE_FRAGMENTS.GOOGLEDRIVE);
                    getContext().startActivity(i);
                }
            });
            return;
        }

        if (running != null) {
            Data p = running.getProgress();
            String file = p.getString(DriveUploadStatus.PROGRESS_FILE);
            long sent = p.getLong(DriveUploadStatus.PROGRESS_SENT, 0);
            long total = p.getLong(DriveUploadStatus.PROGRESS_TOTAL, 0);
            StringBuilder sb = new StringBuilder("Uploading ");
            sb.append(file != null ? file : "log");
            if (total > 0) {
                sb.append("  ").append(sent * 100 / total).append("%  (")
                        .append(Formatter.formatShortFileSize(getContext(), sent)).append(" / ")
                        .append(Formatter.formatShortFileSize(getContext(), total)).append(')');
                progress.setIndeterminate(false);
                progress.setProgress((int) (sent * 1000 / total));
            } else {
                progress.setIndeterminate(true);
            }
            if (queued > 0) {
                sb.append("  +").append(queued).append(" queued");
            }
            progress.setVisibility(VISIBLE);
            show(COLOR_BUSY, sb.toString());
            return;
        }

        if (queued > 0) {
            String n = queued == 1 ? "1 upload" : queued + " uploads";
            if (isOnline()) {
                show(COLOR_BUSY, "Google Drive: " + n + " queued");
            } else {
                show(COLOR_WAITING, "Google Drive: " + n + " waiting for internet");
            }
            return;
        }

        final DriveUploadStatus st = DriveUploadStatus.read(getContext());
        if (st.lastFailed()) {
            show(COLOR_ERROR, "Upload failed: " + st.lastErrorFile + " (" + st.lastErrorMessage + "). Tap to retry");
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (st.lastErrorPath != null && new File(st.lastErrorPath).exists()) {
                        new GoogleDriveManager(PreferenceHelper.getInstance()).uploadFile(new File(st.lastErrorPath));
                    }
                }
            });
            return;
        }

        if (st.lastOkMs > 0) {
            show(COLOR_OK, "All uploaded, last at " + DateFormat.getTimeFormat(getContext()).format(new Date(st.lastOkMs))
                    + ". " + Formatter.formatShortFileSize(getContext(), UploadedLogCleaner.logFolderBytes())
                    + " on phone, tap to free up");
            setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    UploadedLogCleaner.confirmAndDelete(getContext());
                }
            });
            return;
        }

        show(COLOR_IDLE, "Google Drive: nothing uploaded yet");
    }

    private void show(int color, String message) {
        setBackgroundColor(color);
        text.setText(message);
    }

    private boolean isOnline() {
        ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) {
            return false;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network n = cm.getActiveNetwork();
            NetworkCapabilities caps = n == null ? null : cm.getNetworkCapabilities(n);
            return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        }
        NetworkInfo info = cm.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
