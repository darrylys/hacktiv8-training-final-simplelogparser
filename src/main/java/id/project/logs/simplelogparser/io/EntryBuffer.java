package id.project.logs.simplelogparser.io;

import java.util.Arrays;

/**
 * Growable byte buffer for one entry, capped at the entry limit (edge #6). Bytes beyond the cap are
 * counted but not stored. Reused per worker.
 */
public final class EntryBuffer {

    public static final int DEFAULT_ENTRY_LIMIT = 16 * 1024 * 1024;
    private static final int INITIAL_CAPACITY = 64 * 1024;

    private final int limit;
    private byte[] data;
    private int size;
    private long totalLength;

    public EntryBuffer(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("entry limit must be positive: " + limit);
        }
        this.limit = limit;
        this.data = new byte[Math.min(INITIAL_CAPACITY, limit)];
    }

    public void clear() {
        size = 0;
        totalLength = 0;
    }

    public void append(byte[] src, int off, int len) {
        totalLength += len;
        int room = limit - size;
        int n = Math.min(room, len);
        if (n <= 0) {
            return;
        }
        if (size + n > data.length) {
            int newCap = (int) Math.min(limit, Math.max((long) data.length * 2, (long) size + n));
            data = Arrays.copyOf(data, newCap);
        }
        System.arraycopy(src, off, data, size, n);
        size += n;
    }

    public void append(ByteLine line) {
        append(line.bytes(), line.start(), line.length());
    }

    /** Backing array; valid up to {@link #size()} until the next mutation. */
    public byte[] array() {
        return data;
    }

    public int size() {
        return size;
    }

    /** Original length of the entry, including bytes dropped by the cap. */
    public long totalLength() {
        return totalLength;
    }

    public boolean truncated() {
        return totalLength > size;
    }

    public boolean isEmpty() {
        return totalLength == 0;
    }
}
