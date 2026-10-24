package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.scan.EntryReader;
import id.project.logs.simplelogparser.scan.ScanSummary;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Prints only the number of matching entries; {@code path:count} when several files are searched. */
public final class CountPrinter implements HitPrinter {

    private final OutputStream out;
    private final String prefix;

    public CountPrinter(OutputStream out, String prefix) {
        this.out = out;
        this.prefix = prefix;
    }

    @Override
    public void print(Hit hit, EntryReader reader) {
        // counted by the scan summary
    }

    @Override
    public void finish(ScanSummary summary) throws IOException {
        String line = (prefix == null ? "" : prefix) + summary.matched() + "\n";
        out.write(line.getBytes(StandardCharsets.UTF_8));
    }
}
