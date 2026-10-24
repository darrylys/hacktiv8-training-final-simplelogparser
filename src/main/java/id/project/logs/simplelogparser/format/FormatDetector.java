package id.project.logs.simplelogparser.format;

import id.project.logs.simplelogparser.format.json.JsonEntryParser;
import id.project.logs.simplelogparser.format.plain.PlainFormatHandler;
import id.project.logs.simplelogparser.io.ByteLine;
import id.project.logs.simplelogparser.io.ByteLineReader;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.LogFormat;

import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Detects the format from the start of the file (A6): at most the first 64 KB or 50 non-empty lines.
 * JSON if the first non-empty line starts with {@code {} and parses as a JSON object; plain if any inspected
 * line passes the strict plain header check (leading non-header lines allowed, edge #3). Otherwise
 * detection fails (AC1); it never guesses.
 */
public final class FormatDetector {

    static final int MAX_BYTES = 64 * 1024;
    static final int MAX_LINES = 50;

    private FormatDetector() {
    }

    public static LogFormat detect(LogFile file, Charset charset) throws IOException, FormatDetectionException {
        if (file.sizeAtOpen() == 0) {
            throw new FormatDetectionException("file is empty");
        }
        long limit = Math.min(file.sizeAtOpen(), MAX_BYTES);
        ByteLineReader reader = new ByteLineReader(file, MAX_BYTES + 16);
        reader.reset(0, limit);
        PlainFormatHandler plain = new PlainFormatHandler(charset);
        TextDecoder decoder = new TextDecoder(charset);
        int nonEmpty = 0;
        ByteLine line;
        while (nonEmpty < MAX_LINES && (line = reader.next()) != null) {
            if (line.continuation() || line.isBlank()) {
                continue;
            }
            nonEmpty++;
            if (nonEmpty == 1 && looksLikeJson(line, decoder)) {
                return LogFormat.JSON;
            }
            if (plain.isEntryStart(line)) {
                return LogFormat.PLAIN;
            }
        }
        if (nonEmpty == 0) {
            throw new FormatDetectionException("file contains no log entries");
        }
        throw new FormatDetectionException("unrecognized log format: no JSON object on the first line and no "
                + "Spring Boot log header in the first " + nonEmpty + " non-empty lines (use --format to override)");
    }

    private static boolean looksLikeJson(ByteLine line, TextDecoder decoder) {
        String text = decoder.decode(line.bytes(), line.start(), line.contentLength(), line.offset()).strip();
        if (!text.startsWith("{")) {
            return false;
        }
        try {
            JsonEntryParser.parse(text);
            return true;
        } catch (MalformedEntryException e) {
            return false;
        }
    }
}
