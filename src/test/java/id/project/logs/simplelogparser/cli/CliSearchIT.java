package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.Fixtures;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static id.project.logs.simplelogparser.cli.CliHarness.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Searches through the CLI against hand-counted fixtures: field search (AC2-AC5a), scopes (AC6), regex
 * spans (AC8), case sensitivity (AC9), levels and time range. Expected values are first-line numbers.
 */
class CliSearchIT {

    private static final String BASIC = Fixtures.path("plain/basic.log").toString();
    private static final String EDGE = Fixtures.path("plain/edge.log").toString();
    private static final String JSON = Fixtures.path("json/basic.log").toString();
    private static final Pattern LINE = Pattern.compile("\"line\":(\\d+)");

    /** First-line numbers of the hits, via --json output. */
    private static List<Long> lines(String... args) {
        String[] all = new String[args.length + 1];
        all[0] = "--json";
        System.arraycopy(args, 0, all, 1, args.length);
        CliHarness.Run r = run(all);
        assertThat(r.code()).as(r.stderr()).isIn(ExitCode.MATCH, ExitCode.NO_MATCH);
        List<Long> out = new ArrayList<>();
        for (String l : r.outLines()) {
            Matcher m = LINE.matcher(l);
            assertThat(m.find()).isTrue();
            out.add(Long.parseLong(m.group(1)));
        }
        assertThat(r.code()).isEqualTo(out.isEmpty() ? ExitCode.NO_MATCH : ExitCode.MATCH);
        return out;
    }

    @Test
    void plainFieldSearch() {
        assertThat(lines("--field", "merchantId=786", BASIC)).containsExactly(1L, 20L, 26L);
        assertThat(lines("--field", "merchantId=786", "--field", "shiftId=147581", BASIC)).containsExactly(1L, 26L);
        assertThat(lines("--field", "shiftId=147581", "--field", "shiftId=147571", BASIC)).containsExactly(1L, 11L, 26L);
        assertThat(lines("--field", "requestId=reqB1", BASIC)).containsExactly(10L, 11L);
        assertThat(lines("--field", "correlationId=corrB1", BASIC)).containsExactly(10L, 11L);
        assertThat(lines("--field", "merchantId*=78", BASIC)).containsExactly(1L, 20L, 26L);
        assertThat(lines("--field", "merchantId~=^(786|1242)$", BASIC)).containsExactly(1L, 11L, 20L, 26L);
        assertThat(lines("--field", "noSuchField=1", BASIC)).isEmpty();
        assertThat(lines("--field", "merchantId=0786", BASIC)).isEmpty();
        assertThat(lines("--field", "logger=i.h.p.a.j.s.s.SyncTrxServiceImpl", BASIC)).containsExactly(27L);
    }

    @Test
    void plainMdcEdgeCases() {
        assertThat(lines("--field", "note=has]bracket", EDGE)).containsExactly(3L);
        assertThat(lines("--field", "city=Zürich", EDGE)).containsExactly(20L);
        assertThat(lines("--field", "correlationId=onlyone", EDGE)).containsExactly(30L);
        assertThat(lines("--field", "merchantId=1000", EDGE)).containsExactly(31L);
        assertThat(lines("--field", "correlationId=merchantId", EDGE)).isEmpty();
        // a header-like line inside a stack trace splits the entry (known limitation, edge #4)
        assertThat(lines("--field", "merchantId=601", EDGE)).containsExactly(23L);
    }

    @Test
    void jsonFieldSearch() {
        assertThat(lines("--field", "shiftId=4891", JSON)).containsExactly(5L, 7L);
        assertThat(lines("--field", "traceId=trace-BBB", JSON)).containsExactly(7L, 8L);
        assertThat(lines("--field", "merchantExternalId=M-1", JSON)).containsExactly(5L);
        assertThat(lines("--field", "enabled=true", JSON)).containsExactly(8L);
        assertThat(lines("--field", "logData*=[1,2]", JSON)).containsExactly(7L);
        assertThat(lines("--field", "level=ERROR", JSON)).containsExactly(8L);
        assertThat(lines("--field", "log.level=ERROR", JSON)).containsExactly(8L);
    }

    @Test
    void textScopes() {
        assertThat(lines("--text", "orderId=2837537", "--in", "request", BASIC)).containsExactly(1L);
        assertThat(lines("--text", "orderId=2837537", "--in", "response", BASIC)).containsExactly(11L);
        assertThat(lines("--text", "orderId=2837537", BASIC)).containsExactly(1L, 11L);
        assertThat(lines("--text", "REQONLY", "--in", "request", EDGE)).containsExactly(4L);
        assertThat(lines("--text", "REQONLY", "--in", "response", EDGE)).isEmpty();
        assertThat(lines("--text", "RESPONLY", "--in", "response", EDGE)).containsExactly(7L);
        assertThat(lines("--text", "RESPONLY", "--in", "request", EDGE)).isEmpty();
        assertThat(lines("--text", "DUPREQ1", "--in", "request", EDGE)).containsExactly(11L);
        assertThat(lines("--text", "DUPREQ2", "--in", "request", EDGE)).isEmpty();
        assertThat(lines("--text", "DUPREQ2", "--in", "response", EDGE)).containsExactly(11L);
        assertThat(lines("--regex", "\"status\":\\s*5\\d\\d", "--in", "response", EDGE)).containsExactly(7L);
    }

    @Test
    void jsonTextSearchUsesDecodedMessageAndRawLine() {
        assertThat(lines("--text", "BANNERLINE", JSON)).containsExactly(4L);
        assertThat(lines("--text", "café", JSON)).containsExactly(7L);
        assertThat(lines("--text", "order \"created\"", JSON)).containsExactly(7L);
        assertThat(lines("--regex", "failed\\nline two", JSON)).containsExactly(8L);
        assertThat(lines("--text", "\"responseStatusCode\":201", JSON)).containsExactly(7L);
        CliHarness.Run r = run("--count", "--text", "slow", "--in", "request", JSON);
        assertThat(r.out().trim()).isEqualTo("1");
        assertThat(r.stderr()).contains("does not apply to JSON");
    }

    @Test
    void regexMatchesAcrossLinesWithinAnEntryButNeverAcrossEntries() {
        assertThat(lines("--regex", "SPANSTART[^\\n]*\\nsecond", EDGE)).containsExactly(25L);
        assertThat(lines("--regex", "SPANSTART.*SPANEND", "--dotall", EDGE)).containsExactly(25L);
        assertThat(lines("--regex", "SPANSTART.*SPANEND", EDGE)).isEmpty();
        assertThat(lines("--regex", "COMPLETED <<\\n\\[::\\]", BASIC)).isEmpty();
        assertThat(lines("--regex", "Unhandled exception\\nexample", BASIC)).containsExactly(20L);
    }

    @Test
    void caseSensitivity() {
        assertThat(lines("--text", "ORDERID=2837537", BASIC)).containsExactly(1L, 11L);
        assertThat(lines("-i", "--text", "ORDERID=2837537", BASIC)).containsExactly(1L, 11L);
        assertThat(lines("--case-sensitive", "--text", "ORDERID=2837537", BASIC)).isEmpty();
        assertThat(lines("--case-sensitive", "--text", "orderId=2837537", BASIC)).containsExactly(1L, 11L);
        assertThat(lines("--text", "MULTIBYTE", EDGE)).containsExactly(20L);
        assertThat(lines("--text", "日本語", EDGE)).containsExactly(20L);
        assertThat(run("-i", "--case-sensitive", "--text", "x", BASIC).code()).isEqualTo(ExitCode.ERROR);
    }

    @Test
    void levelsAndTimeRange() {
        assertThat(lines("--level", "error,warn", BASIC)).containsExactly(20L, 25L, 27L);
        assertThat(lines("--level", "TRACE", EDGE)).containsExactly(29L);
        assertThat(lines("--from", "2026-10-18T03:00", "--to", "2026-10-18T03:30:02.326", BASIC)).containsExactly(25L);
        assertThat(lines("--from", "2026-10-17T20:30:02.326Z", BASIC)).containsExactly(26L, 27L);
        assertThat(lines("--from", "2026-10-23T09:48:33Z", JSON)).containsExactly(10L);
        assertThat(lines("--from", "2026-10-18", "--to", "2026-10-19", "--zone", "UTC", BASIC))
                .containsExactly(1L, 10L, 11L, 20L, 25L, 26L, 27L);
        assertThat(lines("--from", "2026-10-19T00:00", EDGE)).doesNotContain(1L);
        assertThat(run("--level", "loud", BASIC).code()).isEqualTo(ExitCode.ERROR);
        assertThat(run("--from", "tomorrow", BASIC).code()).isEqualTo(ExitCode.ERROR);
    }

    @Test
    void headerlessPrefixAndCrlfFixture() {
        assertThat(lines("--text", "ORPHAN-TOKEN", EDGE)).containsExactly(1L);
        assertThat(lines("--field", "merchantId=786", Fixtures.path("plain/crlf.log").toString()))
                .containsExactly(1L, 20L, 26L);
        assertThat(lines("--case-sensitive", "--text", "MIXED", Fixtures.path("plain/mixed-endings.log").toString()))
                .containsExactly(1L, 3L);
        assertThat(lines("--text", "first entry after BOM", Fixtures.path("plain/bom.log").toString()))
                .containsExactly(1L);
    }
}
