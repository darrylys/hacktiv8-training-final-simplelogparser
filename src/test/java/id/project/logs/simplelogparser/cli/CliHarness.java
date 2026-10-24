package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.scan.ScanEngine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/** Runs {@link CliRunner} in-process with captured byte streams. */
final class CliHarness {

    record Run(int code, byte[] stdout, String stderr) {
        String out() {
            return new String(stdout, StandardCharsets.UTF_8);
        }

        List<String> outLines() {
            return out().lines().toList();
        }
    }

    private CliHarness() {
    }

    static Run run(String... args) {
        return run(false, new ScanEngine(), args);
    }

    static Run run(boolean terminal, ScanEngine engine, String... args) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        PrintStream errStream = new PrintStream(err, true, StandardCharsets.UTF_8);
        int code = new CliRunner(terminal, engine).run(args, out, errStream);
        errStream.flush();
        return new Run(code, out.toByteArray(), err.toString(StandardCharsets.UTF_8));
    }

    static String[] args(Object... parts) {
        return Arrays.stream(parts).map(String::valueOf).toArray(String[]::new);
    }
}
