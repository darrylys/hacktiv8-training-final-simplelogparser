package id.project.logs.simplelogparser.cli;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteFeature;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.scan.EntryReader;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

/**
 * One JSON object per hit and line (AC18): {@code {"file","offset","line","entry","truncated"}}. {@code entry}
 * is the original entry text without its final line terminator, cut at the entry limit when
 * {@code truncated} is true. All content is escaped by the generator.
 */
public final class JsonPrinter implements HitPrinter {

    private static final JsonFactory FACTORY = JsonFactory.builder()
            .disable(StreamWriteFeature.AUTO_CLOSE_TARGET)
            .build();

    private final OutputStream out;
    private final String file;
    private final Charset charset;

    public JsonPrinter(OutputStream out, String file, Charset charset) {
        this.out = out;
        this.file = file;
        this.charset = charset;
    }

    @Override
    public void print(Hit hit, EntryReader reader) throws IOException {
        byte[] bytes = reader.readBytes(hit);
        int len = bytes.length;
        if (!hit.truncated()) {
            if (len > 0 && bytes[len - 1] == '\n') {
                len--;
            }
            if (len > 0 && bytes[len - 1] == '\r') {
                len--;
            }
        }
        String entry = new String(bytes, 0, len, charset);
        if (hit.offset() == 0 && entry.startsWith("﻿")) {
            entry = entry.substring(1);
        }
        try (JsonGenerator gen = FACTORY.createGenerator(out)) {
            gen.writeStartObject();
            gen.writeStringField("file", file);
            gen.writeNumberField("offset", hit.offset());
            gen.writeNumberField("line", hit.lineNumber());
            gen.writeStringField("entry", entry);
            gen.writeBooleanField("truncated", hit.truncated());
            gen.writeEndObject();
        }
        out.write('\n');
    }
}
