package com.mendhak.gpslogger.loggers.fsae;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class EcuLoggerTest {

    // Shape of AirBear's "reading" JSON (serialParser.cpp): raw bytes, some already scaled
    private static final String AIRBEAR_READING = "{"
            + "\"secl\":42,\"inj1_status\":1,\"dfco_active\":0,\"running\":1,"
            + "\"dwell\":3.2,\"MAP\":98,\"IAT\":24,\"CLT\":88,\"Battery_Voltage\":13.8,"
            + "\"AFR1\":13.2,\"correction_o2\":102,\"correction_iat\":100,\"correction_wue\":100,"
            + "\"rpm\":6543,\"correction_ae\":100,\"correction_total\":103,\"VE\":87,"
            + "\"afr_target\":12.8,\"PW1\":412.5,\"tps_DOT\":2500,\"advance\":250,\"TPS\":183,"
            + "\"baro\":85,\"rpmDOT\":65036}";

    private static String row(String json) throws Exception {
        StringBuilder sb = new StringBuilder();
        EcuLogger.appendRow(sb, new JSONObject(json));
        return sb.toString();
    }

    @Test
    public void AppendRow_ColumnCountMatchesHeader() throws Exception {
        int headerCols = EcuLogger.ECU_HEADER.split(",", -1).length;
        // appendRow writes everything after time_s,airbear_ms, each field with a leading comma
        int rowCols = 2 + row(AIRBEAR_READING).split(",", -1).length - 1;
        assertThat(rowCols, is(headerCols));
    }

    @Test
    public void AppendRow_ScalesAndSignsSpeeduinoFields() throws Exception {
        String[] f = row(AIRBEAR_READING).substring(1).split(",", -1);
        assertThat(f[0], is("6543"));      // rpm
        assertThat(f[1], is("98"));        // map_kpa
        assertThat(f[2], is("91.5"));      // tps_pct: raw 183 in 0.5% steps
        assertThat(f[3], is("13.2"));      // afr
        assertThat(f[4], is("12.8"));      // afr_target
        assertThat(f[5], is("88"));        // clt_c
        assertThat(f[6], is("24"));        // iat_c
        assertThat(f[7], is("13.8"));      // batt_v
        assertThat(f[8], is("-6"));        // advance_deg: 250 as int8
        assertThat(f[9], is("4.125"));     // pw1_ms: AirBear 412.5 = 4125 us / 10
        assertThat(f[10], is("87"));       // ve_pct
        assertThat(f[11], is("3.2"));      // dwell_ms
        assertThat(f[12], is("85"));       // baro_kpa
        assertThat(f[13], is("-500"));     // rpm_dot: 65036 as int16
        assertThat(f[14], is("-60"));      // tps_dot: byte 250 as int8 = -6, * 10
        assertThat(f[15], is("102"));      // ego_corr_pct
        assertThat(f[16], is("100"));      // wue_corr_pct
        assertThat(f[17], is("100"));      // ae_corr_pct
        assertThat(f[18], is("103"));      // gamma_corr_pct
        assertThat(f[19], is("1"));        // running
        assertThat(f[20], is("0"));        // dfco
    }

    @Test
    public void AppendRow_MissingFieldsAreEmpty() throws Exception {
        String[] f = row("{\"rpm\":900,\"running\":true}").substring(1).split(",", -1);
        assertThat(f[0], is("900"));
        assertThat(f[2], is(""));
        assertThat(f[8], is(""));
        assertThat(f[19], is("1"));
        assertThat(f[20], is(""));
    }

    private static class Captured {
        String event, id, data;
        long ns;
    }

    private static List<Captured> parse(String... lines) {
        final List<Captured> out = new ArrayList<>();
        SseParser p = new SseParser(new SseParser.Listener() {
            @Override
            public void onEvent(String event, String id, String data, long receivedNs) {
                Captured c = new Captured();
                c.event = event;
                c.id = id;
                c.data = data;
                c.ns = receivedNs;
                out.add(c);
            }
        });
        long t = 1000;
        for (String l : lines) {
            p.line(l, t++);
        }
        return out;
    }

    @Test
    public void SseParser_AirBearStream() {
        // What ESPAsyncWebServer's AsyncEventSource emits for hello, reading, ping and nodata
        List<Captured> ev = parse(
                "retry: 10000", "id: 5", "data: hello!", "",
                "id: 1234", "event: reading", "data: {\"rpm\":900}", "",
                "id: 1300", "data: ping", "",
                "id: 1400", "event: nodata", "data: nodata", "");
        assertThat(ev.size(), is(4));
        assertThat(ev.get(0).data, is("hello!"));
        assertThat(ev.get(1).event, is("reading"));
        assertThat(ev.get(1).id, is("1234"));
        assertThat(ev.get(1).data, is("{\"rpm\":900}"));
        assertThat(ev.get(1).ns, is(1006L)); // time of its data line
        assertThat(ev.get(2).event == null, is(true));
        assertThat(ev.get(3).event, is("nodata"));
    }

    @Test
    public void SseParser_MultiLineDataAndComments() {
        List<Captured> ev = parse(": keepalive", "event: reading", "data: a", "data:b", "", "");
        assertThat(ev.size(), is(1));
        assertThat(ev.get(0).data, is("a\nb"));
    }
}
