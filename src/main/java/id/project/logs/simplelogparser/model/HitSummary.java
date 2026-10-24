package id.project.logs.simplelogparser.model;

import java.util.Map;

/**
 * Short description of a hit for result lists.
 *
 * @param keyIds    requestId / correlationId / traceId when present
 * @param firstLine first line of the message, cut to {@link #MAX_FIRST_LINE} characters
 */
public record HitSummary(String timestampText, Level level, String logger, Map<String, String> keyIds, String firstLine) {

    public static final int MAX_FIRST_LINE = 200;

    public static String firstLineOf(String text) {
        int nl = text.indexOf('\n');
        String line = nl < 0 ? text : text.substring(0, nl);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        return line.length() > MAX_FIRST_LINE ? line.substring(0, MAX_FIRST_LINE) : line;
    }
}
