package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.scan.ScanSummary;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** End-of-scan notice for the status bar, matching the CLI's stderr notices. */
public final class StatusText {

    private StatusText() {
    }

    public static String hits(long count) {
        return String.format(Locale.ROOT, "%,d %s", count, count == 1 ? "hit" : "hits");
    }

    public static String summary(ScanSummary s, int cap) {
        List<String> parts = new ArrayList<>();
        if (s.capped()) {
            parts.add(String.format(Locale.ROOT, "results capped at %,d", cap));
        }
        if (s.cancelled()) {
            parts.add("cancelled");
        }
        if (s.malformedLines() > 0) {
            parts.add(s.malformedLines() + " malformed lines skipped");
        }
        if (s.regexTimeouts() > 0) {
            parts.add(s.regexTimeouts() + " entries skipped (regex timeout)");
        }
        if (s.truncatedEntries() > 0) {
            parts.add(s.truncatedEntries() + " oversized entries truncated");
        }
        if (s.fileChangedDuringScan()) {
            parts.add("file changed during scan");
        }
        parts.add(String.format(Locale.ROOT, "%.1f s", s.elapsed().toMillis() / 1000.0));
        return String.join(" · ", parts);
    }
}
