package id.project.logs.simplelogparser.query;

import java.util.List;

/**
 * Filter on one field. Values are OR'd. Filters on the same field with different modes are separate
 * filters and are AND'd (A10).
 */
public record FieldFilter(String field, MatchMode mode, List<String> values) {

    public FieldFilter {
        values = List.copyOf(values);
    }

    public static FieldFilter exact(String field, String... values) {
        return new FieldFilter(field, MatchMode.EXACT, List.of(values));
    }
}
