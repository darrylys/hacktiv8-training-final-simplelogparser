package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.settings.SettingsStore;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.StackPane;
import javafx.stage.FileChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

/**
 * Main window: menus, one tab per opened file (edge #20), drag-and-drop, file chooser and recent files.
 */
public final class MainWindow {

    private final Stage stage;
    private final SettingsStore settings;
    private final ScanEngine engine;
    private final Executor background;
    private final TabPane tabs = new TabPane();
    private final Menu recent = new Menu("Open Recent");
    /** Detail-pane word wrap, shared by all tabs and remembered across runs. */
    private final BooleanProperty wordWrap;

    public MainWindow(Stage stage, SettingsStore settings, ScanEngine engine, Executor background) {
        this.stage = stage;
        this.settings = settings;
        this.engine = engine;
        this.background = background;
        this.wordWrap = new SimpleBooleanProperty(settings.wordWrap());
        wordWrap.addListener((o, a, on) -> {
            settings.setWordWrap(on);
            saveSettings();
        });

        tabs.setId("tabs");
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        Label placeholder = new Label("Open a log file (Ctrl+O) or drop it here");
        placeholder.getStyleClass().add("hint");
        placeholder.visibleProperty().bind(Bindings.isEmpty(tabs.getTabs()));
        StackPane center = new StackPane(tabs, placeholder);

        BorderPane root = new BorderPane(center);
        root.setTop(menuBar());
        Scene scene = new Scene(root, settings.windowWidth(1280), settings.windowHeight(800));
        scene.getStylesheets().add(MainWindow.class.getResource("slp.css").toExternalForm());
        scene.setOnDragOver(e -> {
            if (e.getDragboard().hasFiles()) {
                e.acceptTransferModes(TransferMode.COPY);
            }
            e.consume();
        });
        scene.setOnDragDropped(e -> {
            List<File> files = e.getDragboard().getFiles();
            files.forEach(f -> open(f.toPath()));
            e.setDropCompleted(!files.isEmpty());
            e.consume();
        });
        stage.setScene(scene);
        stage.setTitle("simplelogparser");
        rebuildRecent();
    }

    public TabPane tabs() {
        return tabs;
    }

    private MenuBar menuBar() {
        MenuItem open = new MenuItem("Open…");
        open.setAccelerator(KeyCombination.keyCombination("Shortcut+O"));
        open.setOnAction(e -> chooseFiles());
        MenuItem close = new MenuItem("Close Tab");
        close.setAccelerator(KeyCombination.keyCombination("Shortcut+W"));
        close.setOnAction(e -> closeCurrent());
        MenuItem exit = new MenuItem("Exit");
        exit.setOnAction(e -> Platform.exit());
        Menu file = new Menu("File", null, open, recent, new SeparatorMenuItem(), close, exit);

        MenuItem copy = new MenuItem("Copy");
        copy.setAccelerator(KeyCombination.keyCombination("Shortcut+C"));
        copy.setOnAction(e -> currentTab().ifPresent(FileTab::copy));
        MenuItem copyAll = new MenuItem("Copy All Results");
        copyAll.setAccelerator(KeyCombination.keyCombination("Shortcut+Shift+C"));
        copyAll.setOnAction(e -> currentTab().ifPresent(FileTab::copyAll));
        Menu edit = new Menu("Edit", null, copy, copyAll);
        return new MenuBar(file, edit);
    }

    private Optional<FileTab> currentTab() {
        Tab t = tabs.getSelectionModel().getSelectedItem();
        return t instanceof FileTab ft ? Optional.of(ft) : Optional.empty();
    }

    private void chooseFiles() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("Open log file");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Log files", "*.log", "*.txt", "*.json", "*.jsonl"),
                new FileChooser.ExtensionFilter("All files", "*.*"));
        settings.recentFiles().stream().findFirst().map(Path::getParent).map(Path::toFile)
                .filter(File::isDirectory).ifPresent(chooser::setInitialDirectory);
        List<File> files = chooser.showOpenMultipleDialog(stage);
        if (files != null) {
            files.forEach(f -> open(f.toPath()));
        }
    }

    /** Opens {@code path} in a new tab; detection runs in the background, failures show a dialog. */
    public void open(Path path) {
        Task<FileTab.Opened> task = new Task<>() {
            @Override
            protected FileTab.Opened call() throws Exception {
                return FileTab.open(path);
            }
        };
        task.setOnSucceeded(e -> {
            FileTab tab = new FileTab(task.getValue(), engine, background, settings::resultCap, wordWrap);
            tabs.getTabs().add(tab);
            tabs.getSelectionModel().select(tab);
            settings.addRecent(path);
            rebuildRecent();
            saveSettings();
        });
        task.setOnFailed(e -> ErrorDialogs.show(stage, "Cannot open " + path.getFileName(), task.getException()));
        background.execute(task);
    }

    private void closeCurrent() {
        currentTab().ifPresent(t -> {
            tabs.getTabs().remove(t);
            t.close();
        });
    }

    /** Cancels all scans and closes all files. */
    public void closeAll() {
        for (Tab t : List.copyOf(tabs.getTabs())) {
            if (t instanceof FileTab ft) {
                ft.close();
            }
        }
        tabs.getTabs().clear();
    }

    private void rebuildRecent() {
        recent.getItems().clear();
        for (Path p : settings.recentFiles()) {
            MenuItem item = new MenuItem(p.toString());
            item.setMnemonicParsing(false);
            item.setOnAction(e -> open(p));
            recent.getItems().add(item);
        }
        recent.setDisable(recent.getItems().isEmpty());
    }

    void saveSettings() {
        settings.setWindowSize((int) stage.getWidth(), (int) stage.getHeight());
        try {
            settings.save();
        } catch (IOException e) {
            // preferences are a convenience; never interrupt the user over them
            System.getLogger(MainWindow.class.getName()).log(System.Logger.Level.DEBUG, "settings not saved", e);
        }
    }
}
