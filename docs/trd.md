# TRD: simplelogparser

Technical design for the product described in [prd.md](prd.md). Section numbers like "PRD 5.4" and "edge #6" refer to that document. Rejected alternatives and the reasons for rejecting them are in [trd-rejected.md](trd-rejected.md).

Assumptions are marked inline as **[A<n>]** and listed in [section 11](#11-assumptions). Each one is a decision this TRD takes where the PRD is silent or ambiguous; the PRD owner should confirm or correct them before implementation of the affected component starts.

## 1. Overview

One Maven module, one shaded jar. The code is split into a UI-free **core** (I/O, format handling, query evaluation, parallel scan) and two thin front ends (**cli**, **gui**) that only translate user input into a `Query`, start a scan, and render hits.

```
            +-----------------+          +--------------------+
  args ---> |  cli (picocli)  |          |  gui (JavaFX)      | <--- mouse/keyboard
            +--------+--------+          +---------+----------+
                     |  Query + ScanRequest        |
                     v                             v
            +--------------------------------------------------+
            |  scan: ScanEngine  (chunk planner, worker pool,  |
            |        ordered merger, cancel, progress, cap)    |
            +---------+------------------------+---------------+
                      |                        |
          +-----------v----------+   +---------v----------+
          | format: framing +    |   | query: compiled    |
          | parsing (plain/json) |   | predicates, regex  |
          +-----------+----------+   | guard, highlighter |
                      |              +--------------------+
          +-----------v----------+
          | io: read-only file   |
          | access, byte lines,  |
          | decoding             |
          +----------------------+
```

Dependency rule: `cli` and `gui` depend on core packages; core packages never depend on `cli`, `gui`, picocli or JavaFX. Enforced by an ArchUnit test (section 9.6).

### 1.1 Key design choices

| Choice | Summary |
|---|---|
| Byte-level framing | Entry boundaries are found on raw bytes; text is decoded once per entry (edge #2). |
| Positional `FileChannel` reads | Each worker reads its own byte range with `FileChannel.read(ByteBuffer, position)`, file opened with `READ` only. No memory mapping (see rejected R1). |
| Chunk ownership rule | A chunk owns every entry whose **first byte** lies in `[chunkStart, chunkEnd)`. It reads past `chunkEnd` to finish its last entry. This makes parallel results identical to a sequential scan (AC12). |
| Ordered streaming merge | Hits from chunk *i* are emitted only after chunks `< i` are complete; chunk 0 streams live, so early matches appear fast (PRD 6, AC13). |
| Lazy, two-stage matching | A cheap byte-level prefilter rejects most entries before decoding and parsing. Prefilters may only produce false positives, never false negatives. |
| Lightweight hits | A hit is `(offset, length, line, summary)`. Full entry text is re-read from the file when displayed, copied or printed (edge #21). |
| Guarded regex | Regex matching runs against a `CharSequence` wrapper that enforces a per-entry deadline and the scan's cancel flag (PRD 5.4, edge #14). |

## 2. Technology and build

### 2.1 Libraries

Versions are the minimum intended; take the latest patch release at implementation time. **[A1]**

| Purpose | Library | Scope |
|---|---|---|
| GUI | OpenJFX `javafx-controls` 21.0.x, classifiers `win` (required), `mac`, `mac-aarch64`, `linux` **[A2]** | compile |
| Highlighted, virtualized detail pane | RichTextFX 0.11.x (`StyleClassedTextArea`) | compile |
| CLI parsing | picocli 4.7.x | compile |
| JSONC parsing and `--json` output | Jackson `jackson-core` 2.18.x (streaming `JsonParser` / `JsonGenerator` only, no databind) | compile |
| Unit tests | JUnit Jupiter 5.11.x, AssertJ 3.26.x | test |
| Architecture rules | ArchUnit 1.3.x (`archunit-junit5`) | test |
| GUI smoke tests | TestFX 4.0.x (+ Monocle for headless, if it works on the chosen JavaFX version) | test, `gui` profile only |

No logging framework. The app reports problems through the scan summary, stderr (CLI) or dialogs (GUI). Internal diagnostics use `System.Logger`, which is silent by default.

### 2.2 `pom.xml` changes

- Properties: `maven.compiler.release=21` (replaces `source`/`target`, so JDK 25 compiles against the Java 21 API), `javafx.version`, library versions.
- `maven-compiler-plugin` 3.13.x with `<release>21</release>`.
- `maven-surefire-plugin` 3.5.x. Default run excludes JUnit tags `perf` and `gui`.
- Profiles:
  - `perf`: runs only `@Tag("perf")` tests in a forked JVM with `-Xmx512m` (AC11).
  - `gui`: runs only `@Tag("gui")` TestFX tests.
- `maven-shade-plugin` 3.6.x bound to `package`: one jar `simplelogparser-<version>.jar`, `ManifestResourceTransformer` with `Main-Class: id.project.logs.simplelogparser.Main`, `ServicesResourceTransformer`, signature files (`META-INF/*.SF|DSA|RSA`) excluded, `module-info.class` from dependencies excluded.
- `javafx-maven-plugin` 0.0.8 for `javafx:run` during development only (not used by the packaged jar).

Build and run:

```powershell
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd package
java -jar target\simplelogparser-1.0-SNAPSHOT.jar --field merchantId=786 sample-logs\log-plain.log
java -jar target\simplelogparser-1.0-SNAPSHOT.jar            # opens the GUI
```

`slp` in the PRD is the command name users see. The project ships `src/dist/slp.cmd` and `src/dist/slp` wrapper scripts that call `java -jar` on the jar next to them. **[A3]**

### 2.3 Toolchain spike (first task)

Before any feature work, verify on the Windows workstation (PRD risk 3):

1. JavaFX 21 classes from a shaded jar launch on JDK 25 through a non-`Application` main class. The "unsupported JavaFX configuration: classes were loaded from 'unnamed module'" warning is expected and acceptable.
2. RichTextFX renders a 16 MB text in the detail pane without freezing.
3. A raw positional-read loop over a 5 GB generated file reaches the throughput budget (section 6.6).

If 1 fails, fall back to JavaFX 17 LTS or to launching with `--module-path`; record the outcome in this document.

**Outcome (2026-10-24, Windows 11, JDK 25.0.2, JavaFX 21.0.5, RichTextFX 0.11.3):**

1. Passed. `java -jar simplelogparser-1.0-SNAPSHOT.jar` (with or without `--gui <file>`) opens the window through `Main`. The only startup message is the expected "unnamed module" warning; the manifest carries `Enable-Native-Access: ALL-UNNAMED` so JDK 22+ does not warn about JavaFX loading its natives.
2. Passed. A 16 MB entry is loaded into the detail pane in about 1.4 s (headless Monocle, software rendering), and the FX thread answers within about 50 ms afterwards (`SearchFlowGuiTest`).
3. Passed with margin: a field search over a generated 5 GB file ran at about 1 GB/s on 16 cores (`ScalePerfTest`, file likely in the OS cache).

Change to A2: the `mac` and `mac-aarch64` JavaFX jars put their native libraries at the same paths (`libglass.dylib`, ...), so one shaded jar can hold only one of them. The jar bundles `win`, `linux` and `mac` (x64); Apple Silicon needs an x64 JDK (Rosetta) or a separate build with the `mac-aarch64` classifier. The jar is about 12.6 MB.

TestFX smoke tests run headless with Monocle 21.0.2 (`.\mvnw.cmd test -Pgui`); `-Dheadless=false -Dglass.platform=win` runs them on the desktop.

## 3. Package structure

Root package: `id.project.logs.simplelogparser` (abbreviated `slp` below).

```
id.project.logs.simplelogparser
├── Main                      launcher: CLI or GUI dispatch; does not extend Application
├── io/                       read-only file access, byte lines, decoding
├── model/                    immutable value types shared by all layers
├── format/                   format detection and the format SPI
│   ├── plain/                Spring Boot text format
│   └── json/                 ECS JSON-per-line format
├── query/                    query model, compilation, predicates, regex guard, highlighting
├── scan/                     parallel scan engine, merge, progress, cancel, summary
├── settings/                 user-profile settings (recent files); the only code that writes files
├── cli/                      picocli command, output printers, exit codes
└── gui/                      JavaFX application, views, view-models, clipboard
```

`src/test/java` mirrors this tree. Test fixtures live in `src/test/resources/fixtures/` (section 9.2).

## 4. Class structure

Records are used for immutable values. Types not listed here (small helpers, exceptions) are implementation details.

### 4.1 `slp` (root)

| Class | Responsibility |
|---|---|
| `Main` | `main(String[])`. With no arguments or with `--gui` (plus optional file paths), calls `Application.launch(SlpGuiApp.class, args)`. Otherwise runs `CliRunner` and calls `System.exit` with its code. |

### 4.2 `io`

| Class | Responsibility |
|---|---|
| `LogFile` | Opens a `FileChannel` with `StandardOpenOption.READ` only; captures `sizeAtOpen` (edge #13). `AutoCloseable`. Exposes `readAt(long pos, ByteBuffer dst)` and `currentSize()`. Never creates, locks or writes anything. Opening is O(1) (PRD 5.1). |
| `ByteLineReader` | Reads lines as byte ranges from a start position using a reusable buffer (default 1 MB). Returns `ByteLine(offset, bytes, start, length, terminatorLength)`. A line longer than the entry limit is reported in pieces with an `overflow` flag instead of growing the buffer (edge #7). Tracks the absolute offset of every line. |
| `EntryBuffer` | Growable byte buffer for one entry, capped at the entry limit (default 16 MB, edge #6). Bytes beyond the cap are counted but not stored; sets `truncated`. Reused per worker. |
| `TextDecoder` | Decodes an entry's bytes with a `CharsetDecoder` set to `REPLACE` (U+FFFD, edge #11, AC24). Strips a UTF-8 BOM when the entry starts at offset 0. Produces both the **raw text** (original line endings) and the **match text** (`\r\n` and lone `\r` normalized to `\n`, edge #10). |
| `ByteSearch` | ASCII-case-insensitive and case-sensitive byte substring search (used by prefilters). |

Encodings: only ASCII-compatible charsets (UTF-8 default, ISO-8859-1, windows-1252, ...) are accepted, because framing looks for `\n`, `[` and `{` as single bytes. `--encoding UTF-16` is rejected with exit code 2. **[A4]**

### 4.3 `model`

| Type | Responsibility |
|---|---|
| `LogFormat` (enum) | `PLAIN`, `JSON`. |
| `Level` (enum) | `TRACE, DEBUG, INFO, WARN, ERROR, FATAL, UNKNOWN`. Parsed case-insensitively; anything else is `UNKNOWN`. **[A5]** |
| `RawEntry` (record) | `offset`, `byteLength` (original length, even when truncated), `lineNumber` (1-based, first line of the entry), the entry bytes view, `truncated`, `headerless`. Produced by framing, consumed by parsing. Short-lived, worker-local. |
| `LogEntry` (interface) | Parsed entry. `offset()`, `lineNumber()`, `rawText()`, `matchText()`, `timestamp(): Optional<Instant>`, `level()`, `field(String): Optional<String>`, `fieldNames()`, `section(Scope): CharSequence` (empty when absent), `truncated()`. Implementations parse lazily: `matchText()` and sections are computed only when a predicate asks. |
| `Hit` (record) | `offset`, `byteLength`, `lineNumber`, `truncated`, `HitSummary summary`. The only per-result object kept after a scan. |
| `HitSummary` (record) | `timestampText`, `level`, `logger`, `keyIds` (requestId / correlationId / traceId when present), `firstLine` (message first line, cut to 200 chars). Used by the GUI list and nowhere else. |

### 4.4 `format`

| Type | Responsibility |
|---|---|
| `FormatHandler` (interface) | The format SPI. `LogFormat format()`; `boolean isEntryStart(ByteLine line)` (byte-level, strict); `boolean isSkippable(ByteLine line)` (JSON blank lines); `LogEntry parse(RawEntry raw, ParseContext ctx) throws MalformedEntryException`; `HitSummary summarize(LogEntry e)`. Adding a format means adding one implementation (PRD 9). |
| `FormatDetector` | Reads at most the first 64 KB or 50 non-empty lines. JSON if the first non-empty line (after BOM) starts with `{` and parses as a JSON object. Plain if any inspected line passes the strict plain header check (leading non-header lines allowed, edge #3). Otherwise throws `FormatDetectionException` (AC1). `--format plain|json` bypasses detection. **[A6]** |
| `ParseContext` (record) | `ZoneId plainZone` (default `Asia/Jakarta`, `--zone`), `boolean strict` (`--strict`). |
| `MalformedEntryException` | Thrown by JSON parsing; counted by the scan (edge #8) or fatal under `--strict`. |

#### 4.4.1 `format.plain`

| Class | Responsibility |
|---|---|
| `PlainFormatHandler` | Implements the SPI. `isEntryStart`: byte prefilter (line starts with `[`, contains `] dddd-dd-dd dd:dd:dd.ddd [`), then the full header regex on the decoded line (edge #4). |
| `PlainHeaderParser` | Header regex (compiled once): `^\[(?<mdc>.*?)\] (?<ts>\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}\.\d{3}) \[(?<thread>[^\]]*)\] (?<level>[A-Z]+)\s+(?<logger>\S+) - (?<msg>.*)$`. The lazy `mdc` group is anchored by the timestamp that must follow it, so a `]` inside the MDC does not break parsing. Timestamp parsed as `LocalDateTime` in `plainZone`. |
| `MdcParser` | Implements edge #5. The MDC is split on the first `:` into token 1 and the rest, then on the next `:` into token 2 and the remainder. A token is a positional id only if it is non-empty and contains no space and no `": "`; token 1 → `correlationId`, token 2 → `requestId`. The remainder is split on `", "`; each part is split on its first `": "` into key and value (both trimmed). Parts without `": "` are ignored. Empty positional tokens (`[abc:def:]`, `[::]`) yield no field. **[A7]** Duplicate keys: the first wins. |
| `SectionSplitter` | Finds lines equal (after trimming) to `-- REQUEST --` / `-- RESPONSE --` in the match text. Request section = lines after the first `-- REQUEST --` up to the first `-- RESPONSE --` after it (or entry end). Response section = lines after the first `-- RESPONSE --` to entry end (edge #16). Sections keep their `|>` / `|<` prefixes, so highlight offsets map directly onto the displayed text. **[A8]** Returns offset ranges into the match text, not copies. |
| `PlainEntry` | `LogEntry` implementation. Built-in fields: `level`, `thread`, `logger` (abbreviated, as written), `timestamp` (the original text). |
| `HeaderlessEntry` | Lines before the first header in chunk 0 (edge #3, AC23): no fields, level `UNKNOWN`, no timestamp; matchable by text only. |

#### 4.4.2 `format.json`

| Class | Responsibility |
|---|---|
| `JsonFormatHandler` | Implements the SPI. Every non-blank line starts an entry; blank lines are skipped silently. A line that fails to parse throws `MalformedEntryException` (edge #8, AC22). |
| `JsonEntryParser` | Streaming parse with one reused `JsonFactory`. Top-level scalar values become fields as their string text (`"147581"` and `147581` both become `147581`; booleans `true`/`false`; `null` → no field). Top-level object/array values become a field whose value is the compact raw JSON of that value; nested keys are not individually addressable. **[A9]** `@timestamp` → `Instant`, `log.level` → level, `process.thread.name` → `thread`, `log.logger` → `logger`, `message` decoded (escapes resolved, edge #9). Built-in names `level`, `thread`, `logger`, `timestamp` are aliases of those keys; the original key names (`log.level`, ...) also work. |
| `JsonEntry` | `LogEntry` implementation. `matchText()` for scope All is the decoded `message`, a `\n` separator, then the raw line (edge #9). `section(REQUEST/RESPONSE)` is never used for JSON (section 5.3). |

### 4.5 `query`

| Type | Responsibility |
|---|---|
| `Query` (record) | `List<FieldFilter> fields`, `Optional<TextQuery> text`, `Set<Level> levels` (empty = any), `Optional<Instant> from`, `Optional<Instant> to`. An empty query matches every entry. |
| `FieldFilter` (record) | `String field`, `MatchMode mode`, `List<String> values`. Values OR'd; filters on different fields AND'd (PRD 2). Filters for the same field with different modes are separate filters and are AND'd. **[A10]** |
| `MatchMode` (enum) | `EXACT` (default), `CONTAINS`, `REGEX`. Field values are trimmed and compared case-sensitively, no numeric coercion (edge #18). **[A11]** |
| `TextQuery` (record) | `pattern`, `regex` (vs literal), `caseSensitive` (default false), `dotAll`, `Scope scope`. |
| `Scope` (enum) | `ALL`, `REQUEST`, `RESPONSE`. |
| `QueryCompiler` | Validates and compiles a `Query` into a `CompiledQuery` **before** any I/O. Invalid regex → `QuerySyntaxException` carrying `PatternSyntaxException` description and index (PRD 5.4, AC7). Literal text compiles to `Pattern.quote` + flags (`CASE_INSENSITIVE | UNICODE_CASE` when insensitive). |
| `CompiledQuery` | `boolean mayMatch(RawEntry)`: byte prefilter. `boolean matches(LogEntry, MatchBudget)`: full evaluation, cheapest checks first: level → time range → field filters → text. |
| `BytePrefilter` | Built from literal parts of the query: for each EXACT/CONTAINS field filter, at least one of its values must occur in the entry bytes; a literal text query must occur in the entry bytes. Disabled for a value whose bytes might differ from its decoded form (non-ASCII under case-insensitive matching, JSON-escapable characters `"`, `\`, control chars, and anything when `--encoding` is not UTF-8). |
| `GuardedCharSequence` | Wraps the text being matched. Every 4096 `charAt` calls it checks the per-entry deadline and the scan's cancel flag, and throws `MatchTimeoutException` / `ScanCancelledException`. `subSequence` returns a guarded view. |
| `MatchBudget` | Per-entry deadline (default 1 s, `--regex-timeout`) **[A12]** plus the cancel token. Only used when the query contains a regex; literal matching is not guarded. |
| `Highlighter` | Given a `LogEntry` and a `CompiledQuery`, returns match ranges in the **match text** for the text query and for field values found in the header / JSON. Used by the GUI detail pane and CLI color output. Uses the same guard. |

### 4.6 `scan`

| Type | Responsibility |
|---|---|
| `ScanRequest` (record) | `Path file`, `LogFormat format` (resolved), `CompiledQuery query`, `ParseContext ctx`, `int resultCap` (0 = none), `int threads` (default `availableProcessors()`), `long chunkSize` (default 8 MB; tests use tiny values), `int entryLimit`. |
| `ScanEngine` | Entry point: `ScanHandle start(ScanRequest, ScanListener)`. Owns a fixed thread pool of platform threads per scan. |
| `ChunkPlanner` | Splits `[0, sizeAtOpen)` into fixed-size chunk descriptors. Does no I/O. Boundary alignment happens in the worker. |
| `ChunkWorker` | Scans one chunk (algorithm in section 5.1): frames entries, applies prefilter, parses, matches, records `Hit`s, counts lines, counts malformed / timed-out / truncated entries. |
| `ChunkResult` | Hits of one chunk in file order, its owned line count, its counters. |
| `OrderedMerger` | Emits `ChunkResult`s strictly in chunk order, assigns absolute line numbers, enforces the result cap in file order, and stops the scan when the cap is reached. Chunk 0's hits are forwarded as they are found. |
| `ScanHandle` | `cancel()`, `progress()` (bytes owned-and-scanned / `sizeAtOpen`), `CompletableFuture<ScanSummary> done()`. |
| `ScanListener` (interface) | `onHits(List<Hit>)` (in file order, batched), `onProgress(double)`, `onFinished(ScanSummary)`. Called on scan threads; front ends marshal to their own thread. |
| `ScanSummary` (record) | `matched`, `capped`, `cancelled`, `malformedLines`, `regexTimeouts`, `truncatedEntries`, `fileChangedDuringScan`, `elapsed`. Rendered as the end-of-scan notice in both front ends. |
| `EntryReader` | Re-reads one hit's bytes (`offset`, `byteLength`) from the file and decodes it. Used for display, copy and CLI output. |
| `ReferenceScanner` | Single-threaded, straightforward implementation (whole-chunk = whole-file, no prefilter). Lives in `src/test/java`; it is the oracle for the equivalence tests (AC12). |

### 4.7 `settings`

| Class | Responsibility |
|---|---|
| `SettingsStore` | Reads/writes `${user.home}/.simplelogparser/settings.properties` (recent files list, max 10; last window size; result cap). **[A13]** Contains paths only, never log data (PRD 6). Writes to a temp file in the same settings directory and moves it into place. This is the only class in the code base allowed to open a file for writing (ArchUnit rule, section 9.6). |

### 4.8 `cli`

| Class | Responsibility |
|---|---|
| `SlpCommand` | picocli `@Command`. Options in section 7.1. Converts options to `Query` and `ScanRequest` per file. |
| `FieldArgParser` | Parses `--field` values: `key=value` (EXACT), `key*=value` (CONTAINS), `key~=regex` (REGEX). **[A14]** Repeated keys with the same mode merge into one OR'd filter. |
| `CliRunner` | `int run(String[] args, OutputStream out, PrintStream err)`. In-process entry point used by `Main` and by tests. Validates and compiles everything before opening any file; runs files sequentially (each file's scan is itself parallel); maps outcomes to exit codes. |
| `HitPrinter` (interface) + `RawPrinter`, `JsonPrinter`, `CountPrinter` | `RawPrinter` copies the entry's original bytes to `out` unchanged (AC17); with color on, it decodes, inserts ANSI SGR codes around `Highlighter` ranges and re-encodes. With more than one file, each output line is prefixed with `path:` (grep style) unless `-h/--no-filename`. **[A15]** `JsonPrinter` writes one object per hit via `JsonGenerator`: `{"file","offset","line","entry","truncated"}` (AC18). `CountPrinter` prints the count (per file as `path:count` when several). |
| `TerminalDetector` | Color is on only if not `--no-color`, `NO_COLOR` env var is unset, and stdout is a terminal: `System.console() != null` and, on JDK 22+, `Console.isTerminal()` called reflectively (the build targets 21). |
| `ExitCode` | `MATCH=0`, `NO_MATCH=1`, `ERROR=2`. With several files: 0 if any file matched, 2 if any file errored and none matched. **[A16]** |

### 4.9 `gui`

The GUI follows a view / view-model split so that behavior is testable without a running FX toolkit.

| Class | Responsibility |
|---|---|
| `SlpGuiApp` | `extends Application`. Builds the main window, loads settings, opens files passed on the command line. |
| `MainWindow` | Menu (Open, Recent, Copy, Copy all results), `TabPane` with one `FileTab` per opened file (edge #20), drag-and-drop of files onto the window, `FileChooser`. |
| `FileTab` | Hosts `SearchPane`, `ResultListView`, `DetailPane`, a status bar (progress bar, summary text, Cancel button). Closing the tab cancels its scan and closes its `LogFile`. |
| `SearchViewModel` | Plain Java (JavaFX properties only, no controls). Holds query inputs: field filter rows (field, mode, values), text, regex/literal, case-sensitive, DOTALL, scope, levels, from/to. `compile()` → `CompiledQuery` or validation message shown inline before scanning (AC7). Scope selector disabled for JSON tabs. |
| `ScanController` | Starts a scan for a tab. Cancels the running scan first and tags each scan with a generation number; batches from an older generation are dropped (edge #19). Coalesces `onHits` into the FX thread at most every 100 ms via `Platform.runLater`, so the FX thread never blocks (PRD 6). |
| `ResultListView` | `ListView<Hit>` (virtualized), multi-select, cell shows `HitSummary`. Shows "Results capped at N" banner when `summary.capped()` (AC14). |
| `DetailPane` | RichTextFX `StyleClassedTextArea`, read-only. On selection, a background `Task` re-reads the entry (`EntryReader`), computes highlights (`Highlighter`, guarded) and then sets text and styles on the FX thread. Shows the match text (LF line endings) with `[truncated]` marker for truncated entries. For JSON entries, `DisplayText` renders `\n`, `\r` and `\t` escapes inside string values of the raw line as line breaks and tabs and maps highlight ranges onto the rendered text (PRD 5.5, AC26). A "Word wrap" checkbox binds `wrapText`; the setting is shared by all tabs and stored by `SettingsStore` (AC25). |
| `ClipboardService` | Copy selected entry / entries / detail-pane selection as `ClipboardContent.putString` (Unicode, edge #22). Whole-entry copies use the **original** text re-read from the file, so CRLF and non-ASCII are preserved (AC20). Multiple entries are joined in file order with one blank line, using the line ending of the first entry. **[A17]** Detail-pane partial selection copies the displayed text (LF line endings). **[A18]** "Copy all results" sums `Hit.byteLength` first; over 50 MB (configurable) it asks for confirmation (AC21). The re-read runs in a background task. |
| `ErrorDialogs` | Maps exceptions (missing file, detection failure, I/O error) to a dialog with a clear message (edge #12). Never shows stack traces to the user. |

## 5. Core algorithms

### 5.1 Chunk scan (one worker)

Input: chunk `[s, e)`, file size `N = sizeAtOpen`, format handler.

1. **Align.** If `s == 0`, start at 0. Otherwise read the byte at `s - 1`: if it is `\n`, `s` is a line start; else skip to the byte after the next `\n`. From there, skip lines until `isEntryStart(line)` is true. The first such line's offset is `ownedStart`. If `ownedStart >= e` (or EOF), the chunk owns nothing and reports zero lines.
2. **Frame.** Read lines from `ownedStart`. An entry starts at an entry-start line and extends over following lines until the next entry-start line or EOF. Lines are appended to the `EntryBuffer` (truncating beyond the entry limit).
3. **Own.** An entry belongs to this chunk if its start offset `< e`. The worker stops at the first entry-start line whose offset is `>= e` (the next chunk owns it), or at `N`. Bytes past `N` are never read, even if the file grew (edge #13).
4. **Headerless prefix.** Only in chunk 0, for the plain format: lines before the first header form one `HeaderlessEntry` (edge #3). For JSON every non-blank line is an entry start, so no prefix exists.
5. **Match.** For each owned entry: `query.mayMatch(raw)` → if false, skip; else `handler.parse(raw)` (malformed → count, or abort under `--strict`) → `query.matches(entry, budget)` (timeout → count as `regexTimeouts`, continue) → on match, append a `Hit` with a chunk-relative line number.
6. **Count lines.** The worker counts `\n` bytes from `ownedStart` to the end of its last owned entry. Chunk 0 also counts the headerless prefix. Absolute line numbers are assigned by the merger: `base(i) = 1 + Σ lines(j) for j < i`.
7. **Cancel.** Workers check the cancel token between entries and inside `GuardedCharSequence`.

Chunk ownership is a partition of the file's entries: every entry has exactly one start offset, and exactly one chunk contains it. That is why results are identical to a sequential scan for any chunk size ≥ 1 byte (AC12, edge #1). A tiny chunk may contain no entry start and simply owns nothing.

### 5.2 Ordered merge, cap and memory bound

- Chunks are submitted to the pool in order, but at most `2 × threads` chunks are in flight or buffered at once. A new chunk is submitted only when the merger has emitted the oldest one. This bounds buffered hits independently of file size (PRD 6 "Scale").
- The merger forwards hits of the oldest unfinished chunk as they are found (chunk 0 streams live; later chunks stream once they become the oldest). Other chunks' hits wait in their `ChunkResult`.
- Result cap: the merger counts emitted hits. When the count reaches the cap, it emits nothing more, sets `capped`, and cancels the scan. Workers additionally stop after `cap` local hits, because no chunk can contribute more than that. The capped set is always the **first** N hits in file order, regardless of thread timing.
- After the last chunk, the engine compares `LogFile.currentSize()` with `sizeAtOpen` and sets `fileChangedDuringScan` if they differ (edge #13).

### 5.3 Scope rules

| Format | ALL | REQUEST / RESPONSE |
|---|---|---|
| Plain | whole match text (header + continuation lines) | that section's range of the match text (empty if absent → no match) |
| JSON | decoded `message` + raw line | not applicable: GUI disables the selector; CLI prints a warning to stderr and uses ALL, per PRD 4.2 **[A19]** |

### 5.4 Time range

`--from` / `--to` accept ISO-8601 with or without an offset (`2026-10-18T00:00`, `2026-10-18T00:00:00+07:00`, `...Z`). Values without an offset are interpreted in `plainZone` (Asia/Jakarta by default) for both formats. The CLI help text and the GUI tooltip state the zone (edge #17). `from` is inclusive, `to` is exclusive. **[A20]** Entries without a timestamp (headerless) never match a time filter.

## 6. Cross-cutting concerns

### 6.1 Read-only guarantee

- `LogFile` is the only class that opens log files, and only with `READ`.
- No temp files: nothing in core uses `Files.createTemp*`.
- ArchUnit rules (section 9.6) forbid write APIs (`FileOutputStream`, `FileWriter`, `Files.write*`, `Files.newOutputStream`, `Files.newBufferedWriter`, `Files.createTemp*`, `FileChannel.open` outside `LogFile`) everywhere except `settings`.
- AC15 is verified by an integration test (section 9.4).

### 6.2 Error handling

| Situation | CLI | GUI |
|---|---|---|
| Missing / unreadable file, empty file, no valid entries, detection failure | message on stderr, exit 2 | dialog, tab not opened |
| Invalid regex | message with pattern, error description and index, before opening files; exit 2 | inline validation under the field; Search disabled |
| Malformed JSON line | counted, summary on stderr at end | counted, shown in status bar |
| Regex timeout per entry | counted, summary on stderr | counted, shown in status bar |
| I/O error mid-scan | message on stderr, exit 2 | dialog; partial results kept and marked incomplete |
| `OutOfMemoryError` | not caught; bounded memory design must prevent it (AC11) | same |

An empty file or a file with no valid entries is an error (exit 2), not "no match" (edge #12).

### 6.3 Threading

- Scan pool: platform threads, one fixed pool per scan, size `threads`. Shut down when the scan finishes or is cancelled. Cancel is cooperative; `ScanHandle.cancel()` returns immediately, and workers notice within one entry or within 4096 regex `charAt` calls, which keeps cancel latency under 2 s (AC10, AC13).
- GUI: FX thread only renders. Scans, entry re-reads, highlighting and clipboard preparation run in background tasks.

### 6.4 Memory budget (for `-Xmx512m`)

| Item | Bound |
|---|---|
| Per worker: line buffer 1 MB + entry buffer up to 16 MB + decoded text (2 bytes/char, up to 32 MB) | ~50 MB worst case per worker |
| Workers | `threads` (cap the default at 8 under small heaps: `min(cores, max(1, maxHeap / 64 MB))`) **[A21]** |
| Buffered hits | `2 × threads` chunks × hits per 8 MB chunk; a `Hit` is ~100 bytes with summary, so the worst case (every tiny entry matches) is tens of MB |
| GUI result list | capped (default 10,000 hits) |

The CLI has no default cap (grep semantics); `--max-count N` is the CLI's cap and triggers the "capped" message on stderr (AC14). **[A22]**

### 6.5 Security properties

No network access, no telemetry, no redaction (edge #23). JSON output escapes all content via `JsonGenerator`. ANSI output is only produced for terminals.

### 6.6 Performance budget

Target (PRD 6): literal/field scan of 5 GB in under 60 s, i.e. ≥ 85 MB/s overall. Assumed workstation: 8 cores, NVMe SSD. **[A23]** The design relies on:

- the byte prefilter skipping decode and parse for most entries in a selective search;
- the plain header byte check avoiding regex on non-header lines;
- one reused buffer set per worker (no per-line allocation).

The `perf` profile (section 9.5) measures this; if the target is missed, profile before changing the design.

## 7. Front-end details

### 7.1 CLI options

```
slp [options] <file>...
  --field KEY=VALUE | KEY*=VALUE | KEY~=REGEX   repeatable; same key OR'd, different keys AND'd
  --text TEXT            literal text query
  --regex PATTERN        regex text query (mutually exclusive with --text)
  --in all|request|response   text scope (default all)
  --level LEVEL[,LEVEL]  level filter
  --from TIME  --to TIME time range (see 5.4)
  --zone ZONE            zone for plain timestamps and offset-less --from/--to (default Asia/Jakarta)
  --case-sensitive       case-sensitive text matching (default: insensitive)
  -i, --ignore-case      explicit default; accepted for grep habits
  --dotall               DOTALL flag for --regex
  --format auto|plain|json   (default auto)
  --encoding CHARSET     ASCII-compatible charsets only (default UTF-8)
  --strict               abort on malformed JSON lines
  --count                print the number of matching entries only
  -m, --max-count N      stop after N matching entries
  --json                 one JSON object per hit
  --no-color             disable ANSI highlighting
  -h, --no-filename      do not prefix output with the file name
  --threads N            scan threads (default: cores)
  --regex-timeout MS     per-entry regex timeout (default 1000)
  --gui                  open the GUI (with optional files)
  --help, --version
```

The PRD lists `-i/--case-sensitive`. Because `-i` means "ignore case" in grep and insensitive is already the default, this design gives `-i` its grep meaning and keeps `--case-sensitive` as a long option only. **[A24]**

Summary lines (malformed count, regex timeouts, capped, file changed) go to stderr, so stdout stays clean for piping.

### 7.2 GUI layout

```
+---------------------------------------------------------------+
| File  Edit                                                     |
+--[ log-plain.log ]--[ other.log ]-----------------------------+
| Fields: [merchantId][exact v][786, 787]  [+]                   |
| Text:   [orderId=2837537    ] (o)Literal ( )Regex [ ]Case [ ]. |
| Scope:  (o)All ( )Request ( )Response   Level: [x]ERROR [x]WARN|
| From: [          ] To: [          ] (Asia/Jakarta)  [Search]   |
+---------------------------+-----------------------------------+
| 00:00:04.537 INFO  Gist.. | [7DVay...:...] 2026-10-18 00:00:..|
| 00:00:43.373 ERROR Outl.. | -- REQUEST --                     |
| ...  (virtualized list)   | |> GET /pos-revamp-2/...orderId=  |
|                           |    ^^^^^^^^^^^^^^^ highlighted    |
+---------------------------+-----------------------------------+
| [=======>      ] 42%  1,204 hits   [Cancel]   2 malformed     |
+---------------------------------------------------------------+
```

## 8. Implementation order

1. Toolchain spike (2.3) and `pom.xml` (2.2), with JUnit and ArchUnit wired in.
2. `io` + `format.plain` + `format.json` + `FormatDetector`, with unit tests and fixtures.
3. `query` (compiler, predicates, guard, prefilter).
4. `scan` single-threaded path, then chunked parallel path with the equivalence test suite.
5. `cli` with integration tests for AC1–AC18 and AC22–AC24.
6. `perf` profile and the 5 GB run (AC11).
7. `gui` view-models, then views; TestFX smoke tests; manual clipboard checks.
8. Packaging scripts (`slp.cmd`, `slp`) and README usage section.

## 9. Testing strategy

### 9.1 Levels

| Level | What | Runs |
|---|---|---|
| Unit | Parsers, MDC, sections, detector, decoder, line reader, query compiler, predicates, prefilter, guard, field arg parser | every `mvnw test` |
| Component | `ScanEngine` against fixtures and generated files; equivalence vs `ReferenceScanner` | every `mvnw test` |
| CLI integration | `CliRunner.run` in-process with captured byte streams | every `mvnw test` |
| Architecture | ArchUnit rules | every `mvnw test` |
| Performance / scale | 5 GB generated file at `-Xmx512m` | `-Pperf`, manually and before release |
| GUI | View-model unit tests (default build); TestFX smoke tests | view-models: every build; TestFX: `-Pgui` |
| Manual | Notepad paste, drag-and-drop, visual highlight check | before release, checklist in `docs/manual-test.md` |

### 9.2 Fixtures

`sample-logs/` is git-ignored and contains personal data (emails, phone numbers, addresses), so tests must not depend on it. **[A25]** Instead:

- `src/test/resources/fixtures/plain/*.log` and `fixtures/json/*.log`: small files derived from the samples, with personal data replaced by synthetic values, keeping structure (MDC variants including `[id:id:]`, request/response dumps, long single-line JSON bodies, stack traces, CRLF line endings).
- Purpose-built edge-case fixtures: headerless prefix, header-like line in a stack trace, missing/duplicated section markers, mixed line endings, BOM, invalid UTF-8 bytes, multi-byte characters, malformed / blank / truncated JSON lines, nested JSON values, escaped `\n` / `\"` / `\uXXXX` in messages, an entry larger than a small configured entry limit.
- Expected results are hand-counted and asserted as lists of `(lineNumber)` per query in the test, so a failure shows exactly which entry differs (AC2).
- `LogGenerator` (test code): writes deterministic synthetic plain or JSON logs of a requested size and seed, with known numbers of entries per `merchantId` / `requestId`, so counts are known without hand counting. Used by equivalence and perf tests; writes only into JUnit `@TempDir`.

### 9.3 Equivalence and boundary tests (AC12, edge #1, #2)

- For every fixture and for generated files: run `ScanEngine` with chunk sizes `1, 2, 3, 7, 64, 4096, 8 MB` and thread counts `1, 4`, and assert the hit list (offset, length, line) equals `ReferenceScanner`'s output for the same query.
- Run the same with the prefilter disabled to prove it causes no false negatives.
- Fixture with multi-byte UTF-8 characters positioned so that boundaries fall inside them.
- Seeded random generated files (fixed seeds listed in the test, so failures reproduce) to cover boundary positions the hand fixtures miss.

### 9.4 Acceptance-criteria traceability

| AC | Test |
|---|---|
| AC1 | `FormatDetectorTest`; `CliFormatIT` (neither format → exit 2, message) |
| AC2–AC5a | `FieldSearchIT` on plain and JSON fixtures, hand-counted line lists |
| AC6 | `ScopeSearchIT`: text present only in request vs only in response |
| AC7 | `CliRegexIT`: invalid regex → exit 2, message contains index; a spy `ScanEngine` asserts no scan started |
| AC8 | `RegexSpanIT`: pattern across lines in one entry matches; same pattern across two adjacent entries does not |
| AC9 | `CaseSensitivityTest` (CLI) and `SearchViewModelTest` (GUI toggle produces the same `Query`) |
| AC10 | `RegexGuardTest`: `(a+)+$` against 30 `a`s plus `!` times out within budget and is counted; `CancelLatencyIT`: cancel during a catastrophic regex completes within 2 s |
| AC11 | `ScalePerfTest` (`perf`): generated 5 GB file, `-Xmx512m`, asserts completion and records peak heap from `MemoryPoolMXBean` peak usage; also runs at 1 GB and asserts the peak heap does not grow meaningfully with size (within 20%) |
| AC12 | section 9.3 |
| AC13 | `ScanEngineStreamingTest`: listener receives hits before `onFinished`; progress monotonically increases to 1.0; cancel latency (as AC10). GUI part: TestFX smoke test |
| AC14 | `ResultCapIT`: cap N returns exactly the first N hits in file order with `capped=true`, for several thread counts; CLI `--max-count` prints the capped notice |
| AC15 | `ReadOnlyIT`: copy fixture into `@TempDir`, mark it read-only (`DosFileAttributeView` on Windows, POSIX permissions elsewhere), record directory listing, size, mtime, SHA-256; run field, text, regex and count searches; assert all unchanged. Plus ArchUnit write-API rules |
| AC16 | `CliExitCodeIT` |
| AC17 | `CliRawOutputIT`: output bytes equal the fixture's byte ranges exactly (CRLF fixture and LF fixture); no `ESC` byte when stdout is not a terminal or with `--no-color` |
| AC18 | `CliJsonOutputIT`: each line parses as a JSON object with `offset`, `line`, `entry`; `entry` equals the original text |
| AC19 | TestFX `SearchFlowGuiTest` (`gui`) + manual checklist |
| AC20 | `ClipboardServiceTest` builds the clipboard string from fixtures (exact bytes, CRLF, non-ASCII, multi-select order and separator); manual Notepad paste check |
| AC25 | `SettingsStoreTest` (stored setting); `SearchFlowGuiTest` (checkbox toggles wrapping and is saved) |
| AC26 | `DisplayTextTest` (rendering and highlight mapping); `SearchFlowGuiTest` (rendered in the pane, copy keeps escapes) |
| AC21 | `ClipboardServiceTest`: total over threshold triggers the confirmation callback and reads nothing before confirmation |
| AC22 | `JsonMalformedIT`: entries before and after malformed lines are returned; count reported; `--strict` → exit 2 |
| AC23 | `HeaderlessPrefixIT` |
| AC24 | `InvalidUtf8IT`: scan completes, U+FFFD in decoded text, raw output bytes unchanged |

`*Test` classes are unit/component tests; `*IT` classes are integration tests that still run under Surefire in the default build (no Failsafe needed, as they need no packaged jar). **[A26]**

### 9.5 Performance tests

- `@Tag("perf")`, run with `.\mvnw.cmd test -Pperf`. Surefire forks with `-Xmx512m`.
- The generated 5 GB file goes to `@TempDir` (system temp, not the repo); the test is skipped with a message if free disk space is below 6 GB.
- Records throughput (MB/s) and peak heap for: field EXACT search, literal text search, regex search. Asserts AC11 (no OOM, flat heap). The 60 s throughput target is reported, not asserted, because it depends on hardware. **[A27]**

### 9.6 Architecture tests (ArchUnit)

- `..simplelogparser.(io|model|format|query|scan|settings)..` must not depend on `javafx..`, `org.fxmisc..`, `picocli..`, `..cli..`, `..gui..`.
- `..cli..` must not depend on `..gui..` and vice versa.
- Only `..settings..` may use file write APIs; only `io.LogFile` may call `FileChannel.open` (section 6.1).
- No class may call `System.exit` except `Main`.

### 9.7 GUI tests

- View-models (`SearchViewModel`, `ScanController` with a fake engine, `ClipboardService` with a fake clipboard) are plain JUnit tests in the default build.
- TestFX smoke tests (`@Tag("gui")`): open a fixture, run field / text / regex searches, select an entry, check that highlight style spans exist, Ctrl+C puts the expected string on the clipboard, Cancel stops a long scan. They need a desktop session (or Monocle headless if available for the chosen JavaFX version), so they are not part of the default build.

## 10. Known limitations

- A header-like line inside a message (e.g. a logged log line) splits an entry (edge #4).
- MDC values containing `", "` or `": "` are mis-split (edge #5); text search remains a fallback.
- Nested JSON keys are not addressable as fields (A9); use text/regex search.
- Every search is a full pass over the file (PRD 2).

## 11. Assumptions

| ID | Assumption | Where it matters |
|---|---|---|
| A1 | Library versions listed are minimums; latest patch releases are used and verified in the spike. | 2.1 |
| A2 | Shipping all four JavaFX platform classifiers in one jar is acceptable (adds ~30 MB). If size matters, ship Windows only. | 2.1, packaging |
| A3 | Users invoke `slp` through provided wrapper scripts or `java -jar`; no native launcher (PRD 6). | 2.2 |
| A4 | Only ASCII-compatible encodings are supported for `--encoding`. | 4.2 |
| A5 | Level set is TRACE/DEBUG/INFO/WARN/ERROR/FATAL; other values map to UNKNOWN. | 4.3 |
| A6 | Detection inspects at most 64 KB / 50 non-empty lines. A plain file whose first header is beyond that fails detection and needs `--format plain`. | 4.4 |
| A7 | Empty positional MDC tokens mean the field is absent; duplicate MDC keys keep the first value. | 4.4.1 |
| A8 | Request/response sections include the `|>` / `|<` prefixes; text search runs on that text as-is. | 4.4.1 |
| A9 | JSON top-level object/array values are searchable as their compact raw JSON string; nested keys are not addressable. JSON `null` means the field is absent. | 4.4.2 |
| A10 | Filters on the same field with different match modes are AND'd. | 4.5 |
| A11 | Field values are compared case-sensitively (ids are case-sensitive base64-like strings). Level filters are case-insensitive. | 4.5 |
| A12 | Default per-entry regex timeout is 1 s. | 4.5 |
| A13 | Settings live in `${user.home}/.simplelogparser/settings.properties`. | 4.7 |
| A14 | CLI field syntax `key=value` / `key*=value` / `key~=regex` for exact / contains / regex. | 4.8 |
| A15 | With several files, every output line is prefixed with `path:` (like grep); `-h` suppresses it. Byte-identical output (AC17) applies to single-file or `-h` runs. | 4.8 |
| A16 | Multi-file exit code: 0 if any file matched, otherwise 2 if any file errored, otherwise 1. | 4.8 |
| A17 | Multi-entry copy separator is one blank line using the first entry's line ending. | 4.9 |
| A18 | Copying a partial detail-pane selection yields LF line endings (the displayed text); whole-entry copies preserve original line endings. | 4.9 |
| A19 | `--in request|response` on a JSON file warns and falls back to ALL, rather than erroring or matching nothing. | 5.3 |
| A20 | `--from` is inclusive, `--to` exclusive; offset-less values use `--zone` for both formats. | 5.4 |
| A21 | Default worker count is limited by heap size so that worst-case entry buffers fit in `-Xmx512m`. | 6.4 |
| A22 | The 10,000 result cap applies to the GUI only; the CLI is uncapped unless `--max-count` is given. | 6.4 |
| A23 | Reference hardware for the throughput target: 8 cores, NVMe SSD, Windows 11. | 6.6 |
| A24 | `-i` means ignore-case (grep convention, the default); the PRD's `-i/--case-sensitive` pairing is treated as a typo. | 7.1 |
| A25 | Test fixtures are sanitized copies of the samples committed under `src/test/resources`; the samples themselves are not committed. | 9.2 |
| A26 | Integration tests run in-process under Surefire; Failsafe is not needed. | 9.4 |
| A27 | The 60 s throughput target is measured and reported, not enforced by an automated assertion. | 9.5 |
