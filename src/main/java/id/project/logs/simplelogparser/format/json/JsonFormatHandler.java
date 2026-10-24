package id.project.logs.simplelogparser.format.json;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.MalformedEntryException;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.ByteLine;
import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.HitSummary;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;

import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ECS JSON-per-line format. Every non-blank line starts an entry; blank lines are skipped silently. A line
 * that fails to parse throws {@link MalformedEntryException} (edge #8, AC22).
 */
public final class JsonFormatHandler implements FormatHandler {

    private static final String[] KEY_IDS = {"requestId", "correlationId", "traceId"};

    private final TextDecoder decoder;

    public JsonFormatHandler(Charset charset) {
        this.decoder = new TextDecoder(charset);
    }

    @Override
    public LogFormat format() {
        return LogFormat.JSON;
    }

    @Override
    public boolean isEntryStart(ByteLine line) {
        return !line.isBlank();
    }

    @Override
    public boolean isSkippable(ByteLine line) {
        return line.isBlank();
    }

    @Override
    public LogEntry parse(RawEntry raw, ParseContext ctx) throws MalformedEntryException {
        String text = decoder.decode(raw.bytes(), 0, raw.length(), raw.offset());
        Map<String, String> fields = JsonEntryParser.parse(text);
        return new JsonEntry(raw.offset(), raw.truncated(), text, fields);
    }

    @Override
    public HitSummary summarize(LogEntry entry) {
        Map<String, String> ids = new LinkedHashMap<>();
        for (String key : KEY_IDS) {
            entry.field(key).ifPresent(v -> ids.put(key, v));
        }
        String message = entry instanceof JsonEntry json ? json.message() : entry.rawText();
        return new HitSummary(
                entry.field(JsonEntry.TIMESTAMP_KEY).orElse(""),
                entry.level(),
                entry.field(JsonEntry.LOGGER_KEY).orElse(""),
                ids,
                HitSummary.firstLineOf(message));
    }
}
