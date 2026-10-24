package id.project.logs.simplelogparser.query;

import id.project.logs.simplelogparser.io.ByteSearch;
import id.project.logs.simplelogparser.model.RawEntry;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Byte-level check that rejects entries before decoding and parsing. It may only produce false positives,
 * never false negatives (rejected R10): every group lists alternatives of which at least one must occur in
 * the entry bytes, and a group is left out whenever its bytes might differ from the decoded text.
 * <p>
 * Left out entirely: regex parts, values with non-ASCII under case-insensitive matching, values with
 * {@code "}, {@code \} or control characters (escapable in JSON). For JSON entries, groups with characters
 * that JSON may escape ({@code /}) or that compact nested values reformat ({@code {}[],:}) are skipped, and
 * entries containing a {@code \\u} escape are never rejected. Case-insensitive needles containing
 * {@code i}, {@code k} or {@code s} are skipped for entries with non-ASCII bytes, because
 * {@code UNICODE_CASE} folds characters such as U+0131 and U+212A onto them.
 */
public final class BytePrefilter {

    private static final byte[] UNICODE_ESCAPE = {'\\', 'u'};

    record Group(List<byte[]> alternatives, boolean ignoreCase, boolean foldRisk, boolean jsonUnsafe) {
    }

    private final List<Group> groups;

    private BytePrefilter(List<Group> groups) {
        this.groups = List.copyOf(groups);
    }

    static final BytePrefilter NONE = new BytePrefilter(List.of());

    public boolean isEmpty() {
        return groups.isEmpty();
    }

    int groupCount() {
        return groups.size();
    }

    public boolean mayMatch(RawEntry raw, boolean json) {
        if (groups.isEmpty()) {
            return true;
        }
        byte[] b = raw.bytes();
        int len = raw.length();
        int nonAscii = -1;
        if (json && ByteSearch.contains(b, 0, len, UNICODE_ESCAPE, false)) {
            return true;
        }
        for (Group g : groups) {
            if (json && g.jsonUnsafe) {
                continue;
            }
            if (g.ignoreCase && g.foldRisk) {
                if (nonAscii < 0) {
                    nonAscii = ByteSearch.hasNonAscii(b, 0, len) ? 1 : 0;
                }
                if (nonAscii == 1) {
                    continue;
                }
            }
            boolean found = false;
            for (byte[] alt : g.alternatives) {
                if (ByteSearch.contains(b, 0, len, alt, g.ignoreCase)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    static final class Builder {
        private final List<Group> groups = new ArrayList<>();

        /** Adds a group; values that cannot be checked safely drop the whole group (it is OR'd). */
        Builder require(List<String> values, boolean ignoreCase, boolean structuralUnsafe) {
            if (values.isEmpty()) {
                return this;
            }
            List<byte[]> alts = new ArrayList<>();
            boolean foldRisk = false;
            boolean jsonUnsafe = false;
            for (String v : values) {
                if (!safe(v, ignoreCase)) {
                    return this;
                }
                alts.add(v.getBytes(StandardCharsets.UTF_8));
                foldRisk |= ignoreCase && hasFoldRisk(v);
                jsonUnsafe |= v.indexOf('/') >= 0 || (structuralUnsafe && hasStructural(v));
            }
            groups.add(new Group(alts, ignoreCase, foldRisk, jsonUnsafe));
            return this;
        }

        BytePrefilter build() {
            return groups.isEmpty() ? NONE : new BytePrefilter(groups);
        }

        private static boolean safe(String v, boolean ignoreCase) {
            for (int i = 0; i < v.length(); i++) {
                char c = v.charAt(i);
                if (c < 0x20 || c == 0x7F || c == '"' || c == '\\') {
                    return false;
                }
                if (c > 0x7F && ignoreCase) {
                    return false;
                }
            }
            return true;
        }

        private static boolean hasFoldRisk(String v) {
            for (int i = 0; i < v.length(); i++) {
                char c = Character.toLowerCase(v.charAt(i));
                if (c == 'i' || c == 'k' || c == 's') {
                    return true;
                }
            }
            return false;
        }

        private static boolean hasStructural(String v) {
            for (int i = 0; i < v.length(); i++) {
                if ("{}[],:".indexOf(v.charAt(i)) >= 0) {
                    return true;
                }
            }
            return false;
        }
    }
}
