package id.project.logs.simplelogparser.cli;

import java.util.List;

/** grep-style exit codes. */
public final class ExitCode {

    public static final int MATCH = 0;
    public static final int NO_MATCH = 1;
    public static final int ERROR = 2;

    private ExitCode() {
    }

    /** Several files: 0 if any file matched, otherwise 2 if any file errored, otherwise 1 (A16). */
    public static int combine(List<Integer> perFile) {
        if (perFile.contains(MATCH)) {
            return MATCH;
        }
        if (perFile.contains(ERROR)) {
            return ERROR;
        }
        return NO_MATCH;
    }
}
