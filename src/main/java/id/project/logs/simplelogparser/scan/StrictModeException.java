package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.format.MalformedEntryException;

/** A malformed line under {@code --strict}. */
class StrictModeException extends Exception {

    StrictModeException(long offset, MalformedEntryException cause) {
        super("malformed line at byte offset " + offset + ": " + cause.getMessage(), cause);
    }
}
