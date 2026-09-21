package com.mendhak.gpslogger.senders.googledrive;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;

import org.junit.Test;

public class DriveFolderRefTest {

    @Test
    public void FolderLink_ReturnsId() {
        assertThat(DriveFolderRef.parseFolderId("https://drive.google.com/drive/folders/0AbCdEf-123_xyz"),
                is("0AbCdEf-123_xyz"));
    }

    @Test
    public void FolderLink_WithAccountAndQuery_ReturnsId() {
        assertThat(DriveFolderRef.parseFolderId(" https://drive.google.com/drive/u/1/folders/1xYz_AbC?usp=sharing "),
                is("1xYz_AbC"));
    }

    @Test
    public void IdPrefix_ReturnsId() {
        assertThat(DriveFolderRef.parseFolderId("id: 1xYz_AbC"), is("1xYz_AbC"));
    }

    @Test
    public void PlainPath_ReturnsNull() {
        assertThat(DriveFolderRef.parseFolderId("GPSLogger for Android"), is(nullValue()));
        assertThat(DriveFolderRef.parseFolderId("FSAE/folders/logs"), is(nullValue()));
        assertThat(DriveFolderRef.parseFolderId(null), is(nullValue()));
    }
}
