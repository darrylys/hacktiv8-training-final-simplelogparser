package id.project.logs.simplelogparser.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Line reader, entry buffer, decoder and byte search. */
class IoTest {

    @TempDir
    Path dir;

    private record L(long offset, String text, int term, boolean cont) {
    }

    private List<L> lines(byte[] content, int bufferSize, long start, long limit) throws Exception {
        Path f = dir.resolve("f.log");
        Files.write(f, content);
        List<L> out = new ArrayList<>();
        try (LogFile lf = LogFile.open(f)) {
            ByteLineReader r = new ByteLineReader(lf, bufferSize);
            r.reset(start, limit < 0 ? lf.sizeAtOpen() : limit);
            ByteLine l;
            while ((l = r.next()) != null) {
                out.add(new L(l.offset(), new String(l.bytes(), l.start(), l.length(), StandardCharsets.ISO_8859_1),
                        l.terminatorLength(), l.continuation()));
            }
        }
        return out;
    }

    @Test
    void readsLinesWithOffsetsAndTerminators() throws Exception {
        assertThat(lines("a\nbb\r\n\nccc".getBytes(), 1024, 0, -1)).containsExactly(
                new L(0, "a\n", 1, false),
                new L(2, "bb\r\n", 2, false),
                new L(6, "\n", 1, false),
                new L(7, "ccc", 0, false));
    }

    @Test
    void longLinesComeInPiecesWithoutGrowingTheBuffer() throws Exception {
        String longLine = "x".repeat(50);
        List<L> out = lines((longLine + "\nend\n").getBytes(), 16, 0, -1);
        assertThat(out.stream().map(L::text).reduce("", String::concat)).isEqualTo(longLine + "\nend\n");
        assertThat(out.get(0).cont()).isFalse();
        assertThat(out.get(1).cont()).isTrue();
        assertThat(out.get(out.size() - 1)).isEqualTo(new L(51, "end\n", 1, false));
        assertThat(out).allMatch(l -> l.text().length() <= 16);
    }

    @Test
    void neverReadsAtOrBeyondTheLimit() throws Exception {
        assertThat(lines("abc\ndef\nghi\n".getBytes(), 1024, 2, 6)).containsExactly(
                new L(2, "c\n", 1, false),
                new L(4, "de", 0, false));
    }

    @Test
    void entryBufferTruncatesAtTheLimitButCountsAllBytes() {
        EntryBuffer b = new EntryBuffer(10);
        byte[] data = "0123456789abcdef".getBytes();
        b.append(data, 0, 6);
        b.append(data, 6, 10);
        assertThat(b.size()).isEqualTo(10);
        assertThat(b.totalLength()).isEqualTo(16);
        assertThat(b.truncated()).isTrue();
        assertThat(new String(b.array(), 0, b.size())).isEqualTo("0123456789");
        b.clear();
        assertThat(b.isEmpty()).isTrue();
        assertThat(b.truncated()).isFalse();
    }

    @Test
    void decoderReplacesInvalidBytesAndStripsBomOnlyAtOffsetZero() {
        TextDecoder d = new TextDecoder(StandardCharsets.UTF_8);
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, 'h', 'i'};
        assertThat(d.decode(bom, 0, bom.length, 0)).isEqualTo("hi");
        assertThat(d.decode(bom, 0, bom.length, 100)).isEqualTo("﻿hi");
        byte[] bad = {'a', (byte) 0xFF, 'b'};
        assertThat(d.decode(bad, 0, bad.length, 5)).isEqualTo("a�b");
    }

    @Test
    void matchTextNormalizesAllLineEndings() {
        assertThat(TextDecoder.normalizeLineEndings("a\r\nb\rc\nd")).isEqualTo("a\nb\nc\nd");
        assertThat(TextDecoder.normalizeLineEndings("plain")).isSameAs("plain");
    }

    @Test
    void onlyAsciiCompatibleEncodingsAreAccepted() {
        assertThat(TextDecoder.asciiCompatible("UTF-8")).isEqualTo(StandardCharsets.UTF_8);
        assertThat(TextDecoder.asciiCompatible("windows-1252").name()).isEqualTo("windows-1252");
        assertThat(TextDecoder.asciiCompatible("ISO-8859-1")).isEqualTo(StandardCharsets.ISO_8859_1);
        assertThatThrownBy(() -> TextDecoder.asciiCompatible("UTF-16")).isInstanceOf(UnsupportedCharsetException.class);
        assertThatThrownBy(() -> TextDecoder.asciiCompatible("no-such-charset")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void byteSearchFoldsAsciiCaseOnlyWhenAsked() {
        byte[] hay = "Hello World".getBytes();
        assertThat(ByteSearch.contains(hay, 0, hay.length, "world".getBytes(), true)).isTrue();
        assertThat(ByteSearch.contains(hay, 0, hay.length, "world".getBytes(), false)).isFalse();
        assertThat(ByteSearch.indexOf(hay, 0, hay.length, "World".getBytes(), false)).isEqualTo(6);
        assertThat(ByteSearch.contains(hay, 0, 5, "World".getBytes(), false)).isFalse();
        assertThat(ByteSearch.hasNonAscii("café".getBytes(StandardCharsets.UTF_8), 0, 5)).isTrue();
    }
}
