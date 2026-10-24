package id.project.logs.simplelogparser.gui;

import id.project.logs.simplelogparser.format.FormatDetectionException;
import id.project.logs.simplelogparser.scan.ScanException;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.NoSuchFileException;
import java.util.Locale;
import java.util.concurrent.CompletionException;

/** Maps failures to plain-language dialogs; never shows stack traces (edge #12). */
public final class ErrorDialogs {

    private ErrorDialogs() {
    }

    public static void show(Window owner, String title, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR, messageFor(error), ButtonType.OK);
        alert.initOwner(owner);
        alert.setTitle("simplelogparser");
        alert.setHeaderText(title);
        alert.showAndWait();
    }

    /** Asks before a large clipboard copy (AC21). */
    public static boolean confirmLargeCopy(Window owner, long bytes) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION,
                String.format(Locale.ROOT, "The results are %.1f MB. Copying them can take a while and use a lot of memory.",
                        bytes / (1024.0 * 1024.0)),
                ButtonType.OK, ButtonType.CANCEL);
        alert.initOwner(owner);
        alert.setTitle("simplelogparser");
        alert.setHeaderText("Copy all results to the clipboard?");
        return alert.showAndWait().filter(b -> b == ButtonType.OK).isPresent();
    }

    static String messageFor(Throwable error) {
        Throwable t = error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
        if (t instanceof NoSuchFileException e) {
            return "File not found: " + e.getFile();
        }
        if (t instanceof AccessDeniedException e) {
            return "Permission denied: " + e.getFile();
        }
        if (t instanceof FormatDetectionException e) {
            return "This file cannot be read as a log: " + e.getMessage() + ".";
        }
        if (t instanceof ScanException e) {
            return e.getMessage();
        }
        if (t instanceof IOException e) {
            return "The file could not be read: " + e.getMessage();
        }
        return t.getClass().getSimpleName() + (t.getMessage() == null ? "" : ": " + t.getMessage());
    }
}
