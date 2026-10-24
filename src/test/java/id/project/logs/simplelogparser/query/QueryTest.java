package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.model.Scope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Compiler, predicates, prefilter, regex guard, highlighting and time parsing. */
class QueryTest {

    private static final String PLAIN = "[c1:r1:merchantId: 786, shiftId: 147581] 2026-10-18 07:00:04.537 [exec-6] "
            + "ERROR a.b.C - Boom\n-- REQUEST --\n|> GET /x?orderId=2837537\n-- RESPONSE --\n|< 500 {\"status\": 503}\n";

    private static RawEntry raw(String text) {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        return new RawEntry(0, b.length, b, b.length, false, false);
    }

    private static LogEntry plain(String text) throws Exception {
        return FormatHandler.of(LogFormat.PLAIN, StandardCharsets.UTF_8).parse(raw(text), ParseContext.defaults());
    }

    private static LogEntry json(String text) throws Exception {
        return FormatHandler.of(LogFormat.JSON, StandardCharsets.UTF_8).parse(raw(text), ParseContext.defaults());
    }

    private static boolean matches(Query q, LogEntry e) throws Exception {
        return QueryCompiler.compile(q).matches(e, MatchBudget.unlimited());
    }

    @Test
    void invalidRegexReportsDescriptionAndIndex() {
        assertThatThrownBy(() -> QueryCompiler.compile(Query.matchAll().withText(TextQuery.regex("ab(c"))))
                .isInstanceOfSatisfying(QuerySyntaxException.class, e -> {
                    assertThat(e.index()).isEqualTo(4);
                    assertThat(e.getMessage()).contains("ab(c").contains("Unclosed group").contains("index 4");
                });
        assertThatThrownBy(() -> QueryCompiler.compile(Query.matchAll()
                .withFields(List.of(new FieldFilter("merchantId", MatchMode.REGEX, List.of("["))))))
                .isInstanceOf(QuerySyntaxException.class);
    }

    @Test
    void fieldModesAreCaseSensitiveTrimmedAndWithoutNumericCoercion() throws Exception {
        LogEntry e = plain(PLAIN);
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", " 786 "))), e)).isTrue();
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "0786"))), e)).isFalse();
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "1", "786"))), e)).isTrue();
        assertThat(matches(Query.matchAll().withFields(List.of(new FieldFilter("shiftId", MatchMode.CONTAINS, List.of("475")))), e)).isTrue();
        assertThat(matches(Query.matchAll().withFields(List.of(new FieldFilter("shiftId", MatchMode.REGEX, List.of("^1475\\d+$")))), e)).isTrue();
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("level", "error"))), e)).isFalse();
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("missing", "x"))), e)).isFalse();
        // different fields AND'd
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"),
                FieldFilter.exact("shiftId", "1"))), e)).isFalse();
    }

    @Test
    void levelsTimeRangeAndScopes() throws Exception {
        LogEntry e = plain(PLAIN);
        assertThat(matches(Query.matchAll().withLevels(Set.of(Level.ERROR, Level.WARN)), e)).isTrue();
        assertThat(matches(Query.matchAll().withLevels(Set.of(Level.INFO)), e)).isFalse();
        Instant ts = Instant.parse("2026-10-18T00:00:04.537Z");
        assertThat(matches(Query.matchAll().withRange(ts, null), e)).isTrue();
        assertThat(matches(Query.matchAll().withRange(null, ts), e)).isFalse();
        assertThat(matches(Query.matchAll().withRange(null, ts.plusMillis(1)), e)).isTrue();
        TextQuery order = TextQuery.literal("ORDERID=2837537");
        assertThat(matches(Query.matchAll().withText(order.withScope(Scope.REQUEST)), e)).isTrue();
        assertThat(matches(Query.matchAll().withText(order.withScope(Scope.RESPONSE)), e)).isFalse();
        assertThat(matches(Query.matchAll().withText(order.withCaseSensitive(true)), e)).isFalse();
        assertThat(matches(Query.matchAll().withText(TextQuery.regex("\"status\":\\s*5\\d\\d").withScope(Scope.RESPONSE)), e)).isTrue();
    }

    @Test
    void jsonQueriesUseFallbackScopeAndCompactNestedValues() throws Exception {
        LogEntry e = json("{\"log.level\":\"INFO\",\"shiftId\":4891,\"logData\":{\"items\": [1, 2]},\"message\":\"caf\\u00e9 ok\"}\n");
        CompiledQuery q = QueryCompiler.compile(Query.matchAll()
                .withText(TextQuery.literal("café").withScope(Scope.REQUEST)));
        assertThat(q.matches(e, MatchBudget.unlimited())).isFalse();
        assertThat(q.forFormat(LogFormat.JSON).matches(e, MatchBudget.unlimited())).isTrue();
        assertThat(matches(Query.matchAll().withFields(List.of(FieldFilter.exact("shiftId", "4891"))), e)).isTrue();
        assertThat(matches(Query.matchAll().withFields(List.of(new FieldFilter("logData", MatchMode.CONTAINS, List.of("[1,2]")))), e)).isTrue();
    }

    @Test
    void prefilterOnlyProducesFalsePositives() throws Exception {
        CompiledQuery q = QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("orderid=2837537")))
                .forFormat(LogFormat.PLAIN);
        assertThat(q.hasPrefilter()).isTrue();
        assertThat(q.mayMatch(raw(PLAIN))).isTrue();
        assertThat(q.mayMatch(raw("[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - nothing\n"))).isFalse();

        // UNICODE_CASE folds U+212A (Kelvin) onto 'k': entries with non-ASCII bytes are never rejected for such needles
        CompiledQuery kelvin = QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("kelvin")))
                .forFormat(LogFormat.PLAIN);
        String withKelvin = "[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - Kelvin\n";
        assertThat(kelvin.mayMatch(raw(withKelvin))).isTrue();
        assertThat(kelvin.matches(plain(withKelvin), MatchBudget.unlimited())).isTrue();

        // JSON escapes: \\u sequences disable rejection, '/' and structural characters skip the group
        CompiledQuery cafe = QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("abc").withCaseSensitive(true)))
                .forFormat(LogFormat.JSON);
        assertThat(cafe.mayMatch(raw("{\"message\":\"a\\u0062c\"}"))).isTrue();
        CompiledQuery slash = QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("a/b")))
                .forFormat(LogFormat.JSON);
        assertThat(slash.mayMatch(raw("{\"message\":\"a\\/b\"}"))).isTrue();

        // values with quotes, backslashes or non-ASCII under case-insensitive matching get no prefilter at all
        assertThat(QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("say \"hi\""))).hasPrefilter()).isFalse();
        assertThat(QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("café"))).hasPrefilter()).isFalse();
        assertThat(QueryCompiler.compile(Query.matchAll().withText(TextQuery.regex("a+"))).hasPrefilter()).isFalse();
        assertThat(QueryCompiler.compile(Query.matchAll().withText(TextQuery.literal("x")),
                StandardCharsets.ISO_8859_1).hasPrefilter()).isFalse();
    }

    @Test
    void catastrophicRegexTimesOutWithinBudget() throws Exception {
        LogEntry e = plain("[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - " + "a".repeat(80) + "\n");
        CompiledQuery q = QueryCompiler.compile(Query.matchAll().withText(TextQuery.regex("(.*a){12}x")));
        MatchBudget budget = new MatchBudget(Duration.ofMillis(200), new CancelToken());
        long t0 = System.nanoTime();
        assertThatThrownBy(() -> q.matches(e, budget)).isInstanceOf(MatchTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void cancelStopsARunningRegex() throws Exception {
        LogEntry e = plain("[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - " + "a".repeat(80) + "\n");
        CompiledQuery q = QueryCompiler.compile(Query.matchAll().withText(TextQuery.regex("(.*a){12}x")));
        CancelToken token = new CancelToken();
        token.cancel();
        assertThatThrownBy(() -> q.matches(e, new MatchBudget(Duration.ofDays(1), token)))
                .isInstanceOf(ScanCancelledException.class);
    }

    @Test
    void highlighterFindsTextAndFieldRanges() throws Exception {
        LogEntry e = plain(PLAIN);
        CompiledQuery q = QueryCompiler.compile(Query.matchAll()
                .withFields(List.of(FieldFilter.exact("shiftId", "147581")))
                .withText(TextQuery.literal("orderId=2837537").withScope(Scope.REQUEST)));
        List<Highlighter.Range> ranges = Highlighter.ranges(e, q, MatchBudget.unlimited());
        String text = e.matchText();
        assertThat(ranges).extracting(r -> text.substring(r.start(), r.end()))
                .containsExactly("147581", "orderId=2837537");

        LogEntry crlf = plain(PLAIN.replace("\n", "\r\n"));
        List<Highlighter.Range> raw = Highlighter.toRawRanges(crlf, Highlighter.ranges(crlf, q, MatchBudget.unlimited()));
        assertThat(raw).extracting(r -> crlf.rawText().substring(r.start(), r.end()))
                .containsExactly("147581", "orderId=2837537");
    }

    @Test
    void timeParserAcceptsIsoFormsAndUsesTheZoneWithoutOffset() {
        ZoneId jkt = ZoneId.of("Asia/Jakarta");
        assertThat(TimeParser.parse("2026-10-18T07:00", jkt)).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        assertThat(TimeParser.parse("2026-10-18 07:00:00", jkt)).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        assertThat(TimeParser.parse("2026-10-18T00:00:00+07:00", jkt)).isEqualTo(Instant.parse("2026-10-17T17:00:00Z"));
        assertThat(TimeParser.parse("2026-10-18T00:00:00Z", jkt)).isEqualTo(Instant.parse("2026-10-18T00:00:00Z"));
        assertThat(TimeParser.parse("2026-10-18", jkt)).isEqualTo(Instant.parse("2026-10-17T17:00:00Z"));
        assertThatThrownBy(() -> TimeParser.parse("yesterday", jkt)).isInstanceOf(IllegalArgumentException.class);
    }
}
