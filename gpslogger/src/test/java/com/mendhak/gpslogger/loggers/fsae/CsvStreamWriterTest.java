package com.mendhak.gpslogger.loggers.fsae;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.CoreMatchers.is;

import org.junit.Test;

import java.util.Locale;

public class CsvStreamWriterTest {

    private static String time(long ns, long start) {
        StringBuilder sb = new StringBuilder();
        CsvStreamWriter.appendTime(sb, ns, start);
        return sb.toString();
    }

    private static String fixed(double v, int decimals) {
        StringBuilder sb = new StringBuilder();
        CsvStreamWriter.appendFixed(sb, v, decimals);
        return sb.toString();
    }

    @Test
    public void AppendTime_MicrosecondResolutionFromSessionStart() {
        assertThat(time(1_000_000_000L, 1_000_000_000L), is("0.000000"));
        assertThat(time(1_005_000_000L, 1_000_000_000L), is("0.005000"));
        assertThat(time(1_000_000_000L + 123_456_789_012L, 1_000_000_000L), is("123.456789"));
        assertThat(time(1_000_000_000L + 1_500L, 1_000_000_000L), is("0.000002"));
    }

    @Test
    public void AppendTime_BeforeSessionStart_Negative() {
        assertThat(time(0L, 2_500_000L), is("-0.002500"));
    }

    @Test
    public void AppendFixed_PaddingSignAndRounding() {
        assertThat(fixed(9.80665, 5), is("9.80665"));
        assertThat(fixed(-0.000012, 5), is("-0.00001"));
        assertThat(fixed(-0.000004, 5), is("0.00000"));
        assertThat(fixed(0.05, 3), is("0.050"));
        assertThat(fixed(45.12345678, 8), is("45.12345678"));
        assertThat(fixed(-111.0429, 8), is("-111.04290000"));
        assertThat(fixed(3.0, 0), is("3"));
    }

    @Test
    public void AppendFixed_NoScientificNotation() {
        assertThat(fixed(1e-7, 6), is("0.000000"));
        assertThat(fixed(12345678.9, 2), is("12345678.90"));
    }

    @Test
    public void AppendFixed_NotANumber_EmptyField() {
        assertThat(fixed(Double.NaN, 3), is(""));
        assertThat(fixed(Double.POSITIVE_INFINITY, 3), is(""));
    }

    @Test
    public void AppendFixed_IgnoresCommaDecimalLocale() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.GERMANY);
            assertThat(fixed(1.5, 2), is("1.50"));
            assertThat(time(1_500_000_000L, 0L), is("1.500000"));
        } finally {
            Locale.setDefault(original);
        }
    }
}
