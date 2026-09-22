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

import java.io.File;
import java.io.IOException;

import okhttp3.MediaType;
import okhttp3.RequestBody;
import okio.Buffer;
import okio.BufferedSink;
import okio.Okio;
import okio.Source;

/**
 * Streams a file as a request body, reporting bytes sent at most every 250 ms (and at the end).
 */
class ProgressFileBody extends RequestBody {

    interface Listener {
        void onProgress(long sent, long total);
    }

    private static final long SEGMENT = 16 * 1024;
    private static final long REPORT_INTERVAL_MS = 250;

    private final MediaType contentType;
    private final File file;
    private final Listener listener;

    ProgressFileBody(MediaType contentType, File file, Listener listener) {
        this.contentType = contentType;
        this.file = file;
        this.listener = listener;
    }

    @Override
    public MediaType contentType() {
        return contentType;
    }

    @Override
    public long contentLength() {
        return file.length();
    }

    @Override
    public void writeTo(BufferedSink sink) throws IOException {
        long total = contentLength();
        long sent = 0;
        long lastReport = 0;
        try (Source source = Okio.source(file)) {
            Buffer buffer = new Buffer();
            long read;
            while ((read = source.read(buffer, SEGMENT)) != -1) {
                sink.write(buffer, read);
                sent += read;
                long now = System.currentTimeMillis();
                if (now - lastReport >= REPORT_INTERVAL_MS) {
                    lastReport = now;
                    listener.onProgress(sent, total);
                }
            }
        }
        listener.onProgress(sent, total);
    }
}
