package id.project.logs.simplelogparser.format;

import java.time.ZoneId;

/**
 * @param plainZone zone for plain-format timestamps, which carry no offset (edge #17)
 * @param strict    abort on malformed JSON lines instead of counting them
 */
public record ParseContext(ZoneId plainZone, boolean strict) {

    public static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Jakarta");

    public static ParseContext defaults() {
        return new ParseContext(DEFAULT_ZONE, false);
    }
}
