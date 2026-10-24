package id.project.logs.simplelogparser.format;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.format.json.JsonEntryParser;
import id.project.logs.simplelogparser.format.plain.MdcParser;
import id.project.logs.simplelogparser.format.plain.PlainHeaderParser;
import id.project.logs.simplelogparser.format.plain.SectionSplitter;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.model.Scope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

/** MDC, header, sections, JSON parsing and format detection. */
class FormatTest {

    @Test
    void mdcWithPositionalIdsAndPairs() {
        assertThat(MdcParser.parse("7DVay:KwWy5:merchantId: 786, merchantUserId: 4118, isShiftOpen: true"))
                .containsExactly(entry("correlationId", "7DVay"), entry("requestId", "KwWy5"),
                        entry("merchantId", "786"), entry("merchantUserId", "4118"), entry("isShiftOpen", "true"));
    }

    @Test
    void mdcEdgeCases() {
        assertThat(MdcParser.parse("abc:def:")).containsExactly(entry("correlationId", "abc"), entry("requestId", "def"));
        assertThat(MdcParser.parse("::")).isEmpty();
        assertThat(MdcParser.parse("abc::")).containsExactly(entry("correlationId", "abc"));
        assertThat(MdcParser.parse("onlyone")).containsExactly(entry("correlationId", "onlyone"));
        assertThat(MdcParser.parse("merchantId: 1000, shiftId: 5"))
                .containsExactly(entry("merchantId", "1000"), entry("shiftId", "5"));
        assertThat(MdcParser.parse("a:b:k: v1, k: v2, junk, url: http://x:8080/p"))
                .contains(entry("k", "v1"), entry("url", "http://x:8080/p")).doesNotContainKey("junk");
        assertThat(MdcParser.parse("has space:b:k: v")).doesNotContainKeys("correlationId", "requestId");
    }

    @Test
    void headerRegexAnchorsTheMdcOnTheTimestamp() {
        Optional<PlainHeaderParser.Header> h = PlainHeaderParser.parse(
                "[e1:r1:note: has]bracket] 2026-10-19 08:00:00.000 [main] INFO  c.e.Bracket - msg ] here");
        assertThat(h).isPresent();
        assertThat(h.get().mdc()).isEqualTo("e1:r1:note: has]bracket");
        assertThat(h.get().thread()).isEqualTo("main");
        assertThat(h.get().level()).isEqualTo("INFO");
        assertThat(h.get().logger()).isEqualTo("c.e.Bracket");
        assertThat(h.get().message()).isEqualTo("msg ] here");
        assertThat(PlainHeaderParser.parse("[x] 2026-10-19 8:00:00.000 [main] INFO  c - m")).isEmpty();
        assertThat(PlainHeaderParser.parse("[x] 2026-10-19 08:00:00.000 [main] INFO  c.Filter - ").get().message()).isEmpty();
        assertThat(PlainHeaderParser.parse("[x] 2026-10-19 08:00:00.000 [main] INFO  c.Filter -").get().message()).isEmpty();
        assertThat(PlainHeaderParser.parse("[x] 2026-10-19 08:00:00.000 [main] INFO  c.Filter -x")).isEmpty();
        assertThat(PlainHeaderParser.parse("\tat example.Foo.bar(Foo.java:1)")).isEmpty();
    }

    @Test
    void plainTimestampsUseTheConfiguredZone() {
        assertThat(PlainHeaderParser.toInstant("2026-10-18 07:00:00.000", ParseContext.DEFAULT_ZONE))
                .contains(Instant.parse("2026-10-18T00:00:00Z"));
    }

    @Test
    void sectionsFollowTheFirstMarkers() {
        String text = "hdr\n-- REQUEST --\n|> a\n-- RESPONSE --\n|< b\n-- REQUEST --\n|> c\n-- RESPONSE --\n|< d";
        SectionSplitter.Sections s = SectionSplitter.split(text);
        assertThat(text.substring(s.requestStart(), s.requestEnd())).isEqualTo("|> a\n");
        assertThat(text.substring(s.responseStart(), s.responseEnd())).isEqualTo("|< b\n-- REQUEST --\n|> c\n-- RESPONSE --\n|< d");

        SectionSplitter.Sections onlyReq = SectionSplitter.split("hdr\n  -- REQUEST --  \n|> x\n");
        assertThat(onlyReq.responseStart()).isEqualTo(-1);
        assertThat(onlyReq.requestEnd()).isEqualTo("hdr\n  -- REQUEST --  \n|> x\n".length());

        assertThat(SectionSplitter.split("no markers here").requestStart()).isEqualTo(-1);
    }

    @Test
    void plainEntryExposesBuiltInsMdcAndSections() throws Exception {
        String text = "[c1:r1:merchantId: 786, level: shadowed] 2026-10-18 00:00:04.537 [exec-6] INFO  a.b.C - \r\n"
                + "-- REQUEST --\r\n|> GET /x?orderId=1\r\n-- RESPONSE --\r\n|< 200 OK\r\n";
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        LogEntry e = FormatHandler.of(LogFormat.PLAIN, StandardCharsets.UTF_8)
                .parse(new RawEntry(0, bytes.length, bytes, bytes.length, false, false), ParseContext.defaults());
        assertThat(e.level()).isEqualTo(Level.INFO);
        assertThat(e.field("merchantId")).contains("786");
        assertThat(e.field("correlationId")).contains("c1");
        assertThat(e.field("requestId")).contains("r1");
        assertThat(e.field("level")).contains("INFO");
        assertThat(e.field("thread")).contains("exec-6");
        assertThat(e.field("logger")).contains("a.b.C");
        assertThat(e.field("timestamp")).contains("2026-10-18 00:00:04.537");
        assertThat(e.rawText()).isEqualTo(text);
        assertThat(e.matchText()).doesNotContain("\r");
        assertThat(e.section(Scope.REQUEST).toString()).isEqualTo("|> GET /x?orderId=1\n");
        assertThat(e.section(Scope.RESPONSE).toString()).isEqualTo("|< 200 OK\n");
    }

    @Test
    void jsonScalarsNestedValuesAndNulls() throws Exception {
        Map<String, String> f = JsonEntryParser.parse("{\"a\":\"147581\",\"b\":147581,\"c\":true,\"d\":null,"
                + "\"e\":{\"x\": [1, 2], \"y\": \"s p\"},\"a\":\"dup\",\"m\":\"x\\ny \\\"q\\\" caf\\u00e9\"}");
        assertThat(f).containsEntry("a", "147581").containsEntry("b", "147581").containsEntry("c", "true")
                .doesNotContainKey("d").containsEntry("e", "{\"x\":[1,2],\"y\":\"s p\"}")
                .containsEntry("m", "x\ny \"q\" café");
    }

    @Test
    void malformedJsonIsRejected() {
        for (String bad : new String[]{"not json", "[1,2]", "{\"a\":1", "{\"a\":1} trailing", "{\"a\":\"unterminated}"}) {
            assertThatThrownBy(() -> JsonEntryParser.parse(bad)).as(bad).isInstanceOf(MalformedEntryException.class);
        }
    }

    @Test
    void jsonEntryAliasesAndMatchText() throws Exception {
        String line = "{\"@timestamp\":\"2026-10-22T01:04:28.4980168Z\",\"log.level\":\"WARN\","
                + "\"process.thread.name\":\"main\",\"log.logger\":\"a.B\",\"message\":\"one\\ntwo\"}\r\n";
        byte[] b = line.getBytes(StandardCharsets.UTF_8);
        LogEntry e = FormatHandler.of(LogFormat.JSON, StandardCharsets.UTF_8)
                .parse(new RawEntry(0, b.length, b, b.length, false, false), ParseContext.defaults());
        assertThat(e.level()).isEqualTo(Level.WARN);
        assertThat(e.field("level")).contains("WARN");
        assertThat(e.field("log.level")).contains("WARN");
        assertThat(e.field("thread")).contains("main");
        assertThat(e.field("logger")).contains("a.B");
        assertThat(e.timestamp()).contains(Instant.parse("2026-10-22T01:04:28.4980168Z"));
        assertThat(e.matchText()).isEqualTo("one\ntwo\n" + line.strip());
        assertThat(e.sectionStart(Scope.REQUEST)).isEqualTo(-1);
    }

    @Test
    void detectsFormats(@TempDir Path dir) throws Exception {
        assertThat(detect(Fixtures.path("plain/basic.log"))).isEqualTo(LogFormat.PLAIN);
        assertThat(detect(Fixtures.path("plain/edge.log"))).isEqualTo(LogFormat.PLAIN);
        assertThat(detect(Fixtures.path("plain/bom.log"))).isEqualTo(LogFormat.PLAIN);
        assertThat(detect(Fixtures.path("plain/crlf.log"))).isEqualTo(LogFormat.PLAIN);
        assertThat(detect(Fixtures.path("json/basic.log"))).isEqualTo(LogFormat.JSON);
        assertThatThrownBy(() -> detect(Fixtures.path("plain/neither.txt")))
                .isInstanceOf(FormatDetectionException.class).hasMessageContaining("unrecognized");
        Path empty = Files.createFile(dir.resolve("empty.log"));
        assertThatThrownBy(() -> detect(empty)).hasMessageContaining("empty");
        Path blank = Files.writeString(dir.resolve("blank.log"), "\n\n   \n");
        assertThatThrownBy(() -> detect(blank)).hasMessageContaining("no log entries");
    }

    private static LogFormat detect(Path p) throws Exception {
        try (LogFile f = LogFile.open(p)) {
            return FormatDetector.detect(f, StandardCharsets.UTF_8);
        }
    }
}
