package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.format.json.JsonEntryParser;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.scan.ScanHandle;
import id.project.logs.simplelogparser.scan.ScanListener;
import id.project.logs.simplelogparser.scan.ScanRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static id.project.logs.simplelogparser.cli.CliHarness.run;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * CLI output and exit codes: format errors (AC1), invalid regex before any scan (AC7), exit codes and
 * --count (AC16), byte-identical raw output without ANSI codes (AC17), --json (AC18), malformed JSON
 * lines (AC22), --max-count (AC14) and multi-file output.
 */
class CliOutputIT {

    private static final Path BASIC = Fixtures.path("plain/basic.log");
    private static final Path CRLF = Fixtures.path("plain/crlf.log");
    private static final Path JSON = Fixtures.path("json/basic.log");

    @TempDir
    Path dir;

    /** Bytes of the given 1-based line ranges {from, to} of a file, concatenated. */
    private static byte[] linesOf(Path file, int[]... ranges) throws IOException {
        byte[] all = Files.readAllBytes(file);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int[] r : ranges) {
            int line = 1;
            int start = -1;
            for (int i = 0; i <= all.length; i++) {
                if (line == r[0] && start < 0) {
                    start = i;
                }
                if (i < all.length && all[i] == '\n') {
                    line++;
                    if (line == r[1] + 1) {
                        out.write(all, start, i + 1 - start);
                        break;
                    }
                }
            }
        }
        return out.toByteArray();
    }

    @Test
    void formatDetectionFailuresExitTwo() throws IOException {
        CliHarness.Run neither = run("--text", "x", Fixtures.path("plain/neither.txt").toString());
        assertThat(neither.code()).isEqualTo(ExitCode.ERROR);
        assertThat(neither.stderr()).contains("unrecognized log format");
        Path empty = Files.createFile(dir.resolve("empty.log"));
        assertThat(run(empty.toString()).stderr()).contains("file is empty");
        assertThat(run("--format", "plain", JSON.toString()).stderr()).contains("no valid log entries");
        assertThat(run("--encoding", "UTF-16", BASIC.toString()).code()).isEqualTo(ExitCode.ERROR);
        assertThat(run("--format", "xml", BASIC.toString()).code()).isEqualTo(ExitCode.ERROR);
        CliHarness.Run missing = run(dir.resolve("missing.log").toString());
        assertThat(missing.code()).isEqualTo(ExitCode.ERROR);
        assertThat(missing.stderr()).contains("no such file").doesNotContain("Exception");
        assertThat(run(dir.toString()).stderr()).contains("is a directory");
    }

    @Test
    void invalidRegexFailsBeforeAnyScanStarts() {
        AtomicBoolean started = new AtomicBoolean();
        ScanEngine spy = new ScanEngine() {
            @Override
            public ScanHandle start(ScanRequest request, ScanListener listener) throws IOException {
                started.set(true);
                return super.start(request, listener);
            }
        };
        CliHarness.Run r = CliHarness.run(false, spy, "--regex", "status\":(5\\d\\d", BASIC.toString());
        assertThat(r.code()).isEqualTo(ExitCode.ERROR);
        assertThat(r.stderr()).contains("invalid regex").contains("index");
        assertThat(started).isFalse();
        CliHarness.Run field = CliHarness.run(false, spy, "--field", "merchantId~=[", BASIC.toString());
        assertThat(field.code()).isEqualTo(ExitCode.ERROR);
        assertThat(started).isFalse();
        assertThat(CliHarness.run(false, spy, "--field", "merchantId=786", BASIC.toString()).code()).isZero();
        assertThat(started).isTrue();
    }

    @Test
    void exitCodesAndCount() {
        assertThat(run("--field", "merchantId=786", BASIC.toString()).code()).isEqualTo(ExitCode.MATCH);
        assertThat(run("--field", "merchantId=1", BASIC.toString()).code()).isEqualTo(ExitCode.NO_MATCH);
        CliHarness.Run count = run("--count", "--field", "merchantId=786", BASIC.toString());
        assertThat(count.out()).isEqualTo("3\n");
        assertThat(run("--count", "--field", "merchantId=1", BASIC.toString()).out()).isEqualTo("0\n");
        String missing = dir.resolve("nope.log").toString();
        assertThat(run("--field", "merchantId=786", BASIC.toString(), missing).code()).isEqualTo(ExitCode.MATCH);
        assertThat(run("--field", "merchantId=1", BASIC.toString(), missing).code()).isEqualTo(ExitCode.ERROR);
        assertThat(run("--field", "merchantId=1", BASIC.toString(), CRLF.toString()).code()).isEqualTo(ExitCode.NO_MATCH);
        assertThat(run("--text", "a", "--regex", "b", BASIC.toString()).code()).isEqualTo(ExitCode.ERROR);
        assertThat(run("--no-such-option", BASIC.toString()).code()).isEqualTo(ExitCode.ERROR);
        CliHarness.Run help = run("--help");
        assertThat(help.code()).isZero();
        assertThat(help.out()).contains("--field").contains("Asia/Jakarta");
    }

    @Test
    void rawOutputIsByteIdenticalForLfAndCrlf() throws IOException {
        int[][] entries = {{1, 9}, {20, 24}, {26, 26}};
        CliHarness.Run lf = run("--field", "merchantId=786", BASIC.toString());
        assertThat(lf.stdout()).isEqualTo(linesOf(BASIC, entries));
        CliHarness.Run crlf = run("--field", "merchantId=786", CRLF.toString());
        assertThat(crlf.stdout()).isEqualTo(linesOf(CRLF, entries));
        assertThat(new String(crlf.stdout(), StandardCharsets.ISO_8859_1)).contains("\r\n").doesNotContain("\u001b");
    }

    @Test
    void ansiHighlightingOnlyOnTerminalsAndNeverWithNoColor() {
        String[] args = {"--text", "orderId=2837537", BASIC.toString()};
        assertThat(CliHarness.run(false, new ScanEngine(), args).out()).doesNotContain("\u001b");
        CliHarness.Run color = CliHarness.run(true, new ScanEngine(), args);
        if (System.getenv("NO_COLOR") == null) {
            assertThat(color.out()).contains(RawPrinter.HIGHLIGHT_ON + "orderId=2837537" + RawPrinter.HIGHLIGHT_OFF);
        }
        assertThat(CliHarness.run(true, new ScanEngine(), "--no-color", "--text", "orderId=2837537", BASIC.toString())
                .out()).doesNotContain("\u001b");
    }

    @Test
    void rawOutputPreservesInvalidBytesAndAddsNewlineAtEof() throws IOException {
        Path bad = Fixtures.path("plain/invalid-utf8.log");
        assertThat(run("--text", "BADBYTES", bad.toString()).stdout()).isEqualTo(linesOf(bad, new int[]{1, 2}));
        Path mixed = Fixtures.path("plain/mixed-endings.log");
        byte[] all = Files.readAllBytes(mixed);
        CliHarness.Run last = run("--field", "merchantId=33", mixed.toString());
        String expected = new String(all, StandardCharsets.UTF_8).substring(
                new String(all, StandardCharsets.UTF_8).indexOf("[m3:")) + "\n";
        assertThat(last.out()).isEqualTo(expected);
    }

    @Test
    void multipleFilesArePrefixedUnlessNoFilename() {
        CliHarness.Run r = run("--field", "merchantId=786", BASIC.toString(), CRLF.toString());
        assertThat(r.outLines()).hasSize(30).allMatch(l -> l.startsWith(BASIC + ":") || l.startsWith(CRLF + ":"));
        CliHarness.Run h = run("-h", "--field", "merchantId=786", BASIC.toString(), CRLF.toString());
        assertThat(h.outLines()).hasSize(30).noneMatch(l -> l.startsWith(BASIC.toString()));
        CliHarness.Run count = run("--count", "--field", "merchantId=786", BASIC.toString(), JSON.toString());
        assertThat(count.outLines()).containsExactly(BASIC + ":3", JSON + ":0");
    }

    @Test
    void jsonOutputHasOneObjectPerHitWithOriginalText() throws Exception {
        CliHarness.Run r = run("--json", "--field", "merchantId=786", CRLF.toString());
        List<String> lines = r.outLines();
        assertThat(lines).hasSize(3);
        String original = new String(Files.readAllBytes(CRLF), StandardCharsets.UTF_8);
        for (String line : lines) {
            Map<String, String> obj = JsonEntryParser.parse(line);
            assertThat(obj).containsKeys("file", "offset", "line", "entry", "truncated");
            int offset = Integer.parseInt(obj.get("offset"));
            String entry = obj.get("entry");
            assertThat(original.substring(offset)).startsWith(entry);
            assertThat(obj.get("truncated")).isEqualTo("false");
        }
        assertThat(JsonEntryParser.parse(lines.get(0)).get("entry"))
                .isEqualTo(new String(linesOf(CRLF, new int[]{1, 9}), StandardCharsets.UTF_8).stripTrailing());
        assertThat(JsonEntryParser.parse(lines.get(1)).get("line")).isEqualTo("20");
    }

    @Test
    void malformedJsonLinesAreSkippedAndCounted() {
        CliHarness.Run r = run("--json", JSON.toString());
        assertThat(r.code()).isEqualTo(ExitCode.MATCH);
        assertThat(r.outLines()).hasSize(7);
        assertThat(r.out()).contains("\"line\":1,").contains("\"line\":10,");
        assertThat(r.stderr()).contains("2 malformed lines skipped");
        CliHarness.Run strict = run("--strict", "--field", "shiftId=4891", JSON.toString());
        assertThat(strict.code()).isEqualTo(ExitCode.ERROR);
        assertThat(strict.stderr()).contains("malformed line at byte offset");
    }

    @Test
    void maxCountCapsInFileOrderAndSaysSo() {
        CliHarness.Run r = run("--json", "-m", "2", "--field", "merchantId=786", BASIC.toString());
        assertThat(r.outLines()).hasSize(2);
        assertThat(r.out()).contains("\"line\":1,").contains("\"line\":20,");
        assertThat(r.stderr()).contains("results capped at 2");
        CliHarness.Run count = run("--count", "--max-count", "1", "--field", "merchantId=786", BASIC.toString());
        assertThat(count.out()).isEqualTo("1\n");
        assertThat(run("-m", "0", "--field", "merchantId=786", BASIC.toString()).code()).isEqualTo(ExitCode.NO_MATCH);
    }

    @Test
    void tinyChunksAndThreadsGiveTheSameOutput() {
        CliHarness.Run normal = run("--field", "merchantId=786", BASIC.toString());
        CliHarness.Run tiny = run("--chunk-size", "3", "--threads", "4", "--field", "merchantId=786", BASIC.toString());
        assertThat(tiny.stdout()).isEqualTo(normal.stdout());
    }
}
