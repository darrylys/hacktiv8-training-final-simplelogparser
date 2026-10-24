package id.project.logs.simplelogparser.query;

/** A regex ran past its per-entry deadline (edge #14). Unchecked so it can escape {@code Matcher}. */
public class MatchTimeoutException extends RuntimeException {

    public MatchTimeoutException() {
        super("regex match timed out", null, false, false);
    }
}
