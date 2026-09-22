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
import android.text.format.Formatter;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.mendhak.gpslogger.common.PreferenceHelper;
import com.mendhak.gpslogger.common.Session;
import com.mendhak.gpslogger.common.Strings;
import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Frees phone storage by deleting log chunks that Google Drive has confirmed it received.
 * A chunk counts as uploaded when its .zip was uploaded, or (zip off) every one of its files was.
 * The chunk being written right now and anything not confirmed uploaded are always kept.
 */
public final class UploadedLogCleaner {

    private static final Logger LOG = Logs.of(UploadedLogCleaner.class);

    public static final class Plan {
        public final List<File> files = new ArrayList<>();
        public final Set<String> chunks = new HashSet<>();
        public long bytes;
        public int chunksKept;
        public long bytesKept;
    }

    private UploadedLogCleaner() {
    }

    public static Plan plan(Context context) {
        Plan plan = new Plan();
        File folder = new File(PreferenceHelper.getInstance().getGpsLoggerFolder());
        File[] all = folder.listFiles();
        if (all == null) {
            return plan;
        }
        Set<String> uploaded = DriveUploadStatus.uploadedFiles(context);
        String current = Session.getInstance().isStarted() ? Strings.getFormattedFileName() : null;

        Map<String, List<File>> byChunk = new TreeMap<>();
        for (File f : all) {
            String name = f.getName();
            int dot = name.lastIndexOf('.');
            if (!f.isFile() || dot <= 0) {
                continue;
            }
            String base = name.substring(0, dot);
            List<File> list = byChunk.get(base);
            if (list == null) {
                list = new ArrayList<>();
                byChunk.put(base, list);
            }
            list.add(f);
        }

        for (Map.Entry<String, List<File>> e : byChunk.entrySet()) {
            String base = e.getKey();
            List<File> files = e.getValue();
            boolean isChunk = false;
            boolean allUploaded = true;
            for (File f : files) {
                String name = f.getName();
                if (uploaded.contains(name)) {
                    isChunk = true;
                } else if (!name.endsWith(".zip")) {
                    allUploaded = false;
                }
            }
            if (!isChunk) {
                continue; // never uploaded, or not a log chunk at all
            }
            long size = 0;
            for (File f : files) {
                size += f.length();
            }
            boolean safe = !base.equals(current) && (uploaded.contains(base + ".zip") || allUploaded);
            if (safe) {
                plan.files.addAll(files);
                plan.chunks.add(base);
                plan.bytes += size;
            } else {
                plan.chunksKept++;
                plan.bytesKept += size;
            }
        }
        return plan;
    }

    /** Deletes the planned files and forgets their upload records. Returns bytes freed. */
    public static long delete(Context context, Plan plan) {
        long freed = 0;
        Set<String> forget = new HashSet<>();
        for (File f : plan.files) {
            long len = f.length();
            if (f.delete()) {
                freed += len;
                forget.add(f.getName());
            } else {
                LOG.warn("Could not delete " + f.getAbsolutePath());
            }
        }
        DriveUploadStatus.forgetUploaded(context, forget);
        LOG.info("Freed " + freed + " bytes from " + plan.chunks.size() + " uploaded chunk(s)");
        return freed;
    }

    /** Shows what would be deleted and deletes it on confirmation. */
    public static void confirmAndDelete(final Context context) {
        final Plan plan = plan(context);
        if (plan.chunks.isEmpty()) {
            Toast.makeText(context, "Nothing to clear: no log chunks on the phone have been confirmed uploaded to Drive yet.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        String msg = "Delete " + plan.chunks.size() + " log chunk(s) already uploaded to Google Drive, freeing "
                + Formatter.formatShortFileSize(context, plan.bytes) + "?";
        if (plan.chunksKept > 0) {
            msg += "\n\nKeeping " + plan.chunksKept + " chunk(s) ("
                    + Formatter.formatShortFileSize(context, plan.bytesKept)
                    + ") that are still logging or not fully uploaded.";
        }
        new AlertDialog.Builder(context)
                .setTitle("Free up phone storage")
                .setMessage(msg)
                .setPositiveButton("Delete", (d, w) -> {
                    long freed = delete(context, plan);
                    Toast.makeText(context, "Freed " + Formatter.formatShortFileSize(context, freed),
                            Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Total size of everything in the log folder, for status display. */
    public static long logFolderBytes() {
        File[] all = new File(PreferenceHelper.getInstance().getGpsLoggerFolder()).listFiles();
        long total = 0;
        if (all != null) {
            for (File f : all) {
                if (f.isFile()) {
                    total += f.length();
                }
            }
        }
        return total;
    }
}
