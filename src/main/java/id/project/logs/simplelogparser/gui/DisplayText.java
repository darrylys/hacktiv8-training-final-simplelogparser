package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.format.json.JsonEntry;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.GuardedCharSequence;
import id.project.logs.simplelogparser.query.Highlighter;
import id.project.logs.simplelogparser.query.MatchBudget;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;

/**
 * Text shown in the detail pane, with its highlight ranges. Plain entries show their match text unchanged.
 * JSON entries show the decoded message followed by the raw line, in which the escapes {@code \n},
 * {@code \r} and {@code \t} inside string values are rendered as real line breaks and tabs ({@code \r\n}
 * becomes one line break), so long one-line JSON is readable. Other escapes ({@code \"}, {@code \\},
 * {@code \/}, {@code \}{@code uXXXX}) are kept as written. Display only: copying a whole entry still
 * copies the original line (AC20).
 */
public record DisplayText(String text, List<Highlighter.Range> ranges) {

    /** Builds the display text; {@code matchRanges} are ranges in the entry's match text. */
    public static DisplayText of(LogEntry entry, List<Highlighter.Range> matchRanges, CompiledQuery query,
                                 MatchBudget budget) {
        if (!(entry instanceof JsonEntry json)) {
            return new DisplayText(entry.matchText(), matchRanges);
        }
        String match = entry.matchText();
        int rawStart = Math.min(json.rawLineStart(), match.length());
        Rendered raw = renderEscapes(match.substring(rawStart));
        String text = match.substring(0, rawStart) + raw.text();

        List<Highlighter.Range> out = new ArrayList<>();
        for (Highlighter.Range r : matchRanges) {
            if (r.start() < rawStart) {
                out.add(new Highlighter.Range(r.start(), Math.min(r.end(), rawStart)));
            } else {
                int s = rawStart + raw.map()[r.start() - rawStart];
                int e = rawStart + raw.map()[r.end() - rawStart];
                if (e > s) {
                    out.add(new Highlighter.Range(s, e));
                }
            }
        }
        // matches that only appear once escapes are rendered, e.g. a regex spanning a rendered line break
        if (query != null && query.textPattern().isPresent()) {
            Matcher m = query.textPattern().get().matcher(new GuardedCharSequence(raw.text(), budget));
            while (out.size() < Highlighter.MAX_RANGES && m.find()) {
                if (m.end() > m.start()) {
                    out.add(new Highlighter.Range(rawStart + m.start(), rawStart + m.end()));
                }
            }
        }
        return new DisplayText(text, merge(out));
    }

    /**
     * Rendered text plus {@code map}: for each raw index {@code i} (0..length), the display index where raw
     * char {@code i} starts. Both chars of an escape map to the same display char.
     */
    record Rendered(String text, int[] map) {
    }

    static Rendered renderEscapes(String raw) {
        int n = raw.length();
        int[] map = new int[n + 1];
        StringBuilder sb = new StringBuilder(n);
        boolean inString = false;
        int i = 0;
        while (i < n) {
            char c = raw.charAt(i);
            map[i] = sb.length();
            if (!inString) {
                if (c == '"') {
                    inString = true;
                }
                sb.append(c);
                i++;
                continue;
            }
            if (c == '"') {
                inString = false;
                sb.append(c);
                i++;
            } else if (c == '\\' && i + 1 < n) {
                char next = raw.charAt(i + 1);
                map[i + 1] = sb.length();
                if (next == 'n' || next == 't') {
                    sb.append(next == 'n' ? '\n' : '\t');
                    i += 2;
                } else if (next == 'r') {
                    sb.append('\n');
                    i += 2;
                    if (i + 1 < n && raw.charAt(i) == '\\' && raw.charAt(i + 1) == 'n') {
                        map[i] = map[i - 2];
                        map[i + 1] = map[i - 2];
                        i += 2;
                    }
                } else {
                    // quote, backslash, slash, b, f and backslash-u escapes stay as written
                    sb.append(c).append(next);
                    map[i + 1] = sb.length() - 1;
                    i += 2;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        map[n] = sb.length();
        return new Rendered(sb.toString(), map);
    }

    private static List<Highlighter.Range> merge(List<Highlighter.Range> ranges) {
        List<Highlighter.Range> sorted = new ArrayList<>(ranges);
        sorted.sort(Comparator.comparingInt(Highlighter.Range::start).thenComparingInt(Highlighter.Range::end));
        List<Highlighter.Range> merged = new ArrayList<>(sorted.size());
        for (Highlighter.Range r : sorted) {
            if (!merged.isEmpty() && r.start() <= merged.get(merged.size() - 1).end()) {
                Highlighter.Range last = merged.remove(merged.size() - 1);
                merged.add(new Highlighter.Range(last.start(), Math.max(last.end(), r.end())));
            } else {
                merged.add(r);
            }
        }
        return merged;
    }
}
