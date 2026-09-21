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

/**
 * Minimal Server-Sent Events parser: feed it lines, get whole events back.
 * An event's receive time is when its first data line arrived.
 */
class SseParser {

    interface Listener {
        void onEvent(String event, String id, String data, long receivedNs);
    }

    private final Listener listener;
    private String event;
    private String id;
    private StringBuilder data;
    private long receivedNs;

    SseParser(Listener listener) {
        this.listener = listener;
    }

    void reset() {
        event = null;
        id = null;
        data = null;
    }

    void line(String l, long nowNs) {
        if (l.isEmpty()) {
            if (data != null) {
                listener.onEvent(event, id, data.toString(), receivedNs);
            }
            reset();
            return;
        }
        if (l.charAt(0) == ':') {
            return;
        }
        int colon = l.indexOf(':');
        String field = colon < 0 ? l : l.substring(0, colon);
        String value = colon < 0 ? "" : l.substring(colon + 1);
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        switch (field) {
            case "event":
                event = value;
                break;
            case "id":
                id = value;
                break;
            case "data":
                if (data == null) {
                    data = new StringBuilder(value);
                    receivedNs = nowNs;
                } else {
                    data.append('\n').append(value);
                }
                break;
            default:
                break;
        }
    }
}
