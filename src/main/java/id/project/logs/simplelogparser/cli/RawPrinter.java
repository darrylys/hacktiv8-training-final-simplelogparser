package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.Highlighter;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.query.MatchTimeoutException;
import id.project.logs.simplelogparser.scan.EntryReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.util.List;

/**
 * Copies each entry's original bytes to stdout unchanged (AC17). With several files every output line is
 * prefixed with {@code path:} (A15). With color on, the entry is decoded, ANSI SGR codes are inserted
 * around highlight ranges, and it is re-encoded. An entry that does not end with a newline (last line of
 * a file) gets one, so entries never run together.
 */
public final class RawPrinter implements HitPrinter {

    static final String HIGHLIGHT_ON = "\u001b[1;31m";
    static final String HIGHLIGHT_OFF = "\u001b[0m";

    private final OutputStream out;
    private final byte[] prefix;
    private final boolean color;
    private final CompiledQuery query;
    private final Charset charset;
    private final MatchBudget budget;

    public RawPrinter(OutputStream out, String prefix, boolean color, CompiledQuery query, Charset charset,
                      MatchBudget budget) {
        this.out = out;
        this.prefix = prefix == null ? null : prefix.getBytes(charset);
        this.color = color;
        this.query = query;
        this.charset = charset;
        this.budget = budget;
    }

    @Override
    public void print(Hit hit, EntryReader reader) throws IOException {
        OutputStream target = prefix == null ? out : new LinePrefixer(out, prefix);
        if (color) {
            byte[] bytes = colored(reader.read(hit)).getBytes(charset);
            target.write(bytes);
            if (bytes.length == 0 || bytes[bytes.length - 1] != '\n') {
                out.write('\n');
            }
        } else {
            reader.copyTo(hit, target);
            if (reader.lastByte(hit) != '\n') {
                out.write('\n');
            }
        }
    }

    private String colored(LogEntry entry) {
        String raw = entry.rawText();
        List<Highlighter.Range> ranges;
        try {
            ranges = Highlighter.toRawRanges(entry, Highlighter.ranges(entry, query, budget));
        } catch (MatchTimeoutException e) {
            ranges = List.of();
        }
        StringBuilder sb = new StringBuilder(raw.length() + ranges.size() * 12 + 16);
        int pos = 0;
        for (Highlighter.Range r : ranges) {
            if (r.start() < pos) {
                continue;
            }
            sb.append(raw, pos, r.start()).append(HIGHLIGHT_ON).append(raw, r.start(), r.end()).append(HIGHLIGHT_OFF);
            pos = r.end();
        }
        sb.append(raw, pos, raw.length());
        if (entry.truncated()) {
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append("[truncated]\n");
        }
        return sb.toString();
    }

    /** Writes {@code prefix} before the first byte of every line. */
    private static final class LinePrefixer extends OutputStream {
        private final OutputStream out;
        private final byte[] prefix;
        private boolean atLineStart = true;

        LinePrefixer(OutputStream out, byte[] prefix) {
            this.out = out;
            this.prefix = prefix;
        }

        @Override
        public void write(int b) throws IOException {
            if (atLineStart) {
                out.write(prefix);
                atLineStart = false;
            }
            out.write(b);
            atLineStart = b == '\n';
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            int start = off;
            int end = off + len;
            for (int i = off; i < end; i++) {
                if (atLineStart) {
                    out.write(b, start, i - start);
                    out.write(prefix);
                    start = i;
                    atLineStart = false;
                }
                if (b[i] == '\n') {
                    atLineStart = true;
                }
            }
            out.write(b, start, end - start);
        }
    }
}
