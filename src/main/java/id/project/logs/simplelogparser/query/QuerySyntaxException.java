package id.project.logs.simplelogparser.query;

/** Invalid query, reported before any I/O (PRD 5.4, AC7). */
public class QuerySyntaxException extends Exception {

    private final String pattern;
    private final String description;
    private final int index;

    public QuerySyntaxException(String pattern, String description, int index) {
        super(format(pattern, description, index));
        this.pattern = pattern;
        this.description = description;
        this.index = index;
    }

    public QuerySyntaxException(String message) {
        super(message);
        this.pattern = null;
        this.description = message;
        this.index = -1;
    }

    private static String format(String pattern, String description, int index) {
        String where = index >= 0 ? " at index " + index : "";
        return "invalid regex '" + pattern + "': " + description + where;
    }

    public String pattern() {
        return pattern;
    }

    public String description() {
        return description;
    }

    /** Position of the error in the pattern, or -1 when unknown. */
    public int index() {
        return index;
    }
}
