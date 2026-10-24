package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.Scope;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.MatchMode;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.TextQuery;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC12, edge #1, #2: the parallel chunked scan returns exactly the reference scanner's hits (offset, length,
 * line) for every chunk size down to one byte, with and without the byte prefilter.
 */
class ScanEquivalenceTest {

    private static final long[] CHUNK_SIZES = {1, 2, 3, 7, 64, 4096, 8L * 1024 * 1024};
    private static final int[] THREADS = {1, 4};

    static List<Query> queries() {
        return List.of(
                Query.matchAll(),
                Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))),
                Query.matchAll().withFields(List.of(new FieldFilter("shiftId", MatchMode.CONTAINS, List.of("4891", "1475")))),
                Query.matchAll().withText(TextQuery.literal("orderId=2837537")),
                Query.matchAll().withText(TextQuery.literal("café")),
                Query.matchAll().withText(TextQuery.regex("SPANSTART.*\\n.*SPANEND|\"status\":\\s*5\\d\\d")),
                Query.matchAll().withText(TextQuery.literal("DUPREQ2").withScope(Scope.RESPONSE)),
                Query.matchAll().withLevels(Set.of(Level.ERROR, Level.WARN)));
    }

    static Stream<Arguments> fixtures() {
        return Stream.of(
                Arguments.of("plain/basic.log", LogFormat.PLAIN),
                Arguments.of("plain/crlf.log", LogFormat.PLAIN),
                Arguments.of("plain/edge.log", LogFormat.PLAIN),
                Arguments.of("plain/bom.log", LogFormat.PLAIN),
                Arguments.of("plain/invalid-utf8.log", LogFormat.PLAIN),
                Arguments.of("plain/mixed-endings.log", LogFormat.PLAIN),
                Arguments.of("json/basic.log", LogFormat.JSON));
    }

    @ParameterizedTest
    @MethodSource("fixtures")
    void fixturesMatchReferenceForAllChunkSizes(String fixture, LogFormat format) throws Exception {
        assertEquivalent(Fixtures.path(fixture), format);
    }

    @Test
    void seededGeneratedPlainFilesMatchReference(@TempDir Path dir) throws Exception {
        for (long seed : new long[]{1, 42, 20261018}) {
            Path file = dir.resolve("gen-" + seed + ".log");
            LogGenerator.plain(file, 12_000, seed, seed % 2 == 0, seed == 1);
            assertEquivalent(file, LogFormat.PLAIN);
        }
    }

    @Test
    void seededGeneratedJsonFilesMatchReference(@TempDir Path dir) throws Exception {
        for (long seed : new long[]{3, 99}) {
            Path file = dir.resolve("gen-" + seed + ".json");
            LogGenerator.json(file, 12_000, seed);
            assertEquivalent(file, LogFormat.JSON);
        }
    }

    @Test
    void largerGeneratedFileWithRealisticChunkSizes(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("big.log");
        LogGenerator.Stats stats = LogGenerator.plain(file, 3_000_000, 7, false, false);
        CompiledQuery q = Fixtures.compile(Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))))
                .forFormat(LogFormat.PLAIN);
        List<ReferenceScanner.Ref> expected = ReferenceScanner.scan(file, LogFormat.PLAIN, q);
        assertThat(expected).hasSize((int) stats.count("786"));
        for (long chunk : new long[]{4096, 65_536, 1_000_003}) {
            assertThat(refs(Fixtures.scan(ScanRequest.of(file, LogFormat.PLAIN, q).withChunkSize(chunk).withThreads(4))))
                    .as("chunk %d", chunk).isEqualTo(expected);
        }
    }

    private static void assertEquivalent(Path file, LogFormat format) throws Exception {
        for (Query query : queries()) {
            CompiledQuery q = Fixtures.compile(query).forFormat(format);
            List<ReferenceScanner.Ref> expected = ReferenceScanner.scan(file, format, q);
            for (long chunk : CHUNK_SIZES) {
                for (int threads : THREADS) {
                    ScanRequest req = ScanRequest.of(file, format, q).withChunkSize(chunk).withThreads(threads);
                    assertThat(refs(Fixtures.scan(req)))
                            .as("%s %s chunk=%d threads=%d", file.getFileName(), query, chunk, threads)
                            .isEqualTo(expected);
                    if (q.hasPrefilter()) {
                        assertThat(refs(Fixtures.scan(req.withQuery(q.withoutPrefilter()))))
                                .as("%s %s chunk=%d threads=%d without prefilter", file.getFileName(), query, chunk, threads)
                                .isEqualTo(expected);
                    }
                }
            }
        }
    }

    private static List<ReferenceScanner.Ref> refs(Fixtures.Result r) {
        return r.hits().stream().map(h -> new ReferenceScanner.Ref(h.offset(), h.byteLength(), h.lineNumber())).toList();
    }
}
