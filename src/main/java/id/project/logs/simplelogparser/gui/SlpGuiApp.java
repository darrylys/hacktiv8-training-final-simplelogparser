package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.settings.SettingsStore;
import javafx.application.Application;
import javafx.stage.Stage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/** JavaFX application: loads settings, builds the main window and opens files given on the command line. */
public class SlpGuiApp extends Application {

    private final ExecutorService background = Executors.newCachedThreadPool(new ThreadFactory() {
        private final AtomicInteger n = new AtomicInteger();

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "slp-gui-bg-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        }
    });
    private MainWindow window;

    @Override
    public void start(Stage stage) {
        SettingsStore settings = SettingsStore.forUser();
        try {
            settings.load();
        } catch (IOException e) {
            // unreadable settings: start with defaults
            System.getLogger(SlpGuiApp.class.getName()).log(System.Logger.Level.DEBUG, "settings not loaded", e);
        }
        window = new MainWindow(stage, settings, new ScanEngine(), background);
        stage.setOnCloseRequest(e -> window.saveSettings());
        stage.show();
        for (String arg : getParameters().getRaw()) {
            window.open(Path.of(arg));
        }
    }

    @Override
    public void stop() {
        if (window != null) {
            window.closeAll();
        }
        background.shutdownNow();
    }
}
