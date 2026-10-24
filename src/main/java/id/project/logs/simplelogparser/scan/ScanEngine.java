package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.query.CancelToken;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Parallel scan: a fixed pool of platform threads per scan (rejected R5) scans fixed-size chunks; a
 * coordinator thread merges their hits in file order. At most {@code 2 x threads} chunks are in flight or
 * buffered, and a new chunk is submitted only when the oldest has been emitted, which bounds memory
 * independently of file size (PRD 6).
 */
public class ScanEngine {

    private static final AtomicInteger SCAN_IDS = new AtomicInteger();
    private static final long DRAIN_WAIT_MILLIS = 100;

    /** Opens the file and starts the scan. Throws if the file cannot be opened. */
    public ScanHandle start(ScanRequest request, ScanListener listener) throws IOException {
        LogFile file = LogFile.open(request.file());
        CancelToken cancel = new CancelToken();
        AtomicLong scanned = new AtomicLong();
        ScanHandle handle = new ScanHandle(cancel, scanned, file.sizeAtOpen());
        int id = SCAN_IDS.incrementAndGet();
        Thread coordinator = new Thread(() -> coordinate(request, listener, file, cancel, scanned, handle),
                "slp-scan-" + id);
        coordinator.setDaemon(true);
        coordinator.start();
        return handle;
    }

    /** Runs a scan to completion on the calling thread's behalf. */
    public ScanSummary run(ScanRequest request, ScanListener listener)
            throws IOException, ScanException, InterruptedException {
        return start(request, listener).await();
    }

    private void coordinate(ScanRequest request, ScanListener listener, LogFile file, CancelToken cancel,
                            AtomicLong scanned, ScanHandle handle) {
        long t0 = System.nanoTime();
        ChunkPlanner planner = new ChunkPlanner(file.sizeAtOpen(), request.chunkSize());
        long chunkCount = planner.count();
        int threads = (int) Math.max(1, Math.min(request.threads(), chunkCount));
        ExecutorService pool = Executors.newFixedThreadPool(threads, daemonThreads(Thread.currentThread().getName()));
        FormatHandler handler = FormatHandler.of(request.format(), request.charset());
        ThreadLocal<ChunkWorker> workers = ThreadLocal.withInitial(
                () -> new ChunkWorker(file, request, handler, cancel));
        OrderedMerger merger = new OrderedMerger(listener, request.resultCap());
        try {
            ArrayDeque<ChunkState> inFlight = new ArrayDeque<>();
            int window = 2 * threads;
            long next = 0;
            while (inFlight.size() < window && next < chunkCount) {
                inFlight.add(submit(pool, workers, planner.chunk(next++), cancel, scanned));
            }
            boolean cancelled = false;
            while (!inFlight.isEmpty()) {
                ChunkState head = inFlight.peekFirst();
                boolean underCap = true;
                while (underCap) {
                    List<Hit> hits = head.drain(DRAIN_WAIT_MILLIS);
                    if (!hits.isEmpty()) {
                        underCap = merger.emit(hits);
                    }
                    if (head.isDoneAndDrained()) {
                        break;
                    }
                }
                if (!underCap) {
                    cancel.cancel();
                    break;
                }
                if (head.error() != null) {
                    throw head.error();
                }
                ChunkResult result = head.result();
                merger.completeChunk(result);
                inFlight.removeFirst();
                listener.onProgress(handle.progress());
                if (result.cancelled() || cancel.isCancelled()) {
                    cancelled = true;
                    break;
                }
                if (next < chunkCount) {
                    inFlight.add(submit(pool, workers, planner.chunk(next++), cancel, scanned));
                }
            }
            boolean changed = file.currentSize() != file.sizeAtOpen();
            if (!cancelled && !merger.capped()) {
                listener.onProgress(1.0);
            }
            ScanSummary summary = new ScanSummary(merger.emitted(), merger.capped(), cancelled && !merger.capped(),
                    merger.malformed(), merger.timeouts(), merger.truncated(), changed, merger.entries(),
                    Duration.ofNanos(System.nanoTime() - t0));
            listener.onFinished(summary);
            handle.future().complete(summary);
        } catch (Throwable t) {
            cancel.cancel();
            handle.future().completeExceptionally(new ScanException(describe(t, request), t));
        } finally {
            // no shutdownNow: interrupting a thread inside FileChannel.read would close the channel
            pool.shutdown();
            try {
                pool.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                file.close();
            } catch (IOException ignored) {
                // read-only channel; nothing to flush
            }
        }
    }

    private static ChunkState submit(ExecutorService pool, ThreadLocal<ChunkWorker> workers,
                                     ChunkPlanner.Chunk chunk, CancelToken cancel, AtomicLong scanned) {
        ChunkState state = new ChunkState(chunk);
        pool.execute(() -> {
            try {
                if (cancel.isCancelled()) {
                    state.finish(new ChunkResult(0, 0, 0, 0, 0, 0, true));
                    return;
                }
                ChunkResult r = workers.get().scan(chunk, state::addHit);
                scanned.addAndGet(chunk.length());
                state.finish(r);
            } catch (Throwable t) {
                state.fail(t);
            }
        });
        return state;
    }

    private static String describe(Throwable t, ScanRequest request) {
        if (t instanceof StrictModeException) {
            return t.getMessage();
        }
        Throwable cause = t instanceof UncheckedIOException u ? u.getCause() : t;
        if (cause instanceof IOException) {
            return "I/O error while scanning " + request.file() + ": " + cause.getMessage();
        }
        return "scan failed: " + t;
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-worker-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
