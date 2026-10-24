package id.project.logs.simplelogparser.query;

/** Field value match mode. Values are trimmed and compared case-sensitively, with no numeric coercion (A11). */
public enum MatchMode {
    EXACT,
    CONTAINS,
    REGEX
}
