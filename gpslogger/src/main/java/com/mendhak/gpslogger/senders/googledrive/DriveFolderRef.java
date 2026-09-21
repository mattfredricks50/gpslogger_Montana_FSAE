package com.mendhak.gpslogger.senders.googledrive;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Google Drive folder setting is either a path the app creates (eg. aaa/bbb) or a reference to an
 * existing folder, such as one in a Shared Drive: a folder link (https://drive.google.com/drive/folders/ID)
 * or "id:ID".
 */
final class DriveFolderRef {

    private static final Pattern FOLDER_LINK = Pattern.compile("drive\\.google\\.com/.*folders/([A-Za-z0-9_-]+)");
    private static final Pattern FOLDER_ID = Pattern.compile("^id:\\s*([A-Za-z0-9_-]+)\\s*$");

    private DriveFolderRef() {
    }

    /**
     * @return the folder ID if the setting points at an existing folder, or null if it is a path.
     */
    static String parseFolderId(String setting) {
        if (setting == null) {
            return null;
        }
        Matcher link = FOLDER_LINK.matcher(setting.trim());
        if (link.find()) {
            return link.group(1);
        }
        Matcher id = FOLDER_ID.matcher(setting.trim());
        if (id.find()) {
            return id.group(1);
        }
        return null;
    }
}
