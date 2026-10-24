package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.HeaderlessEntry;
import id.project.logs.simplelogparser.format.MalformedEntryException;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.ByteLine;
import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.query.MatchTimeoutException;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Single-threaded, straightforward oracle for the equivalence tests (AC12): reads the whole file, splits it
 * into lines, frames entries with the format's own entry-start rule, and evaluates the query without the
 * byte prefilter. Test code only; small files only.
 */
public final class ReferenceScanner {

    /** The comparable part of a hit. */
    public record Ref(long offset, long length, long line) {
    }

    private ReferenceScanner() {
    }

    public static List<Ref> scan(Path file, LogFormat format, CompiledQuery query) throws IOException {
        return scan(file, format, query, StandardCharsets.UTF_8, ParseContext.defaults());
    }

    public static List<Ref> scan(Path file, LogFormat format, CompiledQuery query, Charset charset,
                                 ParseContext ctx) throws IOException {
        byte[] all = Files.readAllBytes(file);
        FormatHandler handler = FormatHandler.of(format, charset);
        CompiledQuery q = query.forFormat(format).withoutPrefilter();
        TextDecoder decoder = new TextDecoder(charset);
        MatchBudget budget = MatchBudget.unlimited();

        // line starts and newline counts
        List<long[]> lines = new ArrayList<>(); // {start, end}
        int s = 0;
        for (int i = 0; i < all.length; i++) {
            if (all[i] == '\n') {
                lines.add(new long[]{s, i + 1});
                s = i + 1;
            }
        }
        if (s < all.length) {
            lines.add(new long[]{s, all.length});
        }

        List<Ref> out = new ArrayList<>();
        long entryStart = -1;
        long entryLine = -1;
        boolean headerless = false;
        long entryEnd = -1;
        boolean seenEntryStart = false;
        for (int li = 0; li < lines.size(); li++) {
            long[] l = lines.get(li);
            ByteLine bl = toByteLine(all, l);
            if (handler.isEntryStart(bl)) {
                seenEntryStart = true;
                emit(all, entryStart, entryEnd, entryLine, headerless, handler, q, ctx, decoder, budget, out);
                entryStart = l[0];
                entryEnd = l[1];
                entryLine = li + 1;
                headerless = false;
            } else if (handler.isSkippable(bl)) {
                emit(all, entryStart, entryEnd, entryLine, headerless, handler, q, ctx, decoder, budget, out);
                entryStart = -1;
            } else if (entryStart >= 0) {
                entryEnd = l[1];
            } else if (format == LogFormat.PLAIN && !seenEntryStart) {
                // lines before the first header form one headerless entry
                entryStart = l[0];
                entryEnd = l[1];
                entryLine = li + 1;
                headerless = true;
            }
        }
        emit(all, entryStart, entryEnd, entryLine, headerless, handler, q, ctx, decoder, budget, out);
        return out;
    }

    private static ByteLine toByteLine(byte[] all, long[] l) {
        int start = (int) l[0];
        int len = (int) (l[1] - l[0]);
        int term = 0;
        if (len > 0 && all[start + len - 1] == '\n') {
            term = (len > 1 && all[start + len - 2] == '\r') ? 2 : 1;
        }
        return new ByteLine(l[0], all, start, len, term, false);
    }

    private static void emit(byte[] all, long start, long end, long line, boolean headerless, FormatHandler handler,
                             CompiledQuery q, ParseContext ctx, TextDecoder decoder, MatchBudget budget,
                             List<Ref> out) {
        if (start < 0) {
            return;
        }
        byte[] bytes = Arrays.copyOfRange(all, (int) start, (int) end);
        if (headerless && new String(bytes, StandardCharsets.ISO_8859_1).isBlank()) {
            return;
        }
        RawEntry raw = new RawEntry(start, bytes.length, bytes, bytes.length, false, headerless);
        LogEntry entry;
        try {
            entry = headerless ? new HeaderlessEntry(raw, decoder) : handler.parse(raw, ctx);
        } catch (MalformedEntryException e) {
            return;
        }
        try {
            if (q.matches(entry, budget)) {
                out.add(new Ref(start, bytes.length, line));
            }
        } catch (MatchTimeoutException e) {
            // counted by the engine; the oracle simply skips it
        }
    }
}
