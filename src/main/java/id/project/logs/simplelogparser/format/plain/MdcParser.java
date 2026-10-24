package id.project.logs.simplelogparser.format.plain;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lenient MDC parser (edge #5). The MDC is split on the first {@code :} into token 1 and the rest, then on
 * the next {@code :} into token 2 and the remainder. A token is a positional id only if it contains no
 * space and is not the key of a {@code "key: value"} pair; token 1 is {@code correlationId}, token 2
 * {@code requestId}. Empty positional tokens yield no field (A7). The remainder is split on {@code ", "}
 * into {@code key: value} pairs; parts without {@code ": "} are ignored; the first of duplicate keys wins.
 */
public final class MdcParser {

    public static final String CORRELATION_ID = "correlationId";
    public static final String REQUEST_ID = "requestId";

    private MdcParser() {
    }

    public static Map<String, String> parse(String mdc) {
        Map<String, String> out = new LinkedHashMap<>();
        String rest = mdc;
        int c1 = mdc.indexOf(':');
        if (c1 < 0) {
            if (isPositional(mdc, mdc, mdc.length())) {
                putId(out, CORRELATION_ID, mdc);
                rest = "";
            }
        } else if (isPositional(mdc.substring(0, c1), mdc, c1)) {
            putId(out, CORRELATION_ID, mdc.substring(0, c1));
            rest = mdc.substring(c1 + 1);
            int c2 = rest.indexOf(':');
            if (c2 < 0) {
                if (isPositional(rest, rest, rest.length())) {
                    putId(out, REQUEST_ID, rest);
                    rest = "";
                }
            } else if (isPositional(rest.substring(0, c2), rest, c2)) {
                putId(out, REQUEST_ID, rest.substring(0, c2));
                rest = rest.substring(c2 + 1);
            }
        }
        parsePairs(rest, out);
        return out;
    }

    private static boolean isPositional(String token, String source, int colon) {
        if (token.indexOf(' ') >= 0) {
            return false;
        }
        // in "merchantId: 786" the colon belongs to a key/value pair, not to a positional separator
        return !(colon + 1 < source.length() && source.charAt(colon + 1) == ' ');
    }

    private static void putId(Map<String, String> out, String key, String value) {
        if (!value.isEmpty()) {
            out.putIfAbsent(key, value);
        }
    }

    private static void parsePairs(String rest, Map<String, String> out) {
        if (rest.isBlank()) {
            return;
        }
        int from = 0;
        while (true) {
            int sep = rest.indexOf(", ", from);
            String part = sep < 0 ? rest.substring(from) : rest.substring(from, sep);
            int kv = part.indexOf(": ");
            if (kv >= 0) {
                String key = part.substring(0, kv).trim();
                String value = part.substring(kv + 2).trim();
                if (!key.isEmpty()) {
                    out.putIfAbsent(key, value);
                }
            }
            if (sep < 0) {
                return;
            }
            from = sep + 2;
        }
    }
}
