package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.model.Hit;

import java.util.List;

/** Scan callbacks. Called on a scan thread; front ends marshal to their own thread. */
public interface ScanListener {

    /** Hits in file order, batched. */
    default void onHits(List<Hit> hits) {
    }

    /** Fraction of bytes scanned, 0.0 to 1.0, monotonically increasing. */
    default void onProgress(double fraction) {
    }

    default void onFinished(ScanSummary summary) {
    }
}
