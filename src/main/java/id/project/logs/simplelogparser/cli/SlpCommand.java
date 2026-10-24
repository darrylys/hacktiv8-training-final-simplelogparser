package id.project.logs.simplelogparser.cli;

import picocli.CommandLine.ArgGroup;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Command-line options (TRD 7.1). {@link CliRunner} turns them into a query and one scan per file. */
@Command(name = "slp",
        sortOptions = false,
        usageHelpAutoWidth = true,
        version = "simplelogparser 1.0-SNAPSHOT",
        description = {
                "Search Spring Boot text logs and ECS JSON-per-line logs for whole entries, grep-style.",
                "Exit status: 0 if an entry matched, 1 if none matched, 2 on error.",
                "Run without arguments (or with --gui) to open the GUI."},
        footer = {
                "",
                "Times without an offset are interpreted in --zone (default Asia/Jakarta) for both formats;",
                "--from is inclusive, --to exclusive. Example: --from 2026-10-18T00:00 --to 2026-10-18T01:00"})
public final class SlpCommand {

    @Option(names = "--field", paramLabel = "KEY=VALUE",
            description = "Field filter, repeatable: KEY=VALUE (exact), KEY*=VALUE (contains), KEY~=REGEX. "
                    + "Same key OR'd, different keys AND'd.")
    List<String> fields = new ArrayList<>();

    @ArgGroup(exclusive = true)
    TextOptions text;

    static final class TextOptions {
        @Option(names = "--text", paramLabel = "TEXT", required = true, description = "Literal text query.")
        String literal;

        @Option(names = "--regex", paramLabel = "PATTERN", required = true,
                description = "Regex text query (java.util.regex syntax).")
        String regex;
    }

    @Option(names = "--in", paramLabel = "all|request|response", defaultValue = "all",
            description = "Text scope (default: ${DEFAULT-VALUE}). Request/response apply to plain logs only.")
    String scope;

    @Option(names = "--level", paramLabel = "LEVEL", split = ",",
            description = "Level filter, e.g. --level ERROR,WARN.")
    List<String> levels = new ArrayList<>();

    @Option(names = "--from", paramLabel = "TIME", description = "Only entries at or after TIME (ISO-8601).")
    String from;

    @Option(names = "--to", paramLabel = "TIME", description = "Only entries before TIME (ISO-8601).")
    String to;

    @Option(names = "--zone", paramLabel = "ZONE", defaultValue = "Asia/Jakarta",
            description = "Zone for plain timestamps and offset-less --from/--to (default: ${DEFAULT-VALUE}).")
    String zone;

    @Option(names = "--case-sensitive", description = "Case-sensitive text matching (default: insensitive).")
    boolean caseSensitive;

    @Option(names = {"-i", "--ignore-case"}, description = "Case-insensitive text matching (the default).")
    boolean ignoreCase;

    @Option(names = "--dotall", description = "DOTALL flag for --regex ('.' also matches newlines).")
    boolean dotAll;

    @Option(names = "--format", paramLabel = "auto|plain|json", defaultValue = "auto",
            description = "Input format (default: ${DEFAULT-VALUE}).")
    String format;

    @Option(names = "--encoding", paramLabel = "CHARSET", defaultValue = "UTF-8",
            description = "Input encoding; ASCII-compatible charsets only (default: ${DEFAULT-VALUE}).")
    String encoding;

    @Option(names = "--strict", description = "Abort on malformed JSON lines instead of skipping them.")
    boolean strict;

    @Option(names = "--count", description = "Print only the number of matching entries.")
    boolean count;

    @Option(names = {"-m", "--max-count"}, paramLabel = "N", description = "Stop after N matching entries.")
    Integer maxCount;

    @Option(names = "--json", description = "One JSON object per hit (file, offset, line, entry, truncated).")
    boolean json;

    @Option(names = "--no-color", description = "Disable ANSI highlighting.")
    boolean noColor;

    @Option(names = {"-h", "--no-filename"}, description = "Do not prefix output with the file name.")
    boolean noFilename;

    @Option(names = "--threads", paramLabel = "N", description = "Scan threads (default: CPU cores).")
    Integer threads;

    @Option(names = "--regex-timeout", paramLabel = "MS", defaultValue = "1000",
            description = "Per-entry regex timeout in milliseconds (default: ${DEFAULT-VALUE}).")
    long regexTimeoutMillis;

    @Option(names = "--chunk-size", hidden = true, description = "Scan chunk size in bytes (testing).")
    Long chunkSize;

    @Option(names = "--entry-limit", hidden = true, description = "Per-entry size limit in bytes (testing).")
    Integer entryLimit;

    @Option(names = "--gui", description = "Open the GUI (with optional files).")
    boolean gui;

    @Option(names = "--help", usageHelp = true, description = "Show this help and exit.")
    boolean help;

    @Option(names = "--version", versionHelp = true, description = "Show the version and exit.")
    boolean version;

    @Parameters(paramLabel = "FILE", arity = "1..*", description = "Log files to search.")
    List<Path> files = new ArrayList<>();
}
