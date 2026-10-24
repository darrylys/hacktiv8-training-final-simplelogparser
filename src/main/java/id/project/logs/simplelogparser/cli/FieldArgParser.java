package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.MatchMode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses {@code --field} values (A14): {@code key=value} (exact), {@code key*=value} (contains),
 * {@code key~=regex} (regex). Repeated keys with the same mode merge into one OR'd filter.
 */
public final class FieldArgParser {

    private record Key(String field, MatchMode mode) {
    }

    private FieldArgParser() {
    }

    public static List<FieldFilter> parse(List<String> args) {
        Map<Key, List<String>> merged = new LinkedHashMap<>();
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq <= 0) {
                throw new IllegalArgumentException("invalid --field '" + arg
                        + "' (expected KEY=VALUE, KEY*=VALUE or KEY~=REGEX)");
            }
            String key = arg.substring(0, eq);
            MatchMode mode = MatchMode.EXACT;
            if (key.endsWith("*")) {
                mode = MatchMode.CONTAINS;
                key = key.substring(0, key.length() - 1);
            } else if (key.endsWith("~")) {
                mode = MatchMode.REGEX;
                key = key.substring(0, key.length() - 1);
            }
            key = key.trim();
            if (key.isEmpty()) {
                throw new IllegalArgumentException("invalid --field '" + arg + "': empty field name");
            }
            merged.computeIfAbsent(new Key(key, mode), k -> new ArrayList<>()).add(arg.substring(eq + 1));
        }
        List<FieldFilter> out = new ArrayList<>();
        merged.forEach((k, values) -> out.add(new FieldFilter(k.field(), k.mode(), values)));
        return out;
    }
}
