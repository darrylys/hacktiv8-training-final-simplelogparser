package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.model.Hit;

import java.util.ArrayList;
import java.util.List;

/**
 * Emits hits strictly in chunk order, assigns absolute line numbers ({@code base(i) = 1 + sum of lines of
 * chunks before i}) and enforces the result cap in file order, so the capped set is always the first N
 * hits regardless of thread timing (rejected R4). Used only by the scan's coordinator thread.
 */
final class OrderedMerger {

    private final ScanListener listener;
    private final int cap;
    private long base = 1;
    private long emitted;
    private boolean capped;

    private long entries;
    private long malformed;
    private long timeouts;
    private long truncated;

    OrderedMerger(ScanListener listener, int cap) {
        this.listener = listener;
        this.cap = cap;
    }

    /** Forwards hits of the current (oldest unfinished) chunk. Returns false once the cap is reached. */
    boolean emit(List<Hit> chunkHits) {
        if (capped) {
            return false;
        }
        List<Hit> batch = new ArrayList<>(chunkHits.size());
        for (Hit h : chunkHits) {
            if (cap > 0 && emitted >= cap) {
                capped = true;
                break;
            }
            batch.add(h.withLineNumber(base + h.lineNumber()));
            emitted++;
        }
        if (cap > 0 && emitted >= cap) {
            capped = true;
        }
        if (!batch.isEmpty()) {
            listener.onHits(batch);
        }
        return !capped;
    }

    /** Called when the current chunk is complete; the next chunk becomes current. */
    void completeChunk(ChunkResult r) {
        base += r.lines();
        entries += r.entries();
        malformed += r.malformed();
        timeouts += r.regexTimeouts();
        truncated += r.truncated();
    }

    boolean capped() {
        return capped;
    }

    long emitted() {
        return emitted;
    }

    long entries() {
        return entries;
    }

    long malformed() {
        return malformed;
    }

    long timeouts() {
        return timeouts;
    }

    long truncated() {
        return truncated;
    }
}
