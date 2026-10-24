package id.project.logs.simplelogparser.format.json;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import id.project.logs.simplelogparser.format.MalformedEntryException;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Streaming parse of one JSON line into top-level fields (A9). Scalars become their string text
 * ({@code "147581"} and {@code 147581} both become {@code 147581}); booleans become {@code true}/{@code false};
 * {@code null} means the field is absent. Object and array values become their compact raw JSON; nested
 * keys are not individually addressable. The first of duplicate keys wins.
 */
public final class JsonEntryParser {

    private static final JsonFactory FACTORY = JsonFactory.builder().build();

    private JsonEntryParser() {
    }

    public static Map<String, String> parse(String text) throws MalformedEntryException {
        try (JsonParser p = FACTORY.createParser(text)) {
            if (p.nextToken() != JsonToken.START_OBJECT) {
                throw new MalformedEntryException("not a JSON object");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            JsonToken t;
            while ((t = p.nextToken()) == JsonToken.FIELD_NAME) {
                String name = p.currentName();
                String value = valueText(p, p.nextToken(), text);
                if (value != null) {
                    fields.putIfAbsent(name, value);
                }
            }
            if (t != JsonToken.END_OBJECT) {
                throw new MalformedEntryException("unterminated JSON object");
            }
            if (p.nextToken() != null) {
                throw new MalformedEntryException("trailing content after JSON object");
            }
            return fields;
        } catch (IOException e) {
            throw new MalformedEntryException(e.getMessage(), e);
        }
    }

    private static String valueText(JsonParser p, JsonToken token, String text)
            throws IOException, MalformedEntryException {
        if (token == null) {
            throw new MalformedEntryException("truncated JSON object");
        }
        return switch (token) {
            case VALUE_STRING, VALUE_NUMBER_INT, VALUE_NUMBER_FLOAT -> p.getText();
            case VALUE_TRUE -> "true";
            case VALUE_FALSE -> "false";
            case VALUE_NULL -> null;
            case START_OBJECT, START_ARRAY -> {
                int start = (int) p.currentTokenLocation().getCharOffset();
                p.skipChildren();
                int end = (int) p.currentLocation().getCharOffset();
                yield compact(text, start, end);
            }
            default -> throw new MalformedEntryException("unexpected token " + token);
        };
    }

    /** Removes insignificant whitespace (outside strings) from {@code text[start, end)}. */
    static String compact(String text, int start, int end) {
        StringBuilder sb = new StringBuilder(end - start);
        boolean inString = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (inString) {
                sb.append(c);
                if (c == '\\' && i + 1 < end) {
                    sb.append(text.charAt(++i));
                } else if (c == '"') {
                    inString = false;
                }
            } else if (c == '"') {
                inString = true;
                sb.append(c);
            } else if (c != ' ' && c != '\t' && c != '\n' && c != '\r') {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
