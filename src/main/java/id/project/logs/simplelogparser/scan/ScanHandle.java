package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.query.CancelToken;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** A running scan. {@link #cancel()} returns immediately; workers notice within one entry. */
public final class ScanHandle {

    private final CancelToken cancel;
    private final AtomicLong scannedBytes;
    private final long totalBytes;
    private final CompletableFuture<ScanSummary> done = new CompletableFuture<>();

    ScanHandle(CancelToken cancel, AtomicLong scannedBytes, long totalBytes) {
        this.cancel = cancel;
        this.scannedBytes = scannedBytes;
        this.totalBytes = totalBytes;
    }

    public void cancel() {
        cancel.cancel();
    }

    /** Bytes of completed chunks over the file size observed at open. */
    public double progress() {
        return totalBytes == 0 ? 1.0 : Math.min(1.0, (double) scannedBytes.get() / totalBytes);
    }

    /** Completes with the summary, or exceptionally with a {@link ScanException}. */
    public CompletableFuture<ScanSummary> done() {
        return done;
    }

    /** Blocks until the scan ends. */
    public ScanSummary await() throws ScanException, InterruptedException {
        try {
            return done.get();
        } catch (ExecutionException e) {
            if (e.getCause() instanceof ScanException se) {
                throw se;
            }
            throw new ScanException(String.valueOf(e.getCause().getMessage()), e.getCause());
        }
    }

    CompletableFuture<ScanSummary> future() {
        return done;
    }
}
