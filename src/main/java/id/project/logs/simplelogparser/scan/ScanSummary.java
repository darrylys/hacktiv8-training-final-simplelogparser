package id.project.logs.simplelogparser.scan;

import java.time.Duration;

/**
 * End-of-scan notice shown by both front ends.
 *
 * @param entries          entries framed in the scanned part of the file (headerless prefix not counted)
 * @param malformedLines   malformed JSON lines among the lines that had to be parsed; lines the byte
 *                         prefilter rejected could not have matched and are not parsed
 */
public record ScanSummary(long matched, boolean capped, boolean cancelled, long malformedLines, long regexTimeouts,
                          long truncatedEntries, boolean fileChangedDuringScan, long entries, Duration elapsed) {
}
