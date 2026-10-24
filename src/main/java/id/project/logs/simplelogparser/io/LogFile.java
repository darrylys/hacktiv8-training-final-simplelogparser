package id.project.logs.simplelogparser.io;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Read-only access to a log file. The channel is opened with {@link StandardOpenOption#READ} only;
 * nothing is ever created, locked or written. Positional reads are safe to use from several threads.
 */
public final class LogFile implements AutoCloseable {

    private final Path path;
    private final FileChannel channel;
    private final long sizeAtOpen;

    private LogFile(Path path, FileChannel channel, long sizeAtOpen) {
        this.path = path;
        this.channel = channel;
        this.sizeAtOpen = sizeAtOpen;
    }

    public static LogFile open(Path path) throws IOException {
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new LogFile(path, channel, channel.size());
        } catch (IOException | RuntimeException e) {
            channel.close();
            throw e;
        }
    }

    public Path path() {
        return path;
    }

    /** File size observed when the file was opened; scans never read past it (edge #13). */
    public long sizeAtOpen() {
        return sizeAtOpen;
    }

    public long currentSize() throws IOException {
        return channel.size();
    }

    /** Reads into {@code dst} starting at {@code pos}. Returns the number of bytes read, or -1 at end of file. */
    public int readAt(long pos, ByteBuffer dst) throws IOException {
        return channel.read(dst, pos);
    }

    /** Fills {@code dst} from {@code pos} until it is full or end of file is reached. Returns bytes read. */
    public int readFully(long pos, ByteBuffer dst) throws IOException {
        int total = 0;
        while (dst.hasRemaining()) {
            int n = channel.read(dst, pos + total);
            if (n < 0) {
                break;
            }
            total += n;
        }
        return total;
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
