package id.project.logs.simplelogparser.format.plain;

import id.project.logs.simplelogparser.io.ByteLine;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses the plain header line. The lazy {@code mdc} group is anchored by the timestamp that must follow
 * it, so a {@code ]} inside the MDC does not break parsing. An empty message may also appear without the
 * space after {@code -} (trailing whitespace stripped by an editor or a copy).
 */
public final class PlainHeaderParser {

    static final Pattern HEADER = Pattern.compile(
            "^\\[(?<mdc>.*?)\\] (?<ts>\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}\\.\\d{3}) "
                    + "\\[(?<thread>[^\\]]*)\\] (?<level>[A-Z]+)\\s+(?<logger>\\S+) -(?: (?<msg>.*))?$",
            Pattern.DOTALL);

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");

    /** Length of {@code "] yyyy-MM-dd HH:mm:ss.SSS ["}. */
    private static final int MARKER_LENGTH = 27;

    public record Header(String mdc, String timestamp, String thread, String level, String logger, String message) {
    }

    private PlainHeaderParser() {
    }

    public static Optional<Header> parse(CharSequence line) {
        Matcher m = HEADER.matcher(line);
        if (!m.matches()) {
            return Optional.empty();
        }
        return Optional.of(new Header(m.group("mdc"), m.group("ts"), m.group("thread"), m.group("level"),
                m.group("logger"), m.group("msg") == null ? "" : m.group("msg")));
    }

    public static boolean matches(CharSequence line) {
        return HEADER.matcher(line).matches();
    }

    /**
     * Cheap byte check: content starts with {@code [} at {@code skip} and contains
     * {@code "] dddd-dd-dd dd:dd:dd.ddd ["}. Rejects almost all continuation lines without decoding.
     */
    static boolean bytePrefilter(ByteLine line, int skip) {
        byte[] b = line.bytes();
        int start = line.start() + skip;
        int end = line.start() + line.contentLength();
        if (start >= end || b[start] != '[') {
            return false;
        }
        for (int i = start + 1; i + MARKER_LENGTH <= end; i++) {
            if (b[i] == ']' && isMarkerAt(b, i)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isMarkerAt(byte[] b, int i) {
        return b[i + 1] == ' '
                && digits(b, i + 2, 4) && b[i + 6] == '-'
                && digits(b, i + 7, 2) && b[i + 9] == '-'
                && digits(b, i + 10, 2) && b[i + 12] == ' '
                && digits(b, i + 13, 2) && b[i + 15] == ':'
                && digits(b, i + 16, 2) && b[i + 18] == ':'
                && digits(b, i + 19, 2) && b[i + 21] == '.'
                && digits(b, i + 22, 3) && b[i + 25] == ' '
                && b[i + 26] == '[';
    }

    private static boolean digits(byte[] b, int from, int count) {
        for (int i = from; i < from + count; i++) {
            if (b[i] < '0' || b[i] > '9') {
                return false;
            }
        }
        return true;
    }

    /** Header timestamps carry no offset; they are interpreted in {@code zone} (edge #17). */
    public static Optional<Instant> toInstant(String timestamp, ZoneId zone) {
        try {
            return Optional.of(LocalDateTime.parse(timestamp, TS).atZone(zone).toInstant());
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }
}
