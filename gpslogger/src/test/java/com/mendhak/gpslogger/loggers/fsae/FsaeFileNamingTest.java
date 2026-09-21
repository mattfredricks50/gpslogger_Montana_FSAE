package com.mendhak.gpslogger.loggers.fsae;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.net.Uri;

import com.mendhak.gpslogger.loggers.Files;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

/**
 * FileSenderFactory.autoSendFiles picks up every file whose Files.getBaseName equals the chunk
 * name, so each FSAE stream must reduce to the same base name as the GPSLogger files.
 */
@RunWith(MockitoJUnitRunner.class)
public class FsaeFileNamingTest {

    private static String baseNameOf(String fileName) {
        Uri uri = mock(Uri.class);
        when(uri.toString()).thenReturn("file:///storage/emulated/0/GPSLogger/" + fileName);
        when(uri.getLastPathSegment()).thenReturn(fileName);
        return Files.getBaseName(uri);
    }

    @Test
    public void StreamFiles_ShareChunkBaseName() {
        for (String ext : new String[]{"gps", "acc", "gyr", "meta", "ecu", "gpx", "csv"}) {
            assertThat(ext, baseNameOf("20260921143012." + ext), is("20260921143012"));
        }
    }

    @Test
    public void StreamFiles_PlainCsvMimeType_NotConvertedToSheets() {
        assertThat(Files.getMimeTypeFromFileName("20260921143012.gps"), is("text/csv"));
        assertThat(Files.getMimeTypeFromFileName("20260921143012.acc"), is("text/csv"));
        assertThat(Files.getMimeTypeFromFileName("20260921143012.gyr"), is("text/csv"));
        assertThat(Files.getMimeTypeFromFileName("20260921143012.ecu"), is("text/csv"));
        assertThat(Files.getMimeTypeFromFileName("20260921143012.meta"), is("application/json"));
    }
}
