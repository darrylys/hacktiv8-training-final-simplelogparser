package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.model.Level;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A search. Field filters are AND'd with each other and with the text query; values within one filter
 * are OR'd (PRD 2). An empty query matches every entry.
 *
 * @param levels empty means any level
 * @param from   inclusive lower bound on the entry timestamp
 * @param to     exclusive upper bound on the entry timestamp (A20)
 */
public record Query(List<FieldFilter> fields, Optional<TextQuery> text, Set<Level> levels,
                    Optional<Instant> from, Optional<Instant> to) {

    public Query {
        fields = List.copyOf(fields);
        levels = Set.copyOf(levels);
    }

    public static Query matchAll() {
        return new Query(List.of(), Optional.empty(), Set.of(), Optional.empty(), Optional.empty());
    }

    public Query withFields(List<FieldFilter> newFields) {
        return new Query(newFields, text, levels, from, to);
    }

    public Query withText(TextQuery newText) {
        return new Query(fields, Optional.ofNullable(newText), levels, from, to);
    }

    public Query withLevels(Set<Level> newLevels) {
        return new Query(fields, text, newLevels, from, to);
    }

    public Query withRange(Instant newFrom, Instant newTo) {
        return new Query(fields, text, levels, Optional.ofNullable(newFrom), Optional.ofNullable(newTo));
    }
}
