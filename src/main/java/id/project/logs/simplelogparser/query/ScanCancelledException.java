package id.project.logs.simplelogparser.query;

/** The scan was cancelled while a match was running. Unchecked so it can escape {@code Matcher}. */
public class ScanCancelledException extends RuntimeException {

    public ScanCancelledException() {
        super("scan cancelled", null, false, false);
    }
}
