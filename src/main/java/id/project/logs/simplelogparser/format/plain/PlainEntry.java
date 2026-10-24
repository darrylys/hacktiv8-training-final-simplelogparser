package id.project.logs.simplelogparser.format.plain;

import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.RawEntry;
import id.project.logs.simplelogparser.model.Scope;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Plain-format entry. Header fields are parsed eagerly; the full text, match text and sections are
 * decoded on first use. Built-in fields: {@code level}, {@code thread}, {@code logger} (abbreviated, as
 * written) and {@code timestamp} (the original text). Built-ins win over MDC keys of the same name.
 */
public final class PlainEntry implements LogEntry {

    private final RawEntry raw;
    private final TextDecoder decoder;
    private final ZoneId zone;
    private final PlainHeaderParser.Header header;
    private final Map<String, String> fields;
    private final Level level;

    private String rawText;
    private String matchText;
    private SectionSplitter.Sections sections;
    private Optional<Instant> timestamp;

    PlainEntry(RawEntry raw, TextDecoder decoder, ZoneId zone, PlainHeaderParser.Header header) {
        this.raw = raw;
        this.decoder = decoder;
        this.zone = zone;
        this.header = header;
        this.level = Level.parse(header.level());
        Map<String, String> f = new LinkedHashMap<>();
        f.put("level", header.level());
        f.put("thread", header.thread());
        f.put("logger", header.logger());
        f.put("timestamp", header.timestamp());
        MdcParser.parse(header.mdc()).forEach(f::putIfAbsent);
        this.fields = Collections.unmodifiableMap(f);
    }

    public PlainHeaderParser.Header header() {
        return header;
    }

    @Override
    public long offset() {
        return raw.offset();
    }

    @Override
    public String rawText() {
        if (rawText == null) {
            rawText = decoder.decode(raw.bytes(), 0, raw.length(), raw.offset());
        }
        return rawText;
    }

    @Override
    public String matchText() {
        if (matchText == null) {
            matchText = TextDecoder.normalizeLineEndings(rawText());
        }
        return matchText;
    }

    @Override
    public Optional<Instant> timestamp() {
        if (timestamp == null) {
            timestamp = PlainHeaderParser.toInstant(header.timestamp(), zone);
        }
        return timestamp;
    }

    @Override
    public Level level() {
        return level;
    }

    @Override
    public Optional<String> field(String name) {
        return Optional.ofNullable(fields.get(name));
    }

    @Override
    public Set<String> fieldNames() {
        return fields.keySet();
    }

    @Override
    public CharSequence section(Scope scope) {
        int start = sectionStart(scope);
        if (start < 0) {
            return "";
        }
        String text = matchText();
        int end = scope == Scope.REQUEST ? sections().requestEnd() : sections().responseEnd();
        return scope == Scope.ALL ? text : text.subSequence(start, end);
    }

    @Override
    public int sectionStart(Scope scope) {
        return switch (scope) {
            case ALL -> 0;
            case REQUEST -> sections().requestStart();
            case RESPONSE -> sections().responseStart();
        };
    }

    private SectionSplitter.Sections sections() {
        if (sections == null) {
            sections = SectionSplitter.split(matchText());
        }
        return sections;
    }

    @Override
    public boolean truncated() {
        return raw.truncated();
    }
}
