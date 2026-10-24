package id.project.logs.simplelogparser.format.plain;

/**
 * Finds request/response sections in a plain entry's match text (edge #16, A8). A marker is a line equal,
 * after trimming, to {@code -- REQUEST --} or {@code -- RESPONSE --}. Request = lines after the first
 * request marker up to the first response marker after it (or entry end). Response = lines after the
 * first response marker to entry end. Sections keep their {@code |>} / {@code |<} prefixes, and are
 * returned as offset ranges into the match text, not copies.
 */
public final class SectionSplitter {

    static final String REQUEST_MARKER = "-- REQUEST --";
    static final String RESPONSE_MARKER = "-- RESPONSE --";

    /** Lines longer than this cannot be markers, so they are not trimmed and compared. */
    private static final int MAX_MARKER_LINE = 256;

    /** A start of -1 means the section is absent. */
    public record Sections(int requestStart, int requestEnd, int responseStart, int responseEnd) {
        static final Sections NONE = new Sections(-1, -1, -1, -1);
    }

    private SectionSplitter() {
    }

    public static Sections split(String text) {
        int reqStart = -1;
        int reqEnd = -1;
        int respStart = -1;
        int n = text.length();
        int lineStart = 0;
        while (lineStart < n) {
            int nl = text.indexOf('\n', lineStart);
            int lineEnd = nl < 0 ? n : nl;
            int next = nl < 0 ? n : nl + 1;
            if (lineEnd - lineStart <= MAX_MARKER_LINE) {
                String line = text.substring(lineStart, lineEnd).trim();
                if (reqStart < 0 && line.equals(REQUEST_MARKER)) {
                    reqStart = next;
                } else if (line.equals(RESPONSE_MARKER)) {
                    if (respStart < 0) {
                        respStart = next;
                    }
                    if (reqStart >= 0 && reqEnd < 0) {
                        reqEnd = lineStart;
                    }
                }
            }
            if (reqEnd >= 0 && respStart >= 0) {
                break;
            }
            lineStart = next;
        }
        if (reqStart < 0 && respStart < 0) {
            return Sections.NONE;
        }
        if (reqStart >= 0 && reqEnd < 0) {
            reqEnd = n;
        }
        return new Sections(reqStart, reqEnd, respStart, respStart < 0 ? -1 : n);
    }
}
