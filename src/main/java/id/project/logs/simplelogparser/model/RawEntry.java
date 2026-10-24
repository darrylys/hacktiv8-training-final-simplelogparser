package id.project.logs.simplelogparser.model;

/**
 * Framed but unparsed entry. {@code bytes[0, length)} holds the stored bytes, which may be fewer than
 * {@code byteLength} when the entry was truncated at the entry limit. The array is usually a reused
 * worker buffer, so a {@code RawEntry} and anything parsed from it are short-lived.
 *
 * @param offset     absolute file offset of the entry's first byte
 * @param byteLength original length in the file
 * @param headerless true for lines before the first header (plain format, edge #3)
 */
public record RawEntry(long offset, long byteLength, byte[] bytes, int length, boolean truncated, boolean headerless) {
}
