package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.model.Scope;

/** Text query; case-insensitive by default (PRD 5.3). */
public record TextQuery(String pattern, boolean regex, boolean caseSensitive, boolean dotAll, Scope scope) {

    public static TextQuery literal(String text) {
        return new TextQuery(text, false, false, false, Scope.ALL);
    }

    public static TextQuery regex(String pattern) {
        return new TextQuery(pattern, true, false, false, Scope.ALL);
    }

    public TextQuery withScope(Scope newScope) {
        return new TextQuery(pattern, regex, caseSensitive, dotAll, newScope);
    }

    public TextQuery withCaseSensitive(boolean value) {
        return new TextQuery(pattern, regex, value, dotAll, scope);
    }
}
