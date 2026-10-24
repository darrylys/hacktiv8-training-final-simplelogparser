package id.project.logs.simplelogparser;

import id.project.logs.simplelogparser.cli.CliRunner;
import id.project.logs.simplelogparser.cli.TerminalDetector;
import id.project.logs.simplelogparser.gui.SlpGuiApp;
import javafx.application.Application;

import java.util.Arrays;

/**
 * Launcher. With no arguments, or with {@code --gui} plus optional file paths, it opens the GUI; otherwise
 * it runs the CLI and exits with its code. It does not extend {@code Application}, so JavaFX can start from
 * the classpath of the shaded jar. This is the only class that calls {@code System.exit}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || Arrays.asList(args).contains("--gui")) {
            String[] files = Arrays.stream(args).filter(a -> !a.equals("--gui")).toArray(String[]::new);
            Application.launch(SlpGuiApp.class, files);
            System.exit(0);
        }
        int code = new CliRunner(TerminalDetector.stdoutIsTerminal()).run(args, System.out, System.err);
        System.out.flush();
        System.exit(code);
    }
}
