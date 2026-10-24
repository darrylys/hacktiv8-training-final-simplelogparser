package id.project.logs.simplelogparser.io;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;

/**
 * Decodes entry bytes. Malformed input is replaced with U+FFFD (edge #11, AC24): {@code new String(bytes,
 * charset)} always uses the charset's replacement. A UTF-8 BOM is stripped when the bytes start at offset 0.
 */
public final class TextDecoder {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private final Charset charset;

    public TextDecoder(Charset charset) {
        this.charset = charset;
    }

    public Charset charset() {
        return charset;
    }

    /** Decodes {@code bytes[off, off+len)}; {@code fileOffset} is the absolute offset of {@code bytes[off]}. */
    public String decode(byte[] bytes, int off, int len, long fileOffset) {
        int bom = fileOffset == 0 ? bomLength(bytes, off, len) : 0;
        return new String(bytes, off + bom, len - bom, charset);
    }

    /** Number of BOM bytes at {@code bytes[off]} (0 or 3). */
    public static int bomLength(byte[] bytes, int off, int len) {
        if (len >= 3 && bytes[off] == UTF8_BOM[0] && bytes[off + 1] == UTF8_BOM[1] && bytes[off + 2] == UTF8_BOM[2]) {
            return 3;
        }
        return 0;
    }

    /** Match text: {@code \r\n} and lone {@code \r} normalized to {@code \n} (edge #10). */
    public static String normalizeLineEndings(String raw) {
        int cr = raw.indexOf('\r');
        if (cr < 0) {
            return raw;
        }
        StringBuilder sb = new StringBuilder(raw.length());
        sb.append(raw, 0, cr);
        int n = raw.length();
        for (int i = cr; i < n; i++) {
            char c = raw.charAt(i);
            if (c == '\r') {
                sb.append('\n');
                if (i + 1 < n && raw.charAt(i + 1) == '\n') {
                    i++;
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * Resolves a charset name, accepting only ASCII-compatible charsets because framing looks for
     * {@code \n}, {@code [} and {@code {} as single bytes (A4).
     */
    public static Charset asciiCompatible(String name) {
        Charset cs;
        try {
            cs = Charset.forName(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("unknown encoding: " + name);
        }
        if (!isAsciiCompatible(cs)) {
            throw new UnsupportedCharsetException(cs.name() + " (only ASCII-compatible encodings are supported)");
        }
        return cs;
    }

    static boolean isAsciiCompatible(Charset cs) {
        if (!cs.canEncode()) {
            return false;
        }
        String probe = "\n\r\t []{}:,\"\\-.0123456789azAZ";
        byte[] encoded = probe.getBytes(cs);
        return java.util.Arrays.equals(encoded, probe.getBytes(StandardCharsets.US_ASCII));
    }
}
