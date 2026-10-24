package id.project.logs.simplelogparser.query;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * Parses {@code --from} / {@code --to} values: ISO-8601 with or without an offset
 * ({@code 2026-10-18T00:00}, {@code 2026-10-18T00:00:00+07:00}, {@code ...Z}), or a date alone. Values
 * without an offset are interpreted in {@code zone} (section 5.4, A20). A space may replace the {@code T}.
 */
public final class TimeParser {

    private TimeParser() {
    }

    public static Instant parse(String text, ZoneId zone) {
        String t = text.trim();
        if (t.length() > 10 && t.charAt(10) == ' ') {
            t = t.substring(0, 10) + 'T' + t.substring(11);
        }
        try {
            return OffsetDateTime.parse(t).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next form
        }
        try {
            return LocalDateTime.parse(t).atZone(zone).toInstant();
        } catch (DateTimeParseException ignored) {
            // try the next form
        }
        try {
            return LocalDate.parse(t).atStartOfDay(zone).toInstant();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("invalid time '" + text
                    + "' (expected ISO-8601, e.g. 2026-10-18T00:00 or 2026-10-18T00:00:00+07:00)");
        }
    }
}
