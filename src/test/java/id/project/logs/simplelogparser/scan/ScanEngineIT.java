package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.TextQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scan engine behavior: streaming and progress (AC13), result cap (AC14), cancel latency (AC10), headerless
 * prefix (AC23), invalid UTF-8 (AC24), entry limit (edge #6) and line numbers.
 */
class ScanEngineIT {

    @TempDir
    Path dir;

    @Test
    void hitsStreamBeforeFinishAndProgressIsMonotonic() throws Exception {
        Path file = dir.resolve("gen.log");
        LogGenerator.plain(file, 2_000_000, 11, false, false);
        CompiledQuery q = Fixtures.compile(Query.matchAll()).forFormat(LogFormat.PLAIN);
        List<Double> progress = Collections.synchronizedList(new ArrayList<>());
        AtomicBoolean hitsBeforeFinish = new AtomicBoolean();
        AtomicBoolean finished = new AtomicBoolean();
        ScanSummary summary = new ScanEngine().run(
                ScanRequest.of(file, LogFormat.PLAIN, q).withChunkSize(64 * 1024).withThreads(4),
                new ScanListener() {
                    @Override
                    public void onHits(List<Hit> hits) {
                        if (!finished.get()) {
                            hitsBeforeFinish.set(true);
                        }
                    }

                    @Override
                    public void onProgress(double fraction) {
                        progress.add(fraction);
                    }

                    @Override
                    public void onFinished(ScanSummary s) {
                        finished.set(true);
                    }
                });
        assertThat(hitsBeforeFinish).isTrue();
        assertThat(finished).isTrue();
        assertThat(summary.matched()).isPositive();
        assertThat(progress).isSortedAccordingTo(Double::compare);
        assertThat(progress.get(progress.size() - 1)).isEqualTo(1.0);
    }

    @Test
    void capReturnsTheFirstNHitsInFileOrderForAnyThreadCount() throws Exception {
        Path file = dir.resolve("gen.log");
        LogGenerator.plain(file, 1_000_000, 5, false, false);
        CompiledQuery q = Fixtures.compile(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))))
                .forFormat(LogFormat.PLAIN);
        List<Hit> all = Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, q).withThreads(1)).hits();
        assertThat(all.size()).isGreaterThan(50);
        for (int threads : new int[]{1, 3, 8}) {
            Fixtures.Result capped = Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, q)
                    .withResultCap(25).withThreads(threads).withChunkSize(4096));
            assertThat(capped.hits()).as("threads %d", threads).isEqualTo(all.subList(0, 25));
            assertThat(capped.summary().capped()).isTrue();
            assertThat(capped.summary().cancelled()).isFalse();
        }
        Fixtures.Result uncapped = Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, q).withResultCap(100_000));
        assertThat(uncapped.summary().capped()).isFalse();
    }

    @Test
    void cancelDuringCatastrophicRegexCompletesWithinTwoSeconds() throws Exception {
        Path file = dir.resolve("evil.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - ").append("a".repeat(80)).append("\n");
        }
        Files.writeString(file, sb);
        CompiledQuery q = Fixtures.compile(Query.matchAll().withText(TextQuery.regex("(.*a){12}x"))).forFormat(LogFormat.PLAIN);
        ScanRequest req = ScanRequest.of(file, LogFormat.PLAIN, q).withThreads(2).withChunkSize(512)
                .withRegexTimeout(Duration.ofHours(1));
        CountDownLatch done = new CountDownLatch(1);
        ScanHandle handle = new ScanEngine().start(req, new ScanListener() {
            @Override
            public void onFinished(ScanSummary summary) {
                done.countDown();
            }
        });
        Thread.sleep(300);
        long t0 = System.nanoTime();
        handle.cancel();
        assertThat(handle.done().get(5, TimeUnit.SECONDS).cancelled()).isTrue();
        assertThat(Duration.ofNanos(System.nanoTime() - t0)).isLessThan(Duration.ofSeconds(2));
    }

    @Test
    void regexTimeoutsAreCountedAndTheScanContinues() throws Exception {
        Path file = dir.resolve("evil.log");
        Files.writeString(file, "[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - " + "a".repeat(80) + "\n"
                + "[a:b:] 2026-10-18 00:00:01.000 [t] INFO c - aaaaaaaaaaaax\n");
        CompiledQuery q = Fixtures.compile(Query.matchAll().withText(TextQuery.regex("(.*a){12}x"))).forFormat(LogFormat.PLAIN);
        Fixtures.Result r = Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, q).withRegexTimeout(Duration.ofMillis(100)));
        assertThat(r.summary().regexTimeouts()).isEqualTo(1);
        assertThat(r.lines()).containsExactly(2L);
    }

    @Test
    void headerlessPrefixIsSurfacedAsAnEntry() throws Exception {
        Fixtures.Result r = Fixtures.scan(Fixtures.path("plain/edge.log"), LogFormat.PLAIN,
                Query.matchAll().withText(TextQuery.literal("ORPHAN-TOKEN")));
        assertThat(r.lines()).containsExactly(1L);
        assertThat(r.hits().get(0).summary().level()).isEqualTo(id.project.logs.simplelogparser.model.Level.UNKNOWN);
        // a field filter never matches it
        assertThat(Fixtures.scan(Fixtures.path("plain/edge.log"), LogFormat.PLAIN,
                Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "100")))).lines()).containsExactly(3L);
    }

    @Test
    void invalidUtf8DoesNotAbortTheScan() throws Exception {
        Path file = Fixtures.path("plain/invalid-utf8.log");
        Fixtures.Result all = Fixtures.scan(file, LogFormat.PLAIN, Query.matchAll());
        assertThat(all.lines()).containsExactly(1L, 3L);
        Fixtures.Result bad = Fixtures.scan(file, LogFormat.PLAIN, Query.matchAll().withText(TextQuery.literal("BADBYTES")));
        assertThat(bad.lines()).containsExactly(1L);
        try (LogFile lf = LogFile.open(file)) {
            LogEntry e = new EntryReader(lf, LogFormat.PLAIN, StandardCharsets.UTF_8, ParseContext.defaults(), 1 << 20)
                    .read(bad.hits().get(0));
            assertThat(e.rawText()).contains("�");
        }
    }

    @Test
    void oversizedEntriesAreTruncatedForMatchingAndFlagged() throws Exception {
        Path file = dir.resolve("big-entry.log");
        String body = "|< " + "x".repeat(5000) + " TAILTOKEN\n";
        Files.writeString(file, "[a:b:merchantId: 1] 2026-10-18 00:00:00.000 [t] INFO c - HEADTOKEN\n" + body
                + "[a:b:merchantId: 2] 2026-10-18 00:00:01.000 [t] INFO c - next\n");
        CompiledQuery head = Fixtures.compile(Query.matchAll().withText(TextQuery.literal("HEADTOKEN"))).forFormat(LogFormat.PLAIN);
        Fixtures.Result r = Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, head).withEntryLimit(1000));
        assertThat(r.hits()).hasSize(1);
        assertThat(r.hits().get(0).truncated()).isTrue();
        assertThat(r.hits().get(0).byteLength()).isGreaterThan(5000);
        assertThat(r.summary().truncatedEntries()).isEqualTo(1);
        CompiledQuery tail = Fixtures.compile(Query.matchAll().withText(TextQuery.literal("TAILTOKEN"))).forFormat(LogFormat.PLAIN);
        assertThat(Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, tail).withEntryLimit(1000)).hits()).isEmpty();
        assertThat(Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, tail)).hits()).hasSize(1);
        // the next entry is unaffected and its line number is right
        CompiledQuery next = Fixtures.compile(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "2"))))
                .forFormat(LogFormat.PLAIN);
        assertThat(Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, next).withEntryLimit(1000).withChunkSize(100)).lines())
                .containsExactly(3L);
    }

    @Test
    void scanOfCrlfFixtureReportsSameLinesAsLf() throws Exception {
        Query q = Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786")));
        assertThat(Fixtures.scan(Fixtures.path("plain/crlf.log"), LogFormat.PLAIN, q).lines())
                .isEqualTo(Fixtures.scan(Fixtures.path("plain/basic.log"), LogFormat.PLAIN, q).lines())
                .containsExactly(1L, 20L, 26L);
    }
}
