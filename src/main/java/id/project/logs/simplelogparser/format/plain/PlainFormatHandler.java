package id.project.logs.simplelogparser.format.plain;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.HeaderlessEntry;
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
import java.util.Optional;

/** Spring Boot text format. Entry start = byte prefilter, then the full header regex (edge #4). */
public final class PlainFormatHandler implements FormatHandler {

    private static final String[] KEY_IDS = {"requestId", "correlationId", "traceId"};

    private final TextDecoder decoder;

    public PlainFormatHandler(Charset charset) {
        this.decoder = new TextDecoder(charset);
    }

    @Override
    public LogFormat format() {
        return LogFormat.PLAIN;
    }

    @Override
    public boolean isEntryStart(ByteLine line) {
        int bom = line.offset() == 0 ? TextDecoder.bomLength(line.bytes(), line.start(), line.contentLength()) : 0;
        if (!PlainHeaderParser.bytePrefilter(line, bom)) {
            return false;
        }
        String text = decoder.decode(line.bytes(), line.start() + bom, line.contentLength() - bom, -1);
        return PlainHeaderParser.matches(text);
    }

    @Override
    public boolean isSkippable(ByteLine line) {
        return false;
    }

    @Override
    public LogEntry parse(RawEntry raw, ParseContext ctx) {
        Optional<PlainHeaderParser.Header> header = PlainHeaderParser.parse(headerLine(raw));
        if (header.isEmpty()) {
            // cannot happen for framed entries; keep the entry matchable by text
            return new HeaderlessEntry(raw, decoder);
        }
        return new PlainEntry(raw, decoder, ctx.plainZone(), header.get());
    }

    private String headerLine(RawEntry raw) {
        byte[] b = raw.bytes();
        int end = 0;
        while (end < raw.length() && b[end] != '\n') {
            end++;
        }
        if (end > 0 && b[end - 1] == '\r') {
            end--;
        }
        return decoder.decode(b, 0, end, raw.offset());
    }

    @Override
    public HitSummary summarize(LogEntry entry) {
        if (!(entry instanceof PlainEntry plain)) {
            return HeaderlessEntry.summarize(entry);
        }
        PlainHeaderParser.Header h = plain.header();
        Map<String, String> ids = new LinkedHashMap<>();
        for (String key : KEY_IDS) {
            entry.field(key).ifPresent(v -> ids.put(key, v));
        }
        return new HitSummary(h.timestamp(), entry.level(), h.logger(), ids, HitSummary.firstLineOf(h.message()));
    }
}
