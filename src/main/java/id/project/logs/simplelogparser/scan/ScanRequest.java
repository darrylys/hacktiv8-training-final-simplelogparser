package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.EntryBuffer;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.MatchBudget;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Everything a scan needs. {@code format} is already resolved (detected or forced).
 *
 * @param resultCap 0 means no cap
 * @param chunkSize bytes per chunk; tests use tiny values to force boundaries mid-entry
 */
public record ScanRequest(Path file, LogFormat format, CompiledQuery query, ParseContext ctx, Charset charset,
                          int resultCap, int threads, long chunkSize, int entryLimit, Duration regexTimeout) {

    public static final long DEFAULT_CHUNK_SIZE = 8L * 1024 * 1024;

    public ScanRequest {
        if (threads < 1) {
            throw new IllegalArgumentException("threads must be >= 1: " + threads);
        }
        if (chunkSize < 1) {
            throw new IllegalArgumentException("chunk size must be >= 1: " + chunkSize);
        }
        if (resultCap < 0) {
            throw new IllegalArgumentException("result cap must be >= 0: " + resultCap);
        }
    }

    public static ScanRequest of(Path file, LogFormat format, CompiledQuery query) {
        return new ScanRequest(file, format, query, ParseContext.defaults(), StandardCharsets.UTF_8, 0,
                defaultThreads(), DEFAULT_CHUNK_SIZE, EntryBuffer.DEFAULT_ENTRY_LIMIT, MatchBudget.DEFAULT_TIMEOUT);
    }

    /**
     * Core count, limited by heap so that worst-case per-worker buffers (about 64 MB each) fit (A21).
     */
    public static int defaultThreads() {
        int cores = Runtime.getRuntime().availableProcessors();
        long maxHeap = Runtime.getRuntime().maxMemory();
        long byHeap = Math.max(1, maxHeap / (64L * 1024 * 1024));
        return (int) Math.max(1, Math.min(cores, byHeap));
    }

    public ScanRequest withQuery(CompiledQuery q) {
        return new ScanRequest(file, format, q, ctx, charset, resultCap, threads, chunkSize, entryLimit, regexTimeout);
    }

    public ScanRequest withContext(ParseContext c) {
        return new ScanRequest(file, format, query, c, charset, resultCap, threads, chunkSize, entryLimit, regexTimeout);
    }

    public ScanRequest withCharset(Charset cs) {
        return new ScanRequest(file, format, query, ctx, cs, resultCap, threads, chunkSize, entryLimit, regexTimeout);
    }

    public ScanRequest withResultCap(int cap) {
        return new ScanRequest(file, format, query, ctx, charset, cap, threads, chunkSize, entryLimit, regexTimeout);
    }

    public ScanRequest withThreads(int n) {
        return new ScanRequest(file, format, query, ctx, charset, resultCap, n, chunkSize, entryLimit, regexTimeout);
    }

    public ScanRequest withChunkSize(long size) {
        return new ScanRequest(file, format, query, ctx, charset, resultCap, threads, size, entryLimit, regexTimeout);
    }

    public ScanRequest withEntryLimit(int limit) {
        return new ScanRequest(file, format, query, ctx, charset, resultCap, threads, chunkSize, limit, regexTimeout);
    }

    public ScanRequest withRegexTimeout(Duration timeout) {
        return new ScanRequest(file, format, query, ctx, charset, resultCap, threads, chunkSize, entryLimit, timeout);
    }
}
