package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.TextQuery;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * AC11 (run with {@code -Pperf}, which forks with {@code -Xmx512m}): a field search over a generated 5 GB
 * plain log completes without {@code OutOfMemoryError}, and peak heap does not grow with file size (compared
 * with a 1 GB run). Throughput is reported, not asserted (A27).
 */
@Tag("perf")
class ScalePerfTest {

    private static final long GB = 1024L * 1024 * 1024;
    private static final long MB = 1024L * 1024;

    @TempDir
    Path dir;

    @Test
    void fiveGigabyteScanHasFlatHeap() throws Exception {
        long free = Files.getFileStore(dir).getUsableSpace();
        assumeTrue(free > 6 * GB + 6 * GB / 5, "needs about 7.2 GB free in the temp directory, has " + free / MB + " MB");
        System.out.printf("max heap %d MB, %d cores%n", Runtime.getRuntime().maxMemory() / MB,
                Runtime.getRuntime().availableProcessors());

        Path small = dir.resolve("1g.log");
        LogGenerator.Stats smallStats = LogGenerator.plain(small, GB, 1, false, false);
        long peakSmall = measure("1 GB field", small, fieldQuery(), smallStats.count("786"));
        Files.delete(small);

        Path big = dir.resolve("5g.log");
        LogGenerator.Stats bigStats = LogGenerator.plain(big, 5 * GB, 2, false, false);
        long peakBig = measure("5 GB field", big, fieldQuery(), bigStats.count("786"));
        measure("5 GB literal", big, Fixtures.compile(Query.matchAll().withText(TextQuery.literal("orderId=1234567"))), -1);
        measure("5 GB regex", big, Fixtures.compile(Query.matchAll().withText(TextQuery.regex("\"status\":5\\d\\d"))), -1);

        assertThat(peakBig).as("peak heap 5 GB vs 1 GB").isLessThanOrEqualTo(Math.max(peakSmall * 6 / 5, peakSmall + 64 * MB));
    }

    private static CompiledQuery fieldQuery() {
        return Fixtures.compile(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))));
    }

    /** Runs one scan and returns the peak heap used during it. */
    private static long measure(String label, Path file, CompiledQuery query, long expectedMatches) throws Exception {
        System.gc();
        List<MemoryPoolMXBean> heapPools = ManagementFactory.getMemoryPoolMXBeans().stream()
                .filter(p -> p.getType() == MemoryType.HEAP).toList();
        heapPools.forEach(MemoryPoolMXBean::resetPeakUsage);
        long size = Files.size(file);
        long[] count = {0};
        ScanSummary summary = new ScanEngine().run(ScanRequest.of(file, LogFormat.PLAIN, query.forFormat(LogFormat.PLAIN)),
                new ScanListener() {
                    @Override
                    public void onHits(List<id.project.logs.simplelogparser.model.Hit> hits) {
                        count[0] += hits.size();
                    }
                });
        long peak = heapPools.stream().mapToLong(p -> p.getPeakUsage().getUsed()).sum();
        double seconds = summary.elapsed().toNanos() / 1e9;
        System.out.printf("%-14s %6d MB in %6.1f s = %6.1f MB/s, %d matches, peak heap %d MB%n",
                label, size / MB, seconds, size / MB / seconds, summary.matched(), peak / MB);
        if (expectedMatches >= 0) {
            assertThat(summary.matched()).isEqualTo(expectedMatches);
        }
        assertThat(count[0]).isEqualTo(summary.matched());
        return peak;
    }
}
