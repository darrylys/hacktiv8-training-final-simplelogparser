package id.project.logs.simplelogparser.model;

/**
 * One search result. Only offsets and a short summary are kept; the entry text is re-read from the file
 * when needed (edge #21).
 *
 * @param lineNumber 1-based line of the entry's first byte
 */
public record Hit(long offset, long byteLength, long lineNumber, boolean truncated, HitSummary summary) {

    public Hit withLineNumber(long line) {
        return new Hit(offset, byteLength, line, truncated, summary);
    }
}
