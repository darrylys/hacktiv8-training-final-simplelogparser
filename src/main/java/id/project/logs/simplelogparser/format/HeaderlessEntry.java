package id.project.logs.simplelogparser.format;

import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.HitSummary;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.model.Scope;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Lines before the first header (edge #3, AC23): no fields, level UNKNOWN, no timestamp; text only. */
public final class HeaderlessEntry implements LogEntry {

    private final long offset;
    private final boolean truncated;
    private final String rawText;
    private String matchText;

    public HeaderlessEntry(RawEntry raw, TextDecoder decoder) {
        this.offset = raw.offset();
        this.truncated = raw.truncated();
        this.rawText = decoder.decode(raw.bytes(), 0, raw.length(), raw.offset());
    }

    public static HitSummary summarize(LogEntry entry) {
        return new HitSummary("", Level.UNKNOWN, "", Map.of(), HitSummary.firstLineOf(entry.rawText()));
    }

    @Override
    public long offset() {
        return offset;
    }

    @Override
    public String rawText() {
        return rawText;
    }

    @Override
    public String matchText() {
        if (matchText == null) {
            matchText = TextDecoder.normalizeLineEndings(rawText);
        }
        return matchText;
    }

    @Override
    public Optional<Instant> timestamp() {
        return Optional.empty();
    }

    @Override
    public Level level() {
        return Level.UNKNOWN;
    }

    @Override
    public Optional<String> field(String name) {
        return Optional.empty();
    }

    @Override
    public Set<String> fieldNames() {
        return Set.of();
    }

    @Override
    public CharSequence section(Scope scope) {
        return scope == Scope.ALL ? matchText() : "";
    }

    @Override
    public int sectionStart(Scope scope) {
        return scope == Scope.ALL ? 0 : -1;
    }

    @Override
    public boolean truncated() {
        return truncated;
    }
}
