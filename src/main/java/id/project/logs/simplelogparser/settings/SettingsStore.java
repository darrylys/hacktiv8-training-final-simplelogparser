package id.project.logs.simplelogparser.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * User settings in {@code ${user.home}/.simplelogparser/settings.properties} (A13): recent files (max 10),
 * last window size, result cap and detail-pane word wrap. Holds paths only, never log data (PRD 6). This is
 * the only class allowed to open a file for writing; it writes a temp file in the settings directory and
 * moves it into place.
 */
public final class SettingsStore {

    public static final int MAX_RECENT = 10;
    public static final int DEFAULT_RESULT_CAP = 10_000;

    private static final String RECENT_PREFIX = "recent.";
    private static final String WIDTH = "window.width";
    private static final String HEIGHT = "window.height";
    private static final String RESULT_CAP = "resultCap";
    private static final String WORD_WRAP = "detail.wordWrap";

    private final Path file;
    private final Properties props = new Properties();

    public SettingsStore(Path directory) {
        this.file = directory.resolve("settings.properties");
    }

    public static SettingsStore forUser() {
        return new SettingsStore(Path.of(System.getProperty("user.home"), ".simplelogparser"));
    }

    public SettingsStore load() throws IOException {
        props.clear();
        if (Files.isRegularFile(file)) {
            try (InputStream in = Files.newInputStream(file)) {
                props.load(in);
            }
        }
        return this;
    }

    public void save() throws IOException {
        Path dir = file.getParent();
        Files.createDirectories(dir);
        Path tmp = Files.createTempFile(dir, "settings", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(tmp)) {
                props.store(out, "simplelogparser settings");
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    public List<Path> recentFiles() {
        List<Path> out = new ArrayList<>();
        for (int i = 0; i < MAX_RECENT; i++) {
            String v = props.getProperty(RECENT_PREFIX + i);
            if (v != null && !v.isBlank()) {
                out.add(Path.of(v));
            }
        }
        return out;
    }

    /** Moves {@code path} to the front of the recent list. */
    public void addRecent(Path path) {
        Path abs = path.toAbsolutePath().normalize();
        List<Path> recent = recentFiles();
        recent.remove(abs);
        recent.add(0, abs);
        for (int i = 0; i < MAX_RECENT; i++) {
            if (i < recent.size()) {
                props.setProperty(RECENT_PREFIX + i, recent.get(i).toString());
            } else {
                props.remove(RECENT_PREFIX + i);
            }
        }
    }

    public int resultCap() {
        return intProp(RESULT_CAP, DEFAULT_RESULT_CAP);
    }

    public void setResultCap(int cap) {
        props.setProperty(RESULT_CAP, Integer.toString(cap));
    }

    /** Whether the GUI detail pane wraps long lines; off by default. */
    public boolean wordWrap() {
        return Boolean.parseBoolean(props.getProperty(WORD_WRAP, "false").trim());
    }

    public void setWordWrap(boolean on) {
        props.setProperty(WORD_WRAP, Boolean.toString(on));
    }

    public int windowWidth(int fallback) {
        return intProp(WIDTH, fallback);
    }

    public int windowHeight(int fallback) {
        return intProp(HEIGHT, fallback);
    }

    public void setWindowSize(int width, int height) {
        props.setProperty(WIDTH, Integer.toString(width));
        props.setProperty(HEIGHT, Integer.toString(height));
    }

    private int intProp(String key, int fallback) {
        try {
            return Integer.parseInt(props.getProperty(key, Integer.toString(fallback)).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
