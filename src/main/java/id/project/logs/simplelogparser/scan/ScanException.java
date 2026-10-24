package id.project.logs.simplelogparser.scan;

/** A scan that could not complete: I/O error mid-scan, or a malformed line under {@code --strict}. */
public class ScanException extends Exception {

    public ScanException(String message, Throwable cause) {
        super(message, cause);
    }
}
