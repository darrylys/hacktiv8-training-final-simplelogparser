package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.format.FormatDetectionException;
import id.project.logs.simplelogparser.format.FormatDetector;
import id.project.logs.simplelogparser.format.ParseContext;
import id.project.logs.simplelogparser.io.EntryBuffer;
import id.project.logs.simplelogparser.io.LogFile;
import id.project.logs.simplelogparser.io.TextDecoder;
import id.project.logs.simplelogparser.model.Hit;
import id.project.logs.simplelogparser.model.Level;
import id.project.logs.simplelogparser.model.LogFormat;
import id.project.logs.simplelogparser.model.Scope;
import id.project.logs.simplelogparser.query.CancelToken;
import id.project.logs.simplelogparser.query.CompiledQuery;
import id.project.logs.simplelogparser.query.FieldFilter;
import id.project.logs.simplelogparser.query.MatchBudget;
import id.project.logs.simplelogparser.query.Query;
import id.project.logs.simplelogparser.query.QueryCompiler;
import id.project.logs.simplelogparser.query.QuerySyntaxException;
import id.project.logs.simplelogparser.query.TextQuery;
import id.project.logs.simplelogparser.query.TimeParser;
import id.project.logs.simplelogparser.scan.EntryReader;
import id.project.logs.simplelogparser.scan.ScanEngine;
import id.project.logs.simplelogparser.scan.ScanException;
import id.project.logs.simplelogparser.scan.ScanListener;
import id.project.logs.simplelogparser.scan.ScanRequest;
import id.project.logs.simplelogparser.scan.ScanSummary;
import picocli.CommandLine;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * In-process CLI entry point used by {@code Main} and by tests. Validates and compiles everything before
 * opening any file, then scans the files one after another (each scan is itself parallel) and maps the
 * outcome to an exit code. Hits go to {@code out}; messages and end-of-scan notices go to {@code err}, so
 * stdout stays clean for piping.
 */
public final class CliRunner {

    private final boolean stdoutIsTerminal;
    private final ScanEngine engine;

    public CliRunner(boolean stdoutIsTerminal) {
        this(stdoutIsTerminal, new ScanEngine());
    }

    public CliRunner(boolean stdoutIsTerminal, ScanEngine engine) {
        this.stdoutIsTerminal = stdoutIsTerminal;
        this.engine = engine;
    }

    /** Settings shared by all files of one run. */
    private record Config(CompiledQuery query, Scope scope, Optional<LogFormat> forcedFormat, Charset charset,
                          ParseContext ctx, int maxCount, int threads, long chunkSize, int entryLimit,
                          Duration regexTimeout, boolean color) {
    }

    public int run(String[] args, OutputStream stdout, PrintStream err) {
        SlpCommand cmd = new SlpCommand();
        CommandLine cl = new CommandLine(cmd);
        try {
            cl.parseArgs(args);
        } catch (CommandLine.ParameterException e) {
            err.println("slp: " + e.getMessage());
            err.println("Try 'slp --help' for usage.");
            return ExitCode.ERROR;
        }
        if (cl.isUsageHelpRequested() || cl.isVersionHelpRequested()) {
            PrintWriter w = new PrintWriter(stdout, true, Charset.defaultCharset());
            if (cl.isUsageHelpRequested()) {
                cl.usage(w, CommandLine.Help.Ansi.OFF);
            } else {
                cl.printVersionHelp(w, CommandLine.Help.Ansi.OFF);
            }
            w.flush();
            return ExitCode.MATCH;
        }
        if (cmd.gui) {
            err.println("slp: --gui is handled by the launcher; run without CLI options to open the GUI");
            return ExitCode.ERROR;
        }

        Config config;
        try {
            config = configure(cmd);
        } catch (QuerySyntaxException | IllegalArgumentException | DateTimeException e) {
            err.println("slp: " + e.getMessage());
            return ExitCode.ERROR;
        }

        OutputStream out = new BufferedOutputStream(stdout, 64 * 1024);
        boolean prefixNames = cmd.files.size() > 1 && !cmd.noFilename;
        List<Integer> codes = new ArrayList<>();
        try {
            for (Path file : cmd.files) {
                codes.add(searchFile(file, cmd, config, prefixNames, out, err));
                out.flush();
            }
            out.flush();
        } catch (IOException e) {
            err.println("slp: error writing output: " + e.getMessage());
            return ExitCode.ERROR;
        }
        return ExitCode.combine(codes);
    }

    private Config configure(SlpCommand cmd) throws QuerySyntaxException {
        if (cmd.caseSensitive && cmd.ignoreCase) {
            throw new IllegalArgumentException("--case-sensitive and --ignore-case cannot be combined");
        }
        if (cmd.dotAll && (cmd.text == null || cmd.text.regex == null)) {
            throw new IllegalArgumentException("--dotall requires --regex");
        }
        if (cmd.maxCount != null && cmd.maxCount < 0) {
            throw new IllegalArgumentException("--max-count must not be negative");
        }
        if (cmd.threads != null && cmd.threads < 1) {
            throw new IllegalArgumentException("--threads must be at least 1");
        }
        if (cmd.regexTimeoutMillis < 1) {
            throw new IllegalArgumentException("--regex-timeout must be at least 1 ms");
        }
        if (cmd.entryLimit != null && cmd.entryLimit < 1) {
            throw new IllegalArgumentException("--entry-limit must be at least 1");
        }
        if (cmd.chunkSize != null && cmd.chunkSize < 1) {
            throw new IllegalArgumentException("--chunk-size must be at least 1");
        }
        ZoneId zone = ZoneId.of(cmd.zone);
        Charset charset = TextDecoder.asciiCompatible(cmd.encoding);
        Scope scope = parseScope(cmd.scope);
        Optional<LogFormat> format = parseFormat(cmd.format);

        List<FieldFilter> fields = FieldArgParser.parse(cmd.fields);
        Query query = Query.matchAll().withFields(fields).withLevels(parseLevels(cmd.levels));
        if (cmd.text != null) {
            boolean regex = cmd.text.regex != null;
            String pattern = regex ? cmd.text.regex : cmd.text.literal;
            query = query.withText(new TextQuery(pattern, regex, cmd.caseSensitive, cmd.dotAll, scope));
        } else if (scope != Scope.ALL) {
            throw new IllegalArgumentException("--in requires --text or --regex");
        }
        Instant from = cmd.from == null ? null : TimeParser.parse(cmd.from, zone);
        Instant to = cmd.to == null ? null : TimeParser.parse(cmd.to, zone);
        query = query.withRange(from, to);

        CompiledQuery compiled = QueryCompiler.compile(query, charset);
        return new Config(compiled, scope, format, charset, new ParseContext(zone, cmd.strict),
                cmd.maxCount == null ? 0 : cmd.maxCount,
                cmd.threads == null ? ScanRequest.defaultThreads() : cmd.threads,
                cmd.chunkSize == null ? ScanRequest.DEFAULT_CHUNK_SIZE : cmd.chunkSize,
                cmd.entryLimit == null ? EntryBuffer.DEFAULT_ENTRY_LIMIT : cmd.entryLimit,
                Duration.ofMillis(cmd.regexTimeoutMillis),
                !cmd.json && !cmd.count && TerminalDetector.colorEnabled(cmd.noColor, stdoutIsTerminal));
    }

    private int searchFile(Path file, SlpCommand cmd, Config config, boolean prefixNames, OutputStream out,
                           PrintStream err) throws IOException {
        String name = file.toString();
        if (!Files.exists(file)) {
            err.println("slp: " + name + ": no such file");
            return ExitCode.ERROR;
        }
        if (Files.isDirectory(file)) {
            err.println("slp: " + name + ": is a directory");
            return ExitCode.ERROR;
        }
        if (!Files.isReadable(file)) {
            err.println("slp: " + name + ": permission denied");
            return ExitCode.ERROR;
        }
        if (config.maxCount() == 0 && cmd.maxCount != null) {
            return ExitCode.NO_MATCH;
        }
        try (LogFile logFile = LogFile.open(file)) {
            if (logFile.sizeAtOpen() == 0) {
                err.println("slp: " + name + ": file is empty");
                return ExitCode.ERROR;
            }
            LogFormat format = config.forcedFormat().isPresent()
                    ? config.forcedFormat().get()
                    : FormatDetector.detect(logFile, config.charset());
            if (format == LogFormat.JSON && config.scope() != Scope.ALL) {
                err.println("slp: " + name + ": warning: --in " + config.scope().name().toLowerCase(Locale.ROOT)
                        + " does not apply to JSON logs; searching all text");
            }
            CompiledQuery query = config.query().forFormat(format);
            ScanRequest request = new ScanRequest(file, format, query, config.ctx(), config.charset(),
                    config.maxCount(), config.threads(), config.chunkSize(), config.entryLimit(),
                    config.regexTimeout());
            String prefix = prefixNames ? name + ":" : null;
            HitPrinter printer;
            if (cmd.count) {
                printer = new CountPrinter(out, prefix);
            } else if (cmd.json) {
                printer = new JsonPrinter(out, name, config.charset());
            } else {
                printer = new RawPrinter(out, prefix, config.color(), query, config.charset(),
                        new MatchBudget(config.regexTimeout(), new CancelToken()));
            }
            EntryReader reader = new EntryReader(logFile, format, config.charset(), config.ctx(), config.entryLimit());
            ScanSummary summary = engine.run(request, new ScanListener() {
                @Override
                public void onHits(List<Hit> hits) {
                    try {
                        for (Hit hit : hits) {
                            printer.print(hit, reader);
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
            });
            // only a headerless block (or nothing) means the file holds no valid entries (edge #12); a capped
            // scan stops before its chunk counts are complete
            if (summary.entries() == 0 && !summary.capped()) {
                err.println("slp: " + name + ": no valid log entries found");
                return ExitCode.ERROR;
            }
            printer.finish(summary);
            reportSummary(name, summary, config, err);
            return summary.matched() > 0 ? ExitCode.MATCH : ExitCode.NO_MATCH;
        } catch (FormatDetectionException e) {
            err.println("slp: " + name + ": " + e.getMessage());
            return ExitCode.ERROR;
        } catch (ScanException e) {
            out.flush();
            err.println("slp: " + name + ": " + e.getMessage());
            return ExitCode.ERROR;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            err.println("slp: " + name + ": interrupted");
            return ExitCode.ERROR;
        } catch (IOException e) {
            err.println("slp: " + name + ": " + e.getMessage());
            return ExitCode.ERROR;
        }
    }

    private static void reportSummary(String name, ScanSummary s, Config config, PrintStream err) {
        if (s.capped()) {
            err.println("slp: " + name + ": results capped at " + config.maxCount() + " (--max-count)");
        }
        if (s.malformedLines() > 0) {
            err.println("slp: " + name + ": " + s.malformedLines() + " malformed lines skipped");
        }
        if (s.regexTimeouts() > 0) {
            err.println("slp: " + name + ": " + s.regexTimeouts() + " entries skipped (regex timeout)");
        }
        if (s.truncatedEntries() > 0) {
            err.println("slp: " + name + ": " + s.truncatedEntries()
                    + " entries exceeded the entry size limit and were matched on their first "
                    + config.entryLimit() + " bytes");
        }
        if (s.fileChangedDuringScan()) {
            err.println("slp: " + name + ": warning: file size changed during the scan; "
                    + "only the originally observed length was searched");
        }
    }

    private static Scope parseScope(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "all" -> Scope.ALL;
            case "request" -> Scope.REQUEST;
            case "response" -> Scope.RESPONSE;
            default -> throw new IllegalArgumentException("invalid --in '" + value + "' (expected all, request or response)");
        };
    }

    private static Optional<LogFormat> parseFormat(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "auto" -> Optional.empty();
            case "plain" -> Optional.of(LogFormat.PLAIN);
            case "json" -> Optional.of(LogFormat.JSON);
            default -> throw new IllegalArgumentException("invalid --format '" + value + "' (expected auto, plain or json)");
        };
    }

    private static Set<Level> parseLevels(List<String> values) {
        Set<Level> levels = EnumSet.noneOf(Level.class);
        for (String v : values) {
            if (v.isBlank()) {
                continue;
            }
            Level level = Level.parse(v);
            if (level == Level.UNKNOWN) {
                throw new IllegalArgumentException("invalid --level '" + v
                        + "' (expected TRACE, DEBUG, INFO, WARN, ERROR or FATAL)");
            }
            levels.add(level);
        }
        return levels;
    }
}
