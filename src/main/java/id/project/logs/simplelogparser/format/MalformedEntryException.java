package id.project.logs.simplelogparser.format;

/** An entry that cannot be parsed (malformed JSON line, edge #8). */
public class MalformedEntryException extends Exception {

    public MalformedEntryException(String message, Throwable cause) {
        super(message, cause);
    }

    public MalformedEntryException(String message) {
        super(message);
    }
}
