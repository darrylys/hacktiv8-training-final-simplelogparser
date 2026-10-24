package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.Fixtures;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.Scope;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.settings.SettingsStore;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.KeyCode;
import javafx.stage.Stage;
import org.fxmisc.richtext.StyleClassedTextArea;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.testfx.api.FxRobot;
import org.testfx.framework.junit5.ApplicationExtension;
import org.testfx.framework.junit5.Start;
import org.testfx.util.WaitForAsyncUtils;

import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC19, AC13, AC20 smoke tests (run with {@code -Pgui}): open a file, run field, text and regex searches,
 * select an entry, see highlights, copy with Ctrl+C, cancel a long scan, and show a 16 MB entry (TRD 2.3).
 */
@Tag("gui")
@ExtendWith(ApplicationExtension.class)
class SearchFlowGuiTest {

    @TempDir
    static Path dir;

    private final ExecutorService background = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r);
        t.setDaemon(true);
        return t;
    });
    private MainWindow window;

    @Start
    void start(Stage stage) throws Exception {
        window = new MainWindow(stage, new SettingsStore(dir.resolve("settings")).load(), new ScanEngine(), background);
        stage.show();
    }

    @AfterEach
    void tearDown(FxRobot robot) {
        robot.interact(window::closeAll);
        background.shutdownNow();
    }

    private FileTab open(FxRobot robot, Path file) throws Exception {
        robot.interact(() -> window.open(file));
        waitFor(() -> !window.tabs().getTabs().isEmpty(), 10);
        return on(robot, () -> (FileTab) window.tabs().getSelectionModel().getSelectedItem());
    }

    private static <T> T on(FxRobot robot, Callable<T> c) throws Exception {
        return WaitForAsyncUtils.asyncFx(c).get(10, TimeUnit.SECONDS);
    }

    private static void waitFor(Callable<Boolean> condition, int seconds) throws Exception {
        WaitForAsyncUtils.waitFor(seconds, TimeUnit.SECONDS, () -> WaitForAsyncUtils.asyncFx(condition).get());
    }

    private static void awaitScan(FileTab tab) throws Exception {
        waitFor(() -> !tab.controller().runningProperty().get() && tab.controller().summaryProperty().get() != null, 20);
    }

    @Test
    void fieldTextAndRegexSearchesWithHighlightAndCopy(FxRobot robot) throws Exception {
        FileTab tab = open(robot, Fixtures.path("plain/crlf.log"));

        // field search through the UI controls
        robot.clickOn(".field-name").write("merchantId");
        robot.clickOn(".field-values").write("786");
        robot.clickOn("#search");
        awaitScan(tab);
        assertThat(on(robot, () -> tab.controller().hits().stream().map(Hit::lineNumber).toList()))
                .containsExactly(1L, 20L, 26L);
        assertThat(robot.lookup("#hit-count").queryLabeled().getText()).isEqualTo("3 hits");

        // select the first entry: detail shows it with the field value highlighted
        robot.interact(() -> tab.results().list().getSelectionModel().select(0));
        StyleClassedTextArea area = tab.detail().area();
        waitFor(() -> area.getText().startsWith("[corrA1:"), 10);
        waitFor(() -> hasMatchStyle(area), 5);

        // Ctrl+C on the result list puts the original entry (CRLF) on the clipboard
        robot.clickOn("#results");
        robot.interact(() -> tab.results().list().getSelectionModel().select(0));
        robot.press(KeyCode.CONTROL).press(KeyCode.C).release(KeyCode.C).release(KeyCode.CONTROL);
        String crlf = Files.readString(Fixtures.path("plain/crlf.log"));
        String expected = crlf.substring(0, crlf.indexOf("[corrB1:"));
        waitFor(() -> expected.equals(Clipboard.getSystemClipboard().getString()), 5);

        // text search in the request scope
        robot.interact(() -> {
            tab.viewModel().fieldRows().get(0).fieldProperty().set("");
            tab.viewModel().fieldRows().get(0).valuesProperty().set("");
            tab.viewModel().textProperty().set("orderId=2837537");
            tab.viewModel().scopeProperty().set(Scope.REQUEST);
        });
        robot.clickOn("#search");
        awaitScan(tab);
        assertThat(on(robot, () -> tab.controller().hits().stream().map(Hit::lineNumber).toList())).containsExactly(1L);

        // invalid regex: inline message, Search disabled, nothing started
        long generation = tab.controller().generation();
        robot.interact(() -> {
            tab.viewModel().scopeProperty().set(Scope.ALL);
            tab.viewModel().regexProperty().set(true);
            tab.viewModel().textProperty().set("status\":(5");
        });
        assertThat(robot.lookup("#validation").queryLabeled().getText()).contains("Invalid regex");
        assertThat(robot.lookup("#search").queryAs(Button.class).isDisabled()).isTrue();
        assertThat(tab.controller().generation()).isEqualTo(generation);

        // valid regex across lines of one entry
        robot.interact(() -> tab.viewModel().textProperty().set("Unhandled exception\\nexample"));
        robot.clickOn("#search");
        awaitScan(tab);
        assertThat(on(robot, () -> tab.controller().hits().stream().map(Hit::lineNumber).toList())).containsExactly(20L);
    }

    @Test
    void cancelStopsALongScanWithinTwoSeconds(FxRobot robot) throws Exception {
        Path evil = dir.resolve("evil.log");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 300; i++) {
            sb.append("[a:b:] 2026-10-18 00:00:00.000 [t] INFO c - ").append("a".repeat(80)).append('\n');
        }
        Files.writeString(evil, sb);
        FileTab tab = open(robot, evil);
        robot.interact(() -> {
            tab.viewModel().regexProperty().set(true);
            tab.viewModel().textProperty().set("(.*a){12}x");
        });
        robot.clickOn("#search");
        waitFor(() -> tab.controller().runningProperty().get(), 5);
        Thread.sleep(300);
        long t0 = System.nanoTime();
        robot.clickOn("#cancel");
        awaitScan(tab);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0)).isLessThan(2000);
        assertThat(on(robot, () -> tab.controller().summaryProperty().get().cancelled())).isTrue();
        assertThat(robot.lookup("#status").queryLabeled().getText()).contains("cancelled");
    }

    @Test
    void sixteenMegabyteEntryRendersWithoutFreezing(FxRobot robot) throws Exception {
        Path big = dir.resolve("big.log");
        try (BufferedWriter w = Files.newBufferedWriter(big, StandardCharsets.UTF_8)) {
            w.write("[a:b:merchantId: 1] 2026-10-18 00:00:00.000 [t] INFO c - huge body follows\n");
            String line = "|< " + "0123456789abcdef".repeat(64) + " NEEDLE\n";
            for (int i = 0; i < 16 * 1024 * 1024 / line.length(); i++) {
                w.write(line);
            }
        }
        FileTab tab = open(robot, big);
        robot.interact(() -> tab.viewModel().textProperty().set("needle"));
        robot.clickOn("#search");
        awaitScan(tab);
        long t0 = System.nanoTime();
        robot.interact(() -> tab.results().list().getSelectionModel().select(0));
        waitFor(() -> tab.detail().area().getLength() > 15 * 1024 * 1024, 30);
        long shownMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0);
        // the FX thread still answers promptly afterwards
        long ping = System.nanoTime();
        on(robot, () -> true);
        long pingMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - ping);
        System.out.printf("16 MB entry shown in %d ms, FX thread ping %d ms%n", shownMillis, pingMillis);
        assertThat(pingMillis).isLessThan(1000);
        assertThat(on(robot, () -> hasMatchStyle(tab.detail().area()))).isTrue();
    }

    private static boolean hasMatchStyle(StyleClassedTextArea area) {
        int len = Math.min(area.getLength(), 200_000);
        return area.getStyleSpans(0, len).stream()
                .map(s -> (Collection<String>) s.getStyle())
                .anyMatch(style -> style.contains("match"));
    }
}
