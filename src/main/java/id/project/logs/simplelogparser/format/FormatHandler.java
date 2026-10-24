package id.project.logs.simplelogparser.format;

import id.project.logs.simplelogparser.format.json.JsonFormatHandler;
import id.project.logs.simplelogparser.format.plain.PlainFormatHandler;
import id.project.logs.simplelogparser.io.ByteLine;
import id.project.logs.simplelogparser.model.HitSummary;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;

import java.nio.charset.Charset;

/** The format SPI. Adding a format means adding one implementation (PRD 9). Implementations are thread-safe. */
public interface FormatHandler {

    LogFormat format();

    /** Byte-level, strict check whether a line starts a new entry. Never called for continuation pieces. */
    boolean isEntryStart(ByteLine line);

    /** Lines that end the current entry but belong to no entry (JSON blank lines). */
    boolean isSkippable(ByteLine line);

    LogEntry parse(RawEntry raw, ParseContext ctx) throws MalformedEntryException;

    HitSummary summarize(LogEntry entry);

    static FormatHandler of(LogFormat format, Charset charset) {
        return switch (format) {
            case PLAIN -> new PlainFormatHandler(charset);
            case JSON -> new JsonFormatHandler(charset);
        };
    }
}
