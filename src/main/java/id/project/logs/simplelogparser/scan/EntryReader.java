package id.project.logs.simplelogparser.scan;

import id.project.logs.simplelogparser.format.FormatHandler;
import id.project.logs.simplelogparser.format.HeaderlessEntry;
import id.project.logs.simplelogparser.format.MalformedEntryException;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.LogEntry;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.RawEntry;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;

/** Re-reads a hit's bytes from the file for display, copy and CLI output (edge #21). */
public final class EntryReader {

    private static final int COPY_BUFFER = 64 * 1024;

    private final LogFile file;
    private final FormatHandler handler;
    private final TextDecoder decoder;
    private final ParseContext ctx;
    private final int entryLimit;

    public EntryReader(LogFile file, LogFormat format, Charset charset, ParseContext ctx, int entryLimit) {
        this.file = file;
        this.handler = FormatHandler.of(format, charset);
        this.decoder = new TextDecoder(charset);
        this.ctx = ctx;
        this.entryLimit = entryLimit;
    }

    /** The hit's stored bytes: the original bytes, cut at the entry limit. */
    public byte[] readBytes(Hit hit) throws IOException {
        int len = (int) Math.min(hit.byteLength(), entryLimit);
        ByteBuffer buf = ByteBuffer.allocate(len);
        int n = file.readFully(hit.offset(), buf);
        if (n < len) {
            byte[] shorter = new byte[n];
            System.arraycopy(buf.array(), 0, shorter, 0, n);
            return shorter;
        }
        return buf.array();
    }

    /** Re-reads and parses the hit's entry. */
    public LogEntry read(Hit hit) throws IOException {
        byte[] bytes = readBytes(hit);
        RawEntry raw = new RawEntry(hit.offset(), hit.byteLength(), bytes, bytes.length,
                hit.byteLength() > bytes.length, false);
        try {
            return handler.parse(raw, ctx);
        } catch (MalformedEntryException e) {
            return new HeaderlessEntry(raw, decoder);
        }
    }

    /** Copies the hit's complete original bytes to {@code out}, unchanged (AC17), without holding them all. */
    public void copyTo(Hit hit, OutputStream out) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate((int) Math.min(COPY_BUFFER, Math.max(1, hit.byteLength())));
        long pos = hit.offset();
        long end = hit.offset() + hit.byteLength();
        while (pos < end) {
            buf.clear();
            buf.limit((int) Math.min(buf.capacity(), end - pos));
            int n = file.readAt(pos, buf);
            if (n <= 0) {
                break;
            }
            out.write(buf.array(), 0, n);
            pos += n;
        }
    }

    /** Last byte of the hit's entry, or -1 if empty. */
    public int lastByte(Hit hit) throws IOException {
        if (hit.byteLength() == 0) {
            return -1;
        }
        ByteBuffer one = ByteBuffer.allocate(1);
        return file.readAt(hit.offset() + hit.byteLength() - 1, one) == 1 ? one.get(0) & 0xFF : -1;
    }
}
