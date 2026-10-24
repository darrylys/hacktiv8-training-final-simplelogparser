package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.model.Scope;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A validated, compiled {@link Query}. Thread-safe and shareable between workers.
 * {@link #mayMatch(RawEntry)} is the cheap byte prefilter; {@link #matches(LogEntry, MatchBudget)} is the
 * full evaluation, cheapest checks first: level, time range, field filters, text.
 */
public final class CompiledQuery {

    /** One compiled field filter. */
    public record CompiledField(String field, MatchMode mode, List<String> values, List<Pattern> patterns) {

        boolean test(String value, MatchBudget budget) {
            String v = value.trim();
            return switch (mode) {
                case EXACT -> values.contains(v);
                case CONTAINS -> {
                    for (String needle : values) {
                        if (v.contains(needle)) {
                            yield true;
                        }
                    }
                    yield false;
                }
                case REGEX -> {
                    CharSequence target = new GuardedCharSequence(v, budget);
                    for (Pattern p : patterns) {
                        if (p.matcher(target).find()) {
                            yield true;
                        }
                    }
                    yield false;
                }
            };
        }
    }

    private final Query query;
    private final List<CompiledField> fields;
    private final Pattern textPattern;
    private final Scope scope;
    private final Set<Level> levels;
    private final Instant from;
    private final Instant to;
    private final BytePrefilter prefilter;
    private final LogFormat format;
    private final boolean usesRegex;

    CompiledQuery(Query query, List<CompiledField> fields, Pattern textPattern, Scope scope,
                  BytePrefilter prefilter, LogFormat format) {
        this.query = query;
        this.fields = List.copyOf(fields);
        this.textPattern = textPattern;
        this.scope = scope;
        this.levels = query.levels();
        this.from = query.from().orElse(null);
        this.to = query.to().orElse(null);
        this.prefilter = prefilter;
        this.format = format;
        this.usesRegex = query.text().map(TextQuery::regex).orElse(false)
                || fields.stream().anyMatch(f -> f.mode() == MatchMode.REGEX);
    }

    public Query query() {
        return query;
    }

    public List<CompiledField> fields() {
        return fields;
    }

    public Optional<Pattern> textPattern() {
        return Optional.ofNullable(textPattern);
    }

    public Scope scope() {
        return scope;
    }

    /** True if any part is a regex; only then is matching guarded by the per-entry deadline. */
    public boolean usesRegex() {
        return usesRegex;
    }

    /**
     * The query as it applies to one file format. JSON has no request/response sections, so a REQUEST or
     * RESPONSE scope falls back to ALL there (PRD 4.2, A19); the front end warns about it.
     */
    public CompiledQuery forFormat(LogFormat fileFormat) {
        Scope s = fileFormat == LogFormat.JSON ? Scope.ALL : scope;
        return new CompiledQuery(query, fields, textPattern, s, prefilter, fileFormat);
    }

    /** Same query with the byte prefilter disabled; used to prove it causes no false negatives. */
    public CompiledQuery withoutPrefilter() {
        return new CompiledQuery(query, fields, textPattern, scope, BytePrefilter.NONE, format);
    }

    public boolean hasPrefilter() {
        return !prefilter.isEmpty();
    }

    public boolean mayMatch(RawEntry raw) {
        // an unknown format is treated like JSON, the stricter case
        return prefilter.mayMatch(raw, format != LogFormat.PLAIN);
    }

    public boolean matches(LogEntry entry, MatchBudget budget) {
        if (!levels.isEmpty() && !levels.contains(entry.level())) {
            return false;
        }
        if (from != null || to != null) {
            Optional<Instant> ts = entry.timestamp();
            if (ts.isEmpty()) {
                return false;
            }
            if (from != null && ts.get().isBefore(from)) {
                return false;
            }
            if (to != null && !ts.get().isBefore(to)) {
                return false;
            }
        }
        if (usesRegex) {
            budget.startEntry();
        }
        for (CompiledField f : fields) {
            Optional<String> value = entry.field(f.field());
            if (value.isEmpty() || !f.test(value.get(), budget)) {
                return false;
            }
        }
        if (textPattern != null) {
            if (entry.sectionStart(scope) < 0) {
                return false;
            }
            CharSequence target = entry.section(scope);
            if (usesRegex) {
                target = new GuardedCharSequence(target, budget);
            }
            return textPattern.matcher(target).find();
        }
        return true;
    }
}
