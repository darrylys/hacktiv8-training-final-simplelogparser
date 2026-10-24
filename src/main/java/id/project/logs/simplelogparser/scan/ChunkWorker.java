package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.HeaderlessEntry;
import id.project.logs.simplelogparser.format.MalformedEntryException;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.ByteLine;
import id.project.logs.simplelogparser.io.ByteLineReader;
import id.project.logs.simplelogparser.io.EntryBuffer;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.HitSummary;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.query.CancelToken;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.query.MatchTimeoutException;
import id.project.logs.simplelogparser.query.ScanCancelledException;

import java.io.IOException;
import java.util.function.Consumer;

/**
 * Scans one chunk (TRD 5.1). A chunk owns every entry whose first byte lies in {@code [start, end)}; it
 * reads past {@code end} to finish its last entry and stops at the first entry start at or after
 * {@code end}, which the next chunk owns. This makes parallel results identical to a sequential scan.
 * <p>
 * One worker per thread; it reuses its line reader, entry buffer and match budget across chunks.
 */
final class ChunkWorker {

    private static final int CANCEL_CHECK_LINES = 4096;

    private final LogFile file;
    private final FormatHandler handler;
    private final CompiledQuery query;
    private final ParseContext ctx;
    private final CancelToken cancel;
    private final int resultCap;
    private final ByteLineReader reader;
    private final EntryBuffer buffer;
    private final TextDecoder decoder;
    private final MatchBudget budget;

    // per-chunk state
    private Consumer<Hit> sink;
    private long hits;
    private long entries;
    private long malformed;
    private long timeouts;
    private long truncated;
    private boolean cancelled;
    private long entryOffset;
    private long entryLine;
    private boolean entryHeaderless;

    ChunkWorker(LogFile file, ScanRequest request, FormatHandler handler, CancelToken cancel) {
        this.file = file;
        this.handler = handler;
        this.ctx = request.ctx();
        // under --strict every line must be parsed, so the prefilter may not skip any
        this.query = request.ctx().strict() ? request.query().withoutPrefilter() : request.query();
        this.cancel = cancel;
        this.resultCap = request.resultCap();
        this.reader = new ByteLineReader(file);
        this.buffer = new EntryBuffer(request.entryLimit());
        this.decoder = new TextDecoder(request.charset());
        this.budget = new MatchBudget(request.regexTimeout(), cancel);
    }

    ChunkResult scan(ChunkPlanner.Chunk chunk, Consumer<Hit> hitSink) throws IOException, StrictModeException {
        this.sink = hitSink;
        hits = entries = malformed = timeouts = truncated = 0;
        cancelled = false;

        long start = chunk.start();
        long end = chunk.end();
        long size = file.sizeAtOpen();
        boolean plainPrefix = start == 0 && handler.format() == LogFormat.PLAIN;

        ByteLine line;
        if (start == 0) {
            reader.reset(0, size);
        } else {
            // align: skip the rest of the line containing byte start-1
            reader.reset(start - 1, size);
            do {
                line = reader.next();
            } while (line != null && !line.endsWithNewline());
            if (line == null) {
                return result(0);
            }
        }

        boolean started = start == 0;
        boolean inEntry = false;
        long newlines = 0;
        long lineCount = 0;
        while ((line = reader.next()) != null) {
            if (++lineCount % CANCEL_CHECK_LINES == 0 && cancel.isCancelled()) {
                cancelled = true;
                return result(newlines);
            }
            boolean pieceStart = !line.continuation();
            boolean entryStart = pieceStart && handler.isEntryStart(line);
            if (!started) {
                if (!entryStart) {
                    continue;
                }
                if (line.offset() >= end) {
                    return result(0);
                }
                started = true;
            }
            if (entryStart) {
                if (inEntry && finishEntry()) {
                    return result(newlines);
                }
                if (line.offset() >= end) {
                    // the next chunk owns this entry
                    return result(newlines);
                }
                beginEntry(line, newlines, false);
                inEntry = true;
            } else if (pieceStart && handler.isSkippable(line)) {
                if (inEntry && finishEntry()) {
                    return result(newlines);
                }
                inEntry = false;
            } else if (inEntry) {
                buffer.append(line);
            } else if (plainPrefix) {
                // lines before the first header (edge #3)
                beginEntry(line, newlines, true);
                inEntry = true;
            }
            if (line.endsWithNewline()) {
                newlines++;
            }
        }
        if (inEntry) {
            finishEntry();
        }
        return result(newlines);
    }

    private void beginEntry(ByteLine line, long lineIndex, boolean headerless) {
        buffer.clear();
        buffer.append(line);
        entryOffset = line.offset();
        entryLine = lineIndex;
        entryHeaderless = headerless;
    }

    /** Processes the buffered entry. Returns true if the worker must stop (cancelled or local cap reached). */
    private boolean finishEntry() throws StrictModeException {
        if (cancel.isCancelled()) {
            cancelled = true;
            return true;
        }
        if (entryHeaderless && isWhitespace(buffer)) {
            return false;
        }
        RawEntry raw = new RawEntry(entryOffset, buffer.totalLength(), buffer.array(), buffer.size(),
                buffer.truncated(), entryHeaderless);
        if (!entryHeaderless) {
            entries++;
        }
        if (raw.truncated()) {
            truncated++;
        }
        if (!query.mayMatch(raw)) {
            return false;
        }
        LogEntry entry;
        if (entryHeaderless) {
            entry = new HeaderlessEntry(raw, decoder);
        } else {
            try {
                entry = handler.parse(raw, ctx);
            } catch (MalformedEntryException e) {
                malformed++;
                if (ctx.strict()) {
                    throw new StrictModeException(entryOffset, e);
                }
                return false;
            }
        }
        try {
            if (!query.matches(entry, budget)) {
                return false;
            }
        } catch (MatchTimeoutException e) {
            timeouts++;
            return false;
        } catch (ScanCancelledException e) {
            cancelled = true;
            return true;
        }
        HitSummary summary = entryHeaderless ? HeaderlessEntry.summarize(entry) : handler.summarize(entry);
        sink.accept(new Hit(entryOffset, raw.byteLength(), entryLine, raw.truncated(), summary));
        hits++;
        // no chunk can contribute more than the cap
        return resultCap > 0 && hits >= resultCap;
    }

    private static boolean isWhitespace(EntryBuffer buf) {
        byte[] b = buf.array();
        for (int i = 0; i < buf.size(); i++) {
            if (b[i] != ' ' && b[i] != '\t' && b[i] != '\r' && b[i] != '\n') {
                return false;
            }
        }
        return true;
    }

    private ChunkResult result(long lines) {
        return new ChunkResult(lines, hits, entries, malformed, timeouts, truncated, cancelled);
    }
}
