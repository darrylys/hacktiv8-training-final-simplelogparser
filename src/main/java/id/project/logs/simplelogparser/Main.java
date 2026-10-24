package id.project.logs.simplelogparser;

import id.project.logs.simplelogparser.cli.CliRunner;
import id.project.logs.simplelogparser.cli.TerminalDetector;

import java.util.Arrays;

/**
 * Launcher. With no arguments or with {@code --gui} it would open the GUI; otherwise it runs the CLI and
 * exits with its code. Does not extend {@code Application}, so the shaded jar can start JavaFX later.
 * This is the only class that calls {@code System.exit}.
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) {
        if (args.length == 0 || Arrays.asList(args).contains("--gui")) {
            System.err.println("slp: the GUI is not available in this build yet; run 'slp --help' for CLI usage.");
            System.exit(2);
        }
        int code = new CliRunner(TerminalDetector.stdoutIsTerminal()).run(args, System.out, System.err);
        System.out.flush();
        System.exit(code);
    }
}
