package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.TextQuery;
import id.project.logs.simplelogparser.scan.LogGenerator;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.scan.ScanSummary;
import javafx.collections.ListChangeListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Scan controller with the real engine and a single-threaded executor standing in for the FX thread: hits
 * reach the list in batches, an older search never leaks into a newer one (edge #19), the cap and cancel
 * are reported, and start failures surface as an error.
 */
class ScanControllerTest {

    private final ExecutorService uiExecutor = Executors.newSingleThreadExecutor();
    private final UiThread ui = uiExecutor::execute;

    @TempDir
    Path dir;

    @AfterEach
    void stopUi() {
        uiExecutor.shutdownNow();
    }

    private ScanController controller(Path file, LogFormat format, IntSupplier cap) {
        return new ScanController(new ScanEngine(), ui, file, format, ParseContext.defaults(), cap);
    }

    private <T> T onUi(Callable<T> c) throws Exception {
        return uiExecutor.submit(c).get(10, TimeUnit.SECONDS);
    }

    private ScanSummary awaitFinished(ScanController c) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            ScanSummary s = onUi(() -> c.runningProperty().get() ? null : c.summaryProperty().get());
            if (s != null) {
                return s;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("scan did not finish");
    }

    private static CompiledQuery query(Query q, LogFormat format) {
        return Fixtures.compile(q).forFormat(format);
    }

    @Test
    void hitsArriveInFileOrderAndTheSummaryLast() throws Exception {
        Path file = Fixtures.path("plain/basic.log");
        ScanController c = controller(file, LogFormat.PLAIN, () -> 10_000);
        CompiledQuery q = query(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))), LogFormat.PLAIN);
        onUi(() -> {
            c.start(q);
            return null;
        });
        ScanSummary s = awaitFinished(c);
        assertThat(s.matched()).isEqualTo(3);
        assertThat(onUi(() -> c.hits().stream().map(Hit::lineNumber).toList())).containsExactly(1L, 20L, 26L);
        assertThat(onUi(() -> c.progressProperty().get())).isEqualTo(1.0);
        assertThat(onUi(() -> c.queryProperty().get())).isSameAs(q);
        assertThat(c.generation()).isEqualTo(1);
    }

    @Test
    void hitsAreBatchedNotAddedOneByOne() throws Exception {
        Path file = dir.resolve("gen.log");
        LogGenerator.plain(file, 3_000_000, 21, false, false);
        ScanController c = controller(file, LogFormat.PLAIN, () -> 1_000_000);
        AtomicInteger changes = new AtomicInteger();
        onUi(() -> {
            c.hits().addListener((ListChangeListener<Hit>) ch -> changes.incrementAndGet());
            c.start(query(Query.matchAll(), LogFormat.PLAIN));
            return null;
        });
        ScanSummary s = awaitFinished(c);
        assertThat(s.matched()).isGreaterThan(2_000);
        assertThat(onUi(() -> c.hits().size())).isEqualTo((int) s.matched());
        assertThat(changes.get()).isLessThan((int) (s.matched() / 50));
    }

    @Test
    void aNewSearchCancelsTheOldOneAndNeverMixesResults() throws Exception {
        Path file = dir.resolve("gen.log");
        LogGenerator.Stats stats = LogGenerator.plain(file, 3_000_000, 22, false, false);
        ScanController c = controller(file, LogFormat.PLAIN, () -> 1_000_000);
        CompiledQuery all = query(Query.matchAll(), LogFormat.PLAIN);
        CompiledQuery m786 = query(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))), LogFormat.PLAIN);
        onUi(() -> {
            c.start(all);
            return null;
        });
        Thread.sleep(20);
        onUi(() -> {
            c.start(m786);
            return null;
        });
        ScanSummary s = awaitFinished(c);
        assertThat(s.matched()).isEqualTo(stats.count("786"));
        assertThat(onUi(() -> c.hits().size())).isEqualTo((int) stats.count("786"));
        assertThat(onUi(() -> c.hits().stream().allMatch(h -> h.lineNumber() > 0))).isTrue();
        assertThat(c.generation()).isEqualTo(2);
    }

    @Test
    void capIsReportedForTheBanner() throws Exception {
        ScanController c = controller(Fixtures.path("plain/basic.log"), LogFormat.PLAIN, () -> 2);
        onUi(() -> {
            c.start(query(Query.matchAll(), LogFormat.PLAIN));
            return null;
        });
        ScanSummary s = awaitFinished(c);
        assertThat(s.capped()).isTrue();
        assertThat(onUi(() -> c.hits().size())).isEqualTo(2);
        assertThat(StatusText.summary(s, c.resultCap())).contains("results capped at 2");
    }

    @Test
    void cancelStopsALongRegexScan() throws Exception {
        Path file = dir.resolve("evil.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - ").append("a".repeat(80)).append('\n');
        }
        Files.writeString(file, sb);
        ScanController c = controller(file, LogFormat.PLAIN, () -> 10_000);
        onUi(() -> {
            c.start(query(Query.matchAll().withText(TextQuery.regex("(.*a){12}x")), LogFormat.PLAIN));
            return null;
        });
        Thread.sleep(300);
        long t0 = System.nanoTime();
        onUi(() -> {
            c.cancel();
            return null;
        });
        ScanSummary s = awaitFinished(c);
        assertThat(s.cancelled()).isTrue();
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2000);
        assertThat(StatusText.summary(s, c.resultCap())).contains("cancelled");
    }

    @Test
    void startFailureIsShownAsAnError() throws Exception {
        ScanController c = controller(dir.resolve("gone.log"), LogFormat.PLAIN, () -> 10);
        onUi(() -> {
            c.start(query(Query.matchAll(), LogFormat.PLAIN));
            return null;
        });
        assertThat(onUi(() -> c.errorProperty().get())).contains("gone.log");
        assertThat(onUi(() -> c.runningProperty().get())).isFalse();
    }
}
