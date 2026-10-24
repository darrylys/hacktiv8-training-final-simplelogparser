package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.format.FormatDetectionException;
import id.project.logs.simplelogparser.format.FormatDetector;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.EntryBuffer;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.scan.EntryReader;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.scan.ScanSummary;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.collections.ListChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.Window;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import java.util.function.IntSupplier;

/**
 * One opened file: search inputs, result list, detail pane and status bar (TRD 7.2). Closing the tab
 * cancels its scan and closes the file.
 */
public final class FileTab extends Tab {

    /** A file opened read-only with its detected format. */
    public record Opened(Path path, LogFile file, LogFormat format) {
    }

    private final Opened opened;
    private final SearchViewModel vm = new SearchViewModel();
    private final ScanController controller;
    private final ResultListView results;
    private final DetailPane detail;
    private final ClipboardService clipboard;
    private final Label status = new Label();

    /**
     * Opens the file and detects its format; call off the FX thread. Errors are reported by the caller
     * (edge #12): missing or unreadable file, empty file, unknown format.
     */
    public static Opened open(Path path) throws IOException, FormatDetectionException {
        LogFile file = LogFile.open(path);
        try {
            if (file.sizeAtOpen() == 0) {
                throw new FormatDetectionException("file is empty");
            }
            return new Opened(path, file, FormatDetector.detect(file, StandardCharsets.UTF_8));
        } catch (IOException | FormatDetectionException | RuntimeException e) {
            file.close();
            throw e;
        }
    }

    public FileTab(Opened opened, ScanEngine engine, Executor background, IntSupplier resultCap,
                   BooleanProperty wordWrap) {
        this.opened = opened;
        Charset charset = StandardCharsets.UTF_8;
        ParseContext ctx = ParseContext.defaults();
        UiThread ui = UiThread.fx();
        EntryReader reader = new EntryReader(opened.file(), opened.format(), charset, ctx, EntryBuffer.DEFAULT_ENTRY_LIMIT);
        controller = new ScanController(engine, ui, opened.path(), opened.format(), ctx, resultCap);
        vm.formatProperty().set(opened.format());
        results = new ResultListView(controller.hits());
        detail = new DetailPane(reader, background, ui, MatchBudget.DEFAULT_TIMEOUT, wordWrap);
        clipboard = new ClipboardService(hit -> {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(hit.byteLength(), Integer.MAX_VALUE - 8));
            reader.copyTo(hit, out);
            return out.toByteArray();
        }, charset, text -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(text);
            Clipboard.getSystemClipboard().setContent(content);
        }, background, ui, ClipboardService.DEFAULT_THRESHOLD);

        setText(opened.path().getFileName().toString());
        setTooltip(new Tooltip(opened.path().toAbsolutePath() + " (" + opened.format().name().toLowerCase(Locale.ROOT) + ")"));

        SearchPane search = new SearchPane(vm, this::search);
        results.list().getSelectionModel().selectedItemProperty().addListener((o, a, hit) -> {
            if (hit != null) {
                detail.show(hit, controller.queryProperty().get());
            }
        });
        controller.hits().addListener((ListChangeListener<Hit>) c -> {
            if (controller.hits().isEmpty()) {
                detail.clear();
            }
        });

        SplitPane split = new SplitPane(results, detail);
        split.setOrientation(Orientation.HORIZONTAL);
        split.setDividerPositions(0.4);

        BorderPane root = new BorderPane(split);
        root.setTop(search);
        root.setBottom(statusBar());
        root.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                controller.cancel();
            }
        });
        setContent(root);
        setOnClosed(e -> close());
    }

    private HBox statusBar() {
        ProgressBar progress = new ProgressBar();
        progress.setId("progress");
        progress.progressProperty().bind(controller.progressProperty());
        progress.visibleProperty().bind(controller.runningProperty());
        Label hits = new Label();
        hits.setId("hit-count");
        hits.textProperty().bind(Bindings.createStringBinding(
                () -> StatusText.hits(controller.hits().size()), controller.hits()));
        Button cancel = new Button("Cancel");
        cancel.setId("cancel");
        cancel.disableProperty().bind(controller.runningProperty().not());
        cancel.setOnAction(e -> controller.cancel());
        status.setId("status");
        controller.summaryProperty().addListener((o, a, s) -> showSummary(s));
        controller.errorProperty().addListener((o, a, err) -> {
            if (!err.isEmpty()) {
                status.setText("Scan failed (results so far are incomplete): " + err);
            }
        });
        controller.runningProperty().addListener((o, a, running) -> {
            if (running) {
                status.setText("Searching…");
            }
        });
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox bar = new HBox(10, progress, hits, cancel, grow, status);
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setPadding(new Insets(4, 8, 4, 8));
        bar.getStyleClass().add("status-bar");
        return bar;
    }

    private void showSummary(ScanSummary s) {
        if (s == null) {
            results.showCapped(null);
            return;
        }
        results.showCapped(s.capped() ? controller.resultCap() : null);
        status.setText(StatusText.summary(s, controller.resultCap()));
    }

    /** Runs the current query; invalid input is already shown inline. */
    public void search() {
        SearchViewModel.Result r = vm.compile();
        if (!r.ok()) {
            status.setText(r.error());
            return;
        }
        detail.clear();
        controller.start(r.query());
    }

    public SearchViewModel viewModel() {
        return vm;
    }

    public ScanController controller() {
        return controller;
    }

    public ResultListView results() {
        return results;
    }

    public DetailPane detail() {
        return detail;
    }

    public Path path() {
        return opened.path();
    }

    /** Copies the detail-pane selection if it has focus, otherwise the selected entries. */
    public void copy() {
        String selected = detail.area().getSelectedText();
        if (detail.area().isFocused() && !selected.isEmpty()) {
            clipboard.copySelection(selected);
            return;
        }
        List<Hit> hits = new ArrayList<>(results.list().getSelectionModel().getSelectedItems());
        if (!hits.isEmpty()) {
            clipboard.copyEntries(hits).whenComplete((ok, err) -> report(ok, err, hits.size()));
        }
    }

    /** Copies all results, asking first when they are large (AC21). */
    public void copyAll() {
        List<Hit> hits = new ArrayList<>(controller.hits());
        Window owner = getTabPane() == null ? null : getTabPane().getScene().getWindow();
        clipboard.copyAll(hits, bytes -> ErrorDialogs.confirmLargeCopy(owner, bytes))
                .whenComplete((ok, err) -> report(ok, err, hits.size()));
    }

    private void report(Boolean ok, Throwable err, int count) {
        UiThread.fx().runLater(() -> {
            if (err != null) {
                status.setText("Copy failed: " + err.getMessage());
            } else if (Boolean.TRUE.equals(ok)) {
                status.setText("Copied " + count + (count == 1 ? " entry" : " entries") + " to the clipboard");
            }
        });
    }

    /** Cancels the scan and closes the file. */
    public void close() {
        controller.dispose();
        try {
            opened.file().close();
        } catch (IOException ignored) {
            // read-only channel
        }
    }
}
