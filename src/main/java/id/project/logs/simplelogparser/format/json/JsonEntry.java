package id.project.logs.simplelogparser.format.json;

import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.Scope;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * JSON-per-line entry. Built-in names {@code level}, {@code thread}, {@code logger} and {@code timestamp}
 * are aliases of the ECS keys, which also work under their own names. Match text is the decoded
 * {@code message}, a {@code \n}, then the raw line (edge #9). JSON has no request/response sections.
 */
public final class JsonEntry implements LogEntry {

    static final String TIMESTAMP_KEY = "@timestamp";
    static final String LEVEL_KEY = "log.level";
    static final String THREAD_KEY = "process.thread.name";
    static final String LOGGER_KEY = "log.logger";
    static final String MESSAGE_KEY = "message";

    private static final Map<String, String> ALIASES = Map.of(
            "level", LEVEL_KEY,
            "thread", THREAD_KEY,
            "logger", LOGGER_KEY,
            "timestamp", TIMESTAMP_KEY);

    private final long offset;
    private final boolean truncated;
    private final String rawText;
    private final Map<String, String> fields;
    private final Level level;
    private String matchText;
    private int rawLineStart;
    private Optional<Instant> timestamp;

    JsonEntry(long offset, boolean truncated, String rawText, Map<String, String> fields) {
        this.offset = offset;
        this.truncated = truncated;
        this.rawText = rawText;
        this.fields = fields;
        this.level = Level.parse(fields.get(LEVEL_KEY));
    }

    public String message() {
        return fields.getOrDefault(MESSAGE_KEY, "");
    }

    @Override
    public long offset() {
        return offset;
    }

    @Override
    public String rawText() {
        return rawText;
    }

    @Override
    public String matchText() {
        if (matchText == null) {
            String line = rawText;
            if (line.endsWith("\n")) {
                line = line.substring(0, line.length() - 1);
            }
            if (line.endsWith("\r")) {
                line = line.substring(0, line.length() - 1);
            }
            String message = TextDecoder.normalizeLineEndings(message());
            rawLineStart = message.length() + 1;
            matchText = message + "\n" + TextDecoder.normalizeLineEndings(line);
        }
        return matchText;
    }

    /** Offset within {@link #matchText()} where the raw line starts. */
    public int rawLineStart() {
        matchText();
        return rawLineStart;
    }

    @Override
    public Optional<Instant> timestamp() {
        if (timestamp == null) {
            timestamp = parseInstant(fields.get(TIMESTAMP_KEY));
        }
        return timestamp;
    }

    static Optional<Instant> parseInstant(String text) {
        if (text == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(text));
        } catch (DateTimeParseException e) {
            try {
                return Optional.of(OffsetDateTime.parse(text).toInstant());
            } catch (DateTimeParseException e2) {
                return Optional.empty();
            }
        }
    }

    @Override
    public Level level() {
        return level;
    }

    @Override
    public Optional<String> field(String name) {
        String value = fields.get(name);
        if (value == null) {
            String alias = ALIASES.get(name);
            if (alias != null) {
                value = fields.get(alias);
            }
        }
        return Optional.ofNullable(value);
    }

    @Override
    public Set<String> fieldNames() {
        return fields.keySet();
    }

    @Override
    public CharSequence section(Scope scope) {
        return scope == Scope.ALL ? matchText() : "";
    }

    @Override
    public int sectionStart(Scope scope) {
        return scope == Scope.ALL ? 0 : -1;
    }

    @Override
    public boolean truncated() {
        return truncated;
    }
}
