package id.project.logs.simplelogparser.model;

import java.util.Locale;

/** Log level. Parsed case-insensitively; anything else is {@link #UNKNOWN} (A5). */
public enum Level {
    TRACE,
    DEBUG,
    INFO,
    WARN,
    ERROR,
    FATAL,
    UNKNOWN;

    public static Level parse(String text) {
        if (text == null) {
            return UNKNOWN;
        }
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (t.equals("WARNING")) {
            return WARN;
        }
        for (Level level : values()) {
            if (level != UNKNOWN && level.name().equals(t)) {
                return level;
            }
        }
        return UNKNOWN;
    }
}
