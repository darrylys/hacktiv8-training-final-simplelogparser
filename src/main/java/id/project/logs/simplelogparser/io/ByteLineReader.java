package id.project.logs.simplelogparser.io;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Reads lines as byte ranges from a start position up to a limit, using one reusable buffer.
 * A line longer than the buffer is returned in pieces: every piece after the first has
 * {@link ByteLine#continuation()} set, so the buffer never grows (edge #7).
 * <p>
 * Reads start small and double up to the buffer size, so readers used for tiny chunks stay cheap.
 */
public final class ByteLineReader {

    public static final int DEFAULT_BUFFER_SIZE = 1 << 20;
    private static final int INITIAL_READ = 4096;

    private final LogFile file;
    private final byte[] buf;
    private final ByteBuffer byteBuffer;

    private long bufStart;
    private int pos;
    private int end;
    private int scanned;
    private long limit;
    private boolean eof;
    private int readSize;
    private boolean midLine;

    public ByteLineReader(LogFile file) {
        this(file, DEFAULT_BUFFER_SIZE);
    }

    public ByteLineReader(LogFile file, int bufferSize) {
        if (bufferSize < 16) {
            throw new IllegalArgumentException("buffer too small: " + bufferSize);
        }
        this.file = file;
        this.buf = new byte[bufferSize];
        this.byteBuffer = ByteBuffer.wrap(buf);
    }

    /** Positions the reader at {@code start}; it never reads at or beyond {@code limit}. */
    public void reset(long start, long limit) {
        this.bufStart = start;
        this.pos = 0;
        this.end = 0;
        this.scanned = 0;
        this.limit = limit;
        this.eof = start >= limit;
        this.readSize = Math.min(INITIAL_READ, buf.length);
        this.midLine = false;
    }

    /** Absolute offset of the next byte that {@link #next()} would return. */
    public long position() {
        return bufStart + pos;
    }

    /** Returns the next line or line piece, or {@code null} at the limit. */
    public ByteLine next() throws IOException {
        while (true) {
            for (int i = scanned; i < end; i++) {
                if (buf[i] == '\n') {
                    int term = (i > pos && buf[i - 1] == '\r') ? 2 : 1;
                    return emit(i + 1 - pos, term, true);
                }
            }
            scanned = end;
            if (eof) {
                return pos < end ? emit(end - pos, 0, false) : null;
            }
            if (pos == 0 && end == buf.length) {
                return emit(end, 0, false);
            }
            fill();
        }
    }

    private ByteLine emit(int length, int terminatorLength, boolean lineEnds) {
        ByteLine line = new ByteLine(bufStart + pos, buf, pos, length, terminatorLength, midLine);
        pos += length;
        scanned = pos;
        midLine = !lineEnds;
        return line;
    }

    private void fill() throws IOException {
        if (pos > 0) {
            int remaining = end - pos;
            System.arraycopy(buf, pos, buf, 0, remaining);
            bufStart += pos;
            scanned -= pos;
            end = remaining;
            pos = 0;
        }
        long fileRemaining = limit - (bufStart + end);
        int want = (int) Math.min(Math.min(buf.length - end, readSize), fileRemaining);
        if (want <= 0) {
            eof = true;
            return;
        }
        byteBuffer.clear().position(end).limit(end + want);
        int n = file.readAt(bufStart + end, byteBuffer);
        if (n <= 0) {
            eof = true;
            return;
        }
        end += n;
        readSize = Math.min(readSize * 2, buf.length);
    }
}
