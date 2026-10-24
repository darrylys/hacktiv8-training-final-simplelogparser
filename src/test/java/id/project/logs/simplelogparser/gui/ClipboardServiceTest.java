package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.scan.EntryReader;
import id.project.logs.simplelogparser.scan.ScanException;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** AC20, AC21, A17, A18: clipboard text is the original text, and large copies ask first. */
class ClipboardServiceTest {

    private final List<String> clipboard = new ArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();

    private ClipboardService service(LogFile file, LogFormat format, long threshold) {
        EntryReader reader = new EntryReader(file, format, StandardCharsets.UTF_8, ParseContext.defaults(), 1 << 20);
        return new ClipboardService(hit -> {
            reads.incrementAndGet();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            reader.copyTo(hit, out);
            return out.toByteArray();
        }, StandardCharsets.UTF_8, clipboard::add, Runnable::run, UiThread.direct(), threshold);
    }

    private static List<Hit> hits(Path file, LogFormat format, Query q) throws Exception {
        return Fixtures.scan(file, format, q).hits();
    }

    /** Text of 1-based lines {@code from..to}, line endings included. */
    private static String lines(Path file, int from, int to) throws Exception {
        String all = Files.readString(file, StandardCharsets.UTF_8);
        String[] parts = all.split("(?<=\n)");
        StringBuilder sb = new StringBuilder();
        for (int i = from - 1; i < to; i++) {
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    @Test
    void singleEntryIsCopiedExactlyWithCrlf() throws Exception {
        Path crlf = Fixtures.path("plain/crlf.log");
        List<Hit> found = hits(crlf, LogFormat.PLAIN, Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786"))));
        try (LogFile f = LogFile.open(crlf)) {
            assertThat(service(f, LogFormat.PLAIN, Long.MAX_VALUE).copyEntries(List.of(found.get(0))).get()).isTrue();
        }
        assertThat(clipboard).containsExactly(lines(crlf, 1, 9));
        assertThat(clipboard.get(0)).contains("\r\n");
    }

    @Test
    void multipleEntriesAreJoinedInFileOrderWithOneBlankLine() throws Exception {
        Path crlf = Fixtures.path("plain/crlf.log");
        List<Hit> found = new ArrayList<>(hits(crlf, LogFormat.PLAIN,
                Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "786")))));
        java.util.Collections.reverse(found);
        try (LogFile f = LogFile.open(crlf)) {
            service(f, LogFormat.PLAIN, Long.MAX_VALUE).copyEntries(found).get();
        }
        assertThat(clipboard).containsExactly(lines(crlf, 1, 9) + "\r\n" + lines(crlf, 20, 24) + "\r\n" + lines(crlf, 26, 26));
    }

    @Test
    void nonAsciiAndLastEntryWithoutNewlineArePreserved() throws Exception {
        Path edge = Fixtures.path("plain/edge.log");
        List<Hit> unicode = hits(edge, LogFormat.PLAIN, Query.matchAll().withFields(List.of(FieldFilter.exact("merchantId", "500"))));
        try (LogFile f = LogFile.open(edge)) {
            service(f, LogFormat.PLAIN, Long.MAX_VALUE).copyEntries(unicode).get();
        }
        assertThat(clipboard.get(0)).isEqualTo(lines(edge, 20, 20)).contains("Z\u00fcrich").contains("\u65e5\u672c\u8a9e");

        Path mixed = Fixtures.path("plain/mixed-endings.log");
        List<Hit> two = hits(mixed, LogFormat.PLAIN, Query.matchAll().withFields(List.of(new FieldFilter("merchantId",
                id.project.logs.simplelogparser.query.MatchMode.EXACT, List.of("32", "33")))));
        try (LogFile f = LogFile.open(mixed)) {
            service(f, LogFormat.PLAIN, Long.MAX_VALUE).copyEntries(two).get();
        }
        String all = Files.readString(mixed);
        String m2 = all.substring(all.indexOf("[m2:"), all.indexOf("[m3:"));
        String m3 = all.substring(all.indexOf("[m3:"));
        // first entry ends with LF, so the separator is one more LF
        assertThat(clipboard.get(1)).isEqualTo(m2 + "\n" + m3);
    }

    @Test
    void copyAllAsksBeforeLargeCopiesAndReadsNothingIfDeclined() throws Exception {
        Path basic = Fixtures.path("plain/basic.log");
        List<Hit> all = hits(basic, LogFormat.PLAIN, Query.matchAll());
        long total = all.stream().mapToLong(Hit::byteLength).sum();
        List<Long> asked = new ArrayList<>();
        try (LogFile f = LogFile.open(basic)) {
            ClipboardService small = service(f, LogFormat.PLAIN, 100);
            assertThat(small.copyAll(all, bytes -> {
                asked.add(bytes);
                return false;
            }).get()).isFalse();
            assertThat(asked).containsExactly(total);
            assertThat(reads).hasValue(0);
            assertThat(clipboard).isEmpty();

            assertThat(small.copyAll(all, bytes -> true).get()).isTrue();
            assertThat(reads).hasValue(all.size());
            assertThat(clipboard).hasSize(1);

            asked.clear();
            ClipboardService large = service(f, LogFormat.PLAIN, ClipboardService.DEFAULT_THRESHOLD);
            large.copyAll(all, bytes -> {
                asked.add(bytes);
                return false;
            }).get();
            assertThat(asked).as("under the threshold nobody is asked").isEmpty();
            assertThat(clipboard).hasSize(2);
        }
    }

    @Test
    void detailSelectionIsCopiedAsDisplayed() throws Exception {
        try (LogFile f = LogFile.open(Fixtures.path("plain/basic.log"))) {
            ClipboardService s = service(f, LogFormat.PLAIN, Long.MAX_VALUE);
            s.copySelection("line one\nline two");
            s.copySelection("");
        }
        assertThat(clipboard).containsExactly("line one\nline two");
    }

    @Test
    void statusAndErrorTextsAreReadable() {
        assertThat(StatusText.hits(1)).isEqualTo("1 hit");
        assertThat(StatusText.hits(1204)).isEqualTo("1,204 hits");
        assertThat(ErrorDialogs.messageFor(new NoSuchFileException("C:\\x.log"))).isEqualTo("File not found: C:\\x.log");
        assertThat(ErrorDialogs.messageFor(new id.project.logs.simplelogparser.format.FormatDetectionException("file is empty")))
                .contains("file is empty");
        assertThat(ErrorDialogs.messageFor(new ScanException("I/O error while scanning x", null))).isEqualTo("I/O error while scanning x");
        assertThat(ErrorDialogs.messageFor(new IllegalStateException("boom"))).doesNotContain("\tat ");
    }
}
