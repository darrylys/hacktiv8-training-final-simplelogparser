package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.format.json.JsonEntry;
import id.project.logs.simplelogparser.model.LogEntry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;

/**
 * Computes match ranges in an entry's {@link LogEntry#matchText() match text}: every match of the text
 * query inside its scope, plus the value of each field filter as written in the entry's first line
 * (plain header) or JSON line. Regex work uses the same guard as the scan.
 */
public final class Highlighter {

    public static final int MAX_RANGES = 10_000;

    /** Half-open range {@code [start, end)} in the match text. */
    public record Range(int start, int end) {
    }

    private Highlighter() {
    }

    public static List<Range> ranges(LogEntry entry, CompiledQuery query, MatchBudget budget) {
        String text = entry.matchText();
        List<Range> out = new ArrayList<>();
        budget.startEntry();
        query.textPattern().ifPresent(p -> {
            int base = entry.sectionStart(query.scope());
            if (base < 0) {
                return;
            }
            Matcher m = p.matcher(new GuardedCharSequence(entry.section(query.scope()), budget));
            while (out.size() < MAX_RANGES && m.find()) {
                if (m.end() > m.start()) {
                    out.add(new Range(base + m.start(), base + m.end()));
                }
            }
        });
        int lineEnd = firstLineEnd(text);
        int regionStart = jsonRawLineStart(entry, text);
        int regionEnd = regionStart == 0 ? lineEnd : text.length();
        for (CompiledQuery.CompiledField f : query.fields()) {
            Optional<String> value = entry.field(f.field());
            if (value.isEmpty() || value.get().isEmpty()) {
                continue;
            }
            String v = value.get();
            String region = text.substring(regionStart, regionEnd);
            int keyAt = region.indexOf(f.field());
            int at = region.indexOf(v, keyAt < 0 ? 0 : keyAt + f.field().length());
            if (at < 0) {
                at = region.indexOf(v);
            }
            if (at >= 0) {
                out.add(new Range(regionStart + at, regionStart + at + v.length()));
            }
        }
        out.sort(Comparator.comparingInt(Range::start).thenComparingInt(Range::end));
        return merge(out);
    }

    /**
     * Maps match-text ranges onto {@link LogEntry#rawText()} (original line endings). For JSON entries only
     * ranges inside the raw-line part of the match text can be mapped; ranges in the decoded message are
     * dropped (the same text usually also matches in the raw line).
     */
    public static List<Range> toRawRanges(LogEntry entry, List<Range> ranges) {
        String raw = entry.rawText();
        int shift = 0;
        if (entry instanceof JsonEntry json) {
            shift = json.rawLineStart();
        }
        int[] map = matchToRaw(raw);
        int matchLen = map.length - 1;
        List<Range> out = new ArrayList<>(ranges.size());
        for (Range r : ranges) {
            int s = r.start() - shift;
            int e = r.end() - shift;
            if (s < 0 || e > matchLen || s >= e) {
                continue;
            }
            out.add(new Range(map[s], map[e - 1] + 1));
        }
        return out;
    }

    /** {@code map[i]} is the raw index of match-text char {@code i}; {@code map[len]} is the raw length. */
    private static int[] matchToRaw(String raw) {
        int[] map = new int[raw.length() + 1];
        int j = 0;
        for (int i = 0; i < raw.length(); i++) {
            if (raw.charAt(i) == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                continue;
            }
            map[j++] = i;
        }
        map[j] = raw.length();
        return Arrays.copyOf(map, j + 1);
    }

    private static int firstLineEnd(String text) {
        int nl = text.indexOf('\n');
        return nl < 0 ? text.length() : nl;
    }

    private static int jsonRawLineStart(LogEntry entry, String text) {
        if (entry instanceof JsonEntry json) {
            return Math.min(json.rawLineStart(), text.length());
        }
        return 0;
    }

    private static List<Range> merge(List<Range> sorted) {
        List<Range> merged = new ArrayList<>(sorted.size());
        for (Range r : sorted) {
            if (!merged.isEmpty() && r.start() <= merged.get(merged.size() - 1).end()) {
                Range last = merged.remove(merged.size() - 1);
                merged.add(new Range(last.start(), Math.max(last.end(), r.end())));
            } else {
                merged.add(r);
            }
        }
        return merged;
    }
}
