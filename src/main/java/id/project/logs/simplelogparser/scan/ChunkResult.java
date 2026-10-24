package id.project.logs.simplelogparser.scan;

/**
 * Counters of one scanned chunk. Hits are streamed separately through the chunk's state.
 *
 * @param lines newlines owned by the chunk, from its first owned entry to where it stopped
 */
record ChunkResult(long lines, long hits, long entries, long malformed, long regexTimeouts, long truncated,
                   boolean cancelled) {
}
