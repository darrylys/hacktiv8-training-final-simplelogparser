package id.project.logs.simplelogparser.scan;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * Writes deterministic synthetic logs of a requested size and seed, with known entry counts per
 * {@code merchantId} (plain) or {@code shiftId} (JSON), so counts are known without hand counting. Test code
 * only; callers write into a JUnit {@code @TempDir}.
 */
public final class LogGenerator {

    static final String[] MERCHANTS = {"786", "1242", "1441", "2000", "7"};
    private static final String[] LEVELS = {"INFO", "DEBUG", "WARN", "ERROR", "INFO", "INFO"};
    private static final String[] LOGGERS = {"i.h.p.a.c.i.l.f.GistRequestResponseLoggingFilter",
            "i.h.p.a.j.s.s.SyncTrxServiceImpl", "o.s.c.s.ResourceBundleMessageSource", "c.e.OrderService"};
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final String ID_CHARS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-";

    /** What was written. {@code perKey} counts entries per merchantId (plain) or shiftId (JSON). */
    public record Stats(long bytes, long entries, long malformed, Map<String, Long> perKey) {
        public long count(String key) {
            return perKey.getOrDefault(key, 0L);
        }
    }

    private LogGenerator() {
    }

    public static Stats plain(Path out, long targetBytes, long seed, boolean crlf, boolean headerlessPrefix)
            throws IOException {
        Random rnd = new Random(seed);
        String nl = crlf ? "\r\n" : "\n";
        Map<String, Long> perKey = new TreeMap<>();
        long entries = 0;
        LocalDateTime ts = LocalDateTime.of(2026, 10, 18, 0, 0);
        try (CountingWriter w = new CountingWriter(out)) {
            if (headerlessPrefix) {
                w.write("\tat example.Truncated.start(Truncated.java:1)" + nl + "tail of a cut-off entry" + nl);
            }
            while (w.count < targetBytes) {
                ts = ts.plusNanos((rnd.nextInt(5000) + 1) * 1_000_000L);
                int kind = rnd.nextInt(10);
                String level = LEVELS[rnd.nextInt(LEVELS.length)];
                String logger = LOGGERS[rnd.nextInt(LOGGERS.length)];
                String thread = "http-nio-8934-exec-" + (rnd.nextInt(12) + 1);
                String mdc;
                if (kind == 9) {
                    mdc = "::";
                } else if (kind == 8) {
                    mdc = id(rnd) + ":" + id(rnd) + ":";
                } else {
                    String m = MERCHANTS[rnd.nextInt(MERCHANTS.length)];
                    perKey.merge(m, 1L, Long::sum);
                    mdc = id(rnd) + ":" + id(rnd) + ":merchantId: " + m + ", merchantUserId: " + rnd.nextInt(5000)
                            + ", shiftId: " + (147000 + rnd.nextInt(1000)) + ", isShiftOpen: " + rnd.nextBoolean()
                            + ", outletId: " + rnd.nextInt(2000);
                }
                StringBuilder e = new StringBuilder(256);
                e.append('[').append(mdc).append("] ").append(TS.format(ts)).append(" [").append(thread).append("] ")
                        .append(level).append(level.length() == 4 ? "  " : " ").append(logger).append(" - ");
                switch (kind % 6) {
                    case 0 -> {
                        e.append(nl).append("-- REQUEST --").append(nl)
                                .append("|> GET /pos/api/v1/payment?orderId=").append(rnd.nextInt(9_000_000)).append(nl)
                                .append("|>").append(nl).append(nl).append("-- RESPONSE --").append(nl)
                                .append("|< ").append(rnd.nextBoolean() ? "200 OK" : "503 Service Unavailable").append(nl)
                                .append("|<").append(nl).append("|< {\"data\":{\"status\":").append(rnd.nextInt(600))
                                .append(",\"body\":\"").append(filler(rnd, rnd.nextInt(2000))).append("\"}}").append(nl);
                    }
                    case 1 -> {
                        e.append("Unhandled exception").append(nl).append("example.errors.NotFound: NOT_FOUND").append(nl);
                        int depth = 3 + rnd.nextInt(10);
                        for (int i = 0; i < depth; i++) {
                            e.append("\tat example.pkg.Class").append(i).append(".method(Class").append(i)
                                    .append(".java:").append(rnd.nextInt(500)).append(')').append(nl);
                        }
                    }
                    case 2 -> e.append("café 日本語 ✓ 😀 multibyte payload ")
                            .append(rnd.nextInt(1000)).append(nl);
                    case 3 -> {
                        if (rnd.nextInt(20) == 0) {
                            e.append("|< ").append(filler(rnd, 5000 + rnd.nextInt(25000))).append(nl);
                        } else {
                            e.append("invoice ID '").append(rnd.nextInt(9_999_999)).append("' has tax").append(nl);
                        }
                    }
                    default -> e.append(">> Request BEGIN >> ").append(filler(rnd, rnd.nextInt(120))).append(nl);
                }
                w.write(e.toString());
                entries++;
            }
            return new Stats(w.count, entries, 0, perKey);
        }
    }

    public static Stats json(Path out, long targetBytes, long seed) throws IOException {
        Random rnd = new Random(seed);
        Map<String, Long> perKey = new TreeMap<>();
        long entries = 0;
        long malformed = 0;
        LocalDateTime ts = LocalDateTime.of(2026, 10, 22, 0, 0);
        try (CountingWriter w = new CountingWriter(out)) {
            while (w.count < targetBytes) {
                ts = ts.plusNanos((rnd.nextInt(5000) + 1) * 1_000_000L);
                int kind = rnd.nextInt(12);
                if (kind == 11) {
                    w.write(rnd.nextBoolean() ? "\n" : "   \n");
                    continue;
                }
                if (kind == 10) {
                    w.write("{\"@timestamp\":\"broken" + nl(rnd));
                    malformed++;
                    continue;
                }
                StringBuilder e = new StringBuilder(256);
                e.append("{\"@timestamp\":\"").append(ts).append("Z\",\"log.level\":\"")
                        .append(LEVELS[rnd.nextInt(LEVELS.length)]).append("\",\"process.thread.name\":\"exec-")
                        .append(rnd.nextInt(9)).append("\",\"log.logger\":\"").append(LOGGERS[rnd.nextInt(LOGGERS.length)])
                        .append('"');
                if (kind < 6) {
                    String shift = Integer.toString(4890 + rnd.nextInt(4));
                    perKey.merge(shift, 1L, Long::sum);
                    e.append(",\"traceId\":\"").append(id(rnd)).append("\",\"shiftId\":");
                    if (rnd.nextBoolean()) {
                        e.append('"').append(shift).append('"');
                    } else {
                        e.append(shift);
                    }
                }
                if (kind == 3) {
                    e.append(",\"logData\":{\"requestBody\":{\"items\":[1, 2, 3]},\"responseStatusCode\":")
                            .append(200 + rnd.nextInt(400)).append('}');
                }
                e.append(",\"message\":\"");
                switch (kind % 4) {
                    case 0 -> e.append("line one\\nline two \\\"quoted\\\" caf\\u00e9 ").append(rnd.nextInt(1000));
                    case 1 -> e.append(filler(rnd, rnd.nextInt(3000)));
                    default -> e.append("plain message ").append(rnd.nextInt(100000));
                }
                e.append("\"}").append(nl(rnd));
                w.write(e.toString());
                entries++;
            }
            return new Stats(w.count, entries, malformed, perKey);
        }
    }

    private static String nl(Random rnd) {
        return rnd.nextInt(8) == 0 ? "\r\n" : "\n";
    }

    private static String id(Random rnd) {
        char[] c = new char[22];
        for (int i = 0; i < c.length; i++) {
            c[i] = ID_CHARS.charAt(rnd.nextInt(ID_CHARS.length()));
        }
        return new String(c);
    }

    private static String filler(Random rnd, int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int r = rnd.nextInt(40);
            sb.append(r < 26 ? (char) ('a' + r) : r < 36 ? (char) ('0' + r - 26) : ' ');
        }
        return sb.toString();
    }

    /** UTF-8 writer that counts bytes written. */
    private static final class CountingWriter implements AutoCloseable {
        private final Writer writer;
        long count;

        CountingWriter(Path path) throws IOException {
            this.writer = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(path), StandardCharsets.UTF_8),
                    1 << 20);
        }

        void write(String s) throws IOException {
            writer.write(s);
            count += utf8Length(s);
        }

        private static long utf8Length(String s) {
            long n = 0;
            for (int i = 0; i < s.length(); i++) {
                char c = s.charAt(i);
                if (c < 0x80) {
                    n++;
                } else if (c < 0x800) {
                    n += 2;
                } else if (Character.isHighSurrogate(c)) {
                    n += 4;
                    i++;
                } else {
                    n += 3;
                }
            }
            return n;
        }

        @Override
        public void close() throws IOException {
            writer.close();
        }
    }
}
