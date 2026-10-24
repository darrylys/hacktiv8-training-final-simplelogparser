package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.model.Hit;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-off between one chunk's worker and the merger. The worker appends hits as it finds them; the merger
 * drains them, so the oldest unfinished chunk streams live while later chunks buffer here.
 */
final class ChunkState {

    final ChunkPlanner.Chunk chunk;
    private List<Hit> pending = new ArrayList<>();
    private boolean done;
    private ChunkResult result;
    private Throwable error;

    ChunkState(ChunkPlanner.Chunk chunk) {
        this.chunk = chunk;
    }

    synchronized void addHit(Hit hit) {
        pending.add(hit);
        if (pending.size() == 1) {
            notifyAll();
        }
    }

    synchronized void finish(ChunkResult r) {
        result = r;
        done = true;
        notifyAll();
    }

    synchronized void fail(Throwable t) {
        error = t;
        done = true;
        notifyAll();
    }

    /** Waits up to {@code timeoutMillis} for hits or completion and drains the pending hits. */
    synchronized List<Hit> drain(long timeoutMillis) throws InterruptedException {
        if (pending.isEmpty() && !done) {
            wait(timeoutMillis);
        }
        if (pending.isEmpty()) {
            return List.of();
        }
        List<Hit> out = pending;
        pending = new ArrayList<>();
        return out;
    }

    synchronized boolean isDoneAndDrained() {
        return done && pending.isEmpty();
    }

    synchronized ChunkResult result() {
        return result;
    }

    synchronized Throwable error() {
        return error;
    }
}
