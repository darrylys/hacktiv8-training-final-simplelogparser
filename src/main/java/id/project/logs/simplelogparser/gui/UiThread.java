package id.project.logs.simplelogparser.gui;

import javafx.application.Platform;

/**
 * Where view-model updates run. Production code uses the FX application thread; tests run updates inline,
 * so view-models and controllers are testable without a running toolkit.
 */
@FunctionalInterface
public interface UiThread {

    void runLater(Runnable action);

    static UiThread fx() {
        return Platform::runLater;
    }

    static UiThread direct() {
        return Runnable::run;
    }
}
