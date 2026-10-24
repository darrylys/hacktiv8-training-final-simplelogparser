package id.project.logs.simplelogparser.query;

/** Cooperative cancel flag shared by a scan's workers and regex guards. */
public final class CancelToken {

    private volatile boolean cancelled;

    public void cancel() {
        cancelled = true;
    }

    public boolean isCancelled() {
        return cancelled;
    }
}
