package id.project.logs.simplelogparser.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class SettingsStoreTest {

    @Test
    void recentFilesRoundTripAndAreCapped(@TempDir Path dir) throws Exception {
        Path settingsDir = dir.resolve(".simplelogparser");
        SettingsStore store = new SettingsStore(settingsDir).load();
        for (int i = 0; i < 12; i++) {
            store.addRecent(dir.resolve("log" + i + ".log"));
        }
        store.addRecent(dir.resolve("log5.log"));
        store.setResultCap(500);
        store.setWindowSize(1200, 800);
        store.setWordWrap(true);
        store.save();

        SettingsStore reloaded = new SettingsStore(settingsDir).load();
        assertThat(reloaded.recentFiles()).hasSize(SettingsStore.MAX_RECENT);
        assertThat(reloaded.recentFiles().get(0)).isEqualTo(dir.resolve("log5.log").toAbsolutePath().normalize());
        assertThat(reloaded.recentFiles().get(1)).isEqualTo(dir.resolve("log11.log").toAbsolutePath().normalize());
        assertThat(reloaded.resultCap()).isEqualTo(500);
        assertThat(reloaded.windowWidth(0)).isEqualTo(1200);
        assertThat(reloaded.wordWrap()).isTrue();
        try (Stream<Path> files = Files.list(settingsDir)) {
            assertThat(files.map(p -> p.getFileName().toString())).containsExactly("settings.properties");
        }
    }

    @Test
    void defaultsWithoutAFile(@TempDir Path dir) throws Exception {
        SettingsStore store = new SettingsStore(dir.resolve("none")).load();
        assertThat(store.recentFiles()).isEmpty();
        assertThat(store.resultCap()).isEqualTo(SettingsStore.DEFAULT_RESULT_CAP);
        assertThat(store.wordWrap()).isFalse();
    }
}
