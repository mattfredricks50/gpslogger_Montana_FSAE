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

package com.mendhak.gpslogger.loggers.fsae;

import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * One buffered, always-open CSV file with a fixed header, for high-rate streams.
 * Plain CSV as PlotJuggler wants it: one header row, UTF-8 without BOM, \n line endings.
 * Thread-safe; rows may come from the sensor thread while the service rotates the file.
 */
class CsvStreamWriter {

    private static final Logger LOG = Logs.of(CsvStreamWriter.class);

    private final String header;
    private BufferedWriter writer;
    private File file;
    private long rows;
    private long firstNs = Long.MIN_VALUE;
    private long lastNs = Long.MIN_VALUE;

    CsvStreamWriter(String header) {
        this.header = header;
    }

    synchronized void open(File newFile) {
        close();
        boolean isNew = !newFile.exists() || newFile.length() == 0;
        try {
            writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(newFile, true), StandardCharsets.UTF_8), 64 * 1024);
            file = newFile;
            if (isNew) {
                writer.append(header).append('\n');
            }
        } catch (IOException e) {
            LOG.error("Could not open " + newFile.getAbsolutePath(), e);
            writer = null;
            file = null;
        }
        rows = 0;
        firstNs = Long.MIN_VALUE;
        lastNs = Long.MIN_VALUE;
    }

    /**
     * @param ns   the row's elapsedRealtimeNanos, used for per-chunk statistics
     * @param line the complete row including the trailing \n
     */
    synchronized void writeRow(long ns, CharSequence line) {
        if (writer == null) {
            return;
        }
        try {
            writer.append(line);
            if (rows == 0) {
                firstNs = ns;
            }
            lastNs = ns;
            rows++;
        } catch (IOException e) {
            LOG.error("Write failed, closing " + file.getName(), e);
            close();
        }
    }

    synchronized void flush() {
        if (writer == null) {
            return;
        }
        try {
            writer.flush();
        } catch (IOException e) {
            LOG.error("Flush failed", e);
        }
    }

    synchronized void close() {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            LOG.error("Close failed", e);
        }
        writer = null;
        file = null;
    }

    synchronized long getRows() {
        return rows;
    }

    synchronized long getFirstNs() {
        return firstNs;
    }

    synchronized long getLastNs() {
        return lastNs;
    }

    /**
     * Seconds since session start with microsecond resolution, without locale-dependent formatting.
     */
    static void appendTime(StringBuilder sb, long ns, long sessionStartNs) {
        long d = ns - sessionStartNs;
        if (d < 0) {
            sb.append('-');
            d = -d;
        }
        long us = (d + 500) / 1000;
        sb.append(us / 1_000_000L).append('.');
        appendPadded(sb, us % 1_000_000L, 6);
    }

    private static final long[] POW10 = {1L, 10L, 100L, 1_000L, 10_000L, 100_000L, 1_000_000L,
            10_000_000L, 100_000_000L, 1_000_000_000L};

    /**
     * Fixed-point decimal with '.' regardless of locale and no scientific notation.
     * NaN/infinity become an empty field.
     */
    static void appendFixed(StringBuilder sb, double v, int decimals) {
        if (Double.isNaN(v) || Double.isInfinite(v)) {
            return;
        }
        long scale = POW10[decimals];
        long r = Math.round(Math.abs(v) * scale);
        if (v < 0 && r != 0) {
            sb.append('-');
        }
        sb.append(r / scale);
        if (decimals > 0) {
            sb.append('.');
            appendPadded(sb, r % scale, decimals);
        }
    }

    private static void appendPadded(StringBuilder sb, long value, int width) {
        for (int i = width - 1; i > 0 && value < POW10[i]; i--) {
            sb.append('0');
        }
        sb.append(value);
    }
}
