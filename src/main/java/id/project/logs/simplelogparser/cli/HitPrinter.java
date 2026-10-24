package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.scan.EntryReader;
import id.project.logs.simplelogparser.scan.ScanSummary;

import java.io.IOException;

/** Writes the hits of one file to stdout. */
public interface HitPrinter {

    void print(Hit hit, EntryReader reader) throws IOException;

    /** Called once after the file's scan finished. */
    default void finish(ScanSummary summary) throws IOException {
    }
}
