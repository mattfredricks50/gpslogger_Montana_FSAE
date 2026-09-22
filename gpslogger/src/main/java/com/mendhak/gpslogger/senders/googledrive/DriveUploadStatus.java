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

package com.mendhak.gpslogger.senders.googledrive;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Last Google Drive upload outcome, for the upload status bar. WorkManager says what is queued
 * or running; this remembers how the most recent finished upload went.
 */
public class DriveUploadStatus {

    public static final String PROGRESS_FILE = "file";
    public static final String PROGRESS_SENT = "sent";
    public static final String PROGRESS_TOTAL = "total";

    private static final String OK_MS = "drive_upload_last_ok_ms";
    private static final String OK_FILE = "drive_upload_last_ok_file";
    private static final String ERROR_MS = "drive_upload_last_error_ms";
    private static final String ERROR_FILE = "drive_upload_last_error_file";
    private static final String ERROR_PATH = "drive_upload_last_error_path";
    private static final String ERROR_MESSAGE = "drive_upload_last_error_message";
    private static final String UPLOADED = "drive_uploaded_files";

    public final long lastOkMs;
    public final String lastOkFile;
    public final long lastErrorMs;
    public final String lastErrorFile;
    public final String lastErrorPath;
    public final String lastErrorMessage;

    private DriveUploadStatus(SharedPreferences p) {
        lastOkMs = p.getLong(OK_MS, 0);
        lastOkFile = p.getString(OK_FILE, null);
        lastErrorMs = p.getLong(ERROR_MS, 0);
        lastErrorFile = p.getString(ERROR_FILE, null);
        lastErrorPath = p.getString(ERROR_PATH, null);
        lastErrorMessage = p.getString(ERROR_MESSAGE, null);
    }

    public static DriveUploadStatus read(Context context) {
        return new DriveUploadStatus(PreferenceManager.getDefaultSharedPreferences(context));
    }

    /** The latest finished upload failed, and nothing has succeeded since. */
    public boolean lastFailed() {
        return lastErrorMs > lastOkMs;
    }

    static synchronized void recordSuccess(Context context, String fileName) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> uploaded = new HashSet<>(p.getStringSet(UPLOADED, Collections.<String>emptySet()));
        uploaded.add(fileName);
        p.edit()
                .putLong(OK_MS, System.currentTimeMillis())
                .putString(OK_FILE, fileName)
                .putStringSet(UPLOADED, uploaded)
                .apply();
    }

    /** Names of local files Drive confirmed it received (and that haven't been cleared since). */
    public static Set<String> uploadedFiles(Context context) {
        return new HashSet<>(PreferenceManager.getDefaultSharedPreferences(context)
                .getStringSet(UPLOADED, Collections.<String>emptySet()));
    }

    static synchronized void forgetUploaded(Context context, Set<String> names) {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> uploaded = new HashSet<>(p.getStringSet(UPLOADED, Collections.<String>emptySet()));
        uploaded.removeAll(names);
        p.edit().putStringSet(UPLOADED, uploaded).apply();
    }

    static void recordFailure(Context context, String filePath, String fileName, String message) {
        PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putLong(ERROR_MS, System.currentTimeMillis())
                .putString(ERROR_FILE, fileName)
                .putString(ERROR_PATH, filePath)
                .putString(ERROR_MESSAGE, message == null || message.isEmpty() ? "unknown error" : message)
                .apply();
    }
}
