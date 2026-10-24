package id.project.logs.simplelogparser.scan;

/**
 * Splits {@code [0, size)} into fixed-size chunks. Does no I/O; alignment to entry boundaries happens in
 * the worker (rejected R6: no pre-pass).
 */
public final class ChunkPlanner {

    public record Chunk(long index, long start, long end) {
        public long length() {
            return end - start;
        }
    }

    private final long size;
    private final long chunkSize;

    public ChunkPlanner(long size, long chunkSize) {
        this.size = size;
        this.chunkSize = chunkSize;
    }

    public long count() {
        return size == 0 ? 0 : (size - 1) / chunkSize + 1;
    }

    public Chunk chunk(long index) {
        long start = index * chunkSize;
        return new Chunk(index, start, Math.min(size, start + chunkSize));
    }
}
