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

package com.mendhak.gpslogger.common;

import android.content.SharedPreferences;

import com.mendhak.gpslogger.common.slf4j.Logs;

import org.slf4j.Logger;

/**
 * Sets up the app as an FSAE car logger: GPS + IMU + AirBear streams in 7 minute chunks,
 * GPS every fix, each finished chunk zipped to Google Drive.
 * <p>
 * Applied once per install (and once more whenever VERSION is bumped), then left alone so
 * settings changed in the app stick.
 */
public class FsaeDefaults {

    private static final Logger LOG = Logs.of(FsaeDefaults.class);

    private static final String APPLIED_KEY = "fsae_defaults_applied_version";
    private static final int VERSION = 1;

    public static void applyOnce(SharedPreferences prefs) {
        if (prefs.getInt(APPLIED_KEY, 0) >= VERSION) {
            return;
        }
        LOG.info("Applying FSAE default settings v" + VERSION);

        prefs.edit()
                // FSAE streams
                .putBoolean(PreferenceNames.LOG_FSAE_STREAMS, true)
                .putString(PreferenceNames.LOG_TO_IMU_RATE_HZ, "200")
                .putBoolean(PreferenceNames.LOG_FSAE_ECU, true)
                .putString(PreferenceNames.FSAE_ECU_HOSTS, "speeduino.local,192.168.4.1")
                .putString(PreferenceNames.NEW_FILE_CHUNK_MINUTES, "7")
                // GPS: every fix, satellites only, no filters that drop points on track
                .putString(PreferenceNames.MINIMUM_INTERVAL, "0")
                .putBoolean(PreferenceNames.KEEP_GPS_ON_BETWEEN_FIXES, true)
                .putString(PreferenceNames.MINIMUM_DISTANCE, "0")
                .putBoolean(PreferenceNames.LOG_SATELLITE_LOCATIONS, true)
                .putBoolean(PreferenceNames.LOG_NETWORK_LOCATIONS, false)
                .putBoolean(PreferenceNames.LOG_PASSIVE_LOCATIONS, false)
                .putBoolean(PreferenceNames.ONLY_LOG_IF_SIGNIFICANT_MOTION, false)
                // GPSLogger's own CSV is superseded by the .gps stream
                .putBoolean(PreferenceNames.LOG_TO_CSV, false)
                // Upload each finished chunk, zipped, to Google Drive; also on stop
                .putBoolean(PreferenceNames.AUTOSEND_ENABLED, true)
                .putBoolean(PreferenceNames.AUTOSEND_ON_STOP, true)
                .putBoolean(PreferenceNames.AUTOSEND_ZIP, true)
                .putBoolean(PreferenceNames.AUTOSEND_GOOGLE_DRIVE_ENABLED, true)
                .putInt(APPLIED_KEY, VERSION)
                .apply();
    }
}
