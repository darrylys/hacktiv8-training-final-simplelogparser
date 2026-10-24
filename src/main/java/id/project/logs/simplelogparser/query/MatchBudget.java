package id.project.logs.simplelogparser.query;

import java.time.Duration;

/**
 * Per-entry regex deadline plus the scan's cancel token. One instance per worker thread; not thread-safe.
 * {@link GuardedCharSequence} calls {@link #tick()} on every {@code charAt}; every 4096 ticks the deadline
 * and the cancel flag are checked.
 */
public final class MatchBudget {

    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(1);
    private static final int CHECK_MASK = 4096 - 1;

    private final long timeoutNanos;
    private final CancelToken cancel;
    private long deadline;
    private int ticks;

    public MatchBudget(Duration timeout, CancelToken cancel) {
        this.timeoutNanos = timeout.toNanos();
        this.cancel = cancel;
        startEntry();
    }

    public static MatchBudget unlimited() {
        return new MatchBudget(Duration.ofDays(365), new CancelToken());
    }

    /** Starts the deadline for the next entry. */
    public void startEntry() {
        deadline = System.nanoTime() + timeoutNanos;
        ticks = 0;
    }

    void tick() {
        if ((++ticks & CHECK_MASK) == 0) {
            check();
        }
    }

    void check() {
        if (cancel.isCancelled()) {
            throw new ScanCancelledException();
        }
        if (System.nanoTime() - deadline > 0) {
            throw new MatchTimeoutException();
        }
    }
}
