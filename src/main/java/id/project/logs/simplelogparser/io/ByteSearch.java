package id.project.logs.simplelogparser.io;

/** Byte substring search used by prefilters. Case-insensitive search folds ASCII letters only. */
public final class ByteSearch {

    private ByteSearch() {
    }

    public static boolean contains(byte[] hay, int off, int len, byte[] needle, boolean ignoreAsciiCase) {
        return indexOf(hay, off, len, needle, ignoreAsciiCase) >= 0;
    }

    /** Index into {@code hay} (absolute, not relative to {@code off}) of the first match, or -1. */
    public static int indexOf(byte[] hay, int off, int len, byte[] needle, boolean ignoreAsciiCase) {
        int n = needle.length;
        if (n == 0) {
            return off;
        }
        int last = off + len - n;
        byte first = needle[0];
        if (!ignoreAsciiCase) {
            outer:
            for (int i = off; i <= last; i++) {
                if (hay[i] != first) {
                    continue;
                }
                for (int j = 1; j < n; j++) {
                    if (hay[i + j] != needle[j]) {
                        continue outer;
                    }
                }
                return i;
            }
            return -1;
        }
        byte firstLower = lower(first);
        outer:
        for (int i = off; i <= last; i++) {
            if (lower(hay[i]) != firstLower) {
                continue;
            }
            for (int j = 1; j < n; j++) {
                if (lower(hay[i + j]) != lower(needle[j])) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    public static boolean hasNonAscii(byte[] hay, int off, int len) {
        for (int i = off, end = off + len; i < end; i++) {
            if (hay[i] < 0) {
                return true;
            }
        }
        return false;
    }

    private static byte lower(byte b) {
        return (b >= 'A' && b <= 'Z') ? (byte) (b + 32) : b;
    }
}
