package id.project.logs.simplelogparser.io;

/**
 * One line (or one piece of an over-long line) as a view into a reader's buffer. Valid only until the
 * reader's next call.
 *
 * @param offset           absolute file offset of {@code bytes[start]}
 * @param length           number of bytes including the terminator
 * @param terminatorLength 0, 1 ({@code \n}) or 2 ({@code \r\n})
 * @param continuation     true if this piece continues a line that did not fit in the buffer; such a
 *                         piece never starts an entry
 */
public record ByteLine(long offset, byte[] bytes, int start, int length, int terminatorLength, boolean continuation) {

    public int contentLength() {
        return length - terminatorLength;
    }

    public boolean endsWithNewline() {
        return length > 0 && bytes[start + length - 1] == '\n';
    }

    public byte byteAt(int i) {
        return bytes[start + i];
    }

    /** True if the content (without terminator) is empty or only spaces and tabs. */
    public boolean isBlank() {
        int end = start + contentLength();
        for (int i = start; i < end; i++) {
            byte b = bytes[i];
            if (b != ' ' && b != '\t' && b != '\r') {
                return false;
            }
        }
        return true;
    }
}
