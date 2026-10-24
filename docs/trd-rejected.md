# TRD: rejected decisions

Alternatives considered for [trd.md](trd.md) and why they were not chosen. Decisions already settled in the PRD (no index, JavaFX over Swing, no boolean query language, no installer) are not repeated here, except where the TRD had a further choice to make.

Each entry lists the option, the reason it was rejected, and when to reconsider it.

## I/O and scanning

### R1. Memory-mapped files (`FileChannel.map`) for reading

- **Rejected because:** On Windows a mapped region keeps the file mapped until the `MappedByteBuffer` is garbage-collected (there is no supported unmap in Java 21 without `Unsafe` or the FFM `Arena` API). That blocks other tools from deleting or renaming the log, which users would experience as the tool "locking" files. Mapping 5 GB also inflates the process's reported memory, which confuses the "bounded memory" requirement. Positional `FileChannel.read` into a reused 1 MB buffer is sequential, simple, and close to mmap speed for a single forward pass.
- **Reconsider if:** the perf profile shows read throughput is the bottleneck. A Java 22+ FFM `Arena.ofConfined()` mapping can be unmapped deterministically, but it needs a higher target than Java 21.

### R2. `BufferedReader.readLine()` / `Files.lines()` over decoded characters

- **Rejected because:** Decoding before framing makes byte offsets unknown (needed for hits, re-reads and `--json`), cannot start at an arbitrary byte position without risking a split UTF-8 sequence (edge #2), loses the original line terminators (AC17), and allocates a `String` per line. `readLine` also has no line-length bound (edge #7).
- **Reconsider if:** never for the scan path.

### R3. Load the whole file (or a whole chunk) into memory

- **Rejected because:** Violates the bounded-memory requirement (PRD 6, AC11).

### R4. Parallel streams / `ForkJoinPool` with a global hit counter for the cap

- **Rejected because:** A shared counter makes the capped result set depend on thread timing, so the "first N" would differ between runs and from a sequential scan (breaks AC12/AC14 determinism). Parallel streams also give no control over how many chunks are buffered, so memory would not be bounded. A fixed pool with an ordered merger and an in-flight window gives both determinism and a memory bound.
- **Reconsider if:** never; the explicit design is not much more code.

### R5. Virtual threads for chunk workers

- **Rejected because:** Scanning is CPU-bound (decoding, regex). Virtual threads help with blocking I/O concurrency, not CPU work, and they add pinning concerns around synchronized code in libraries. A fixed platform-thread pool sized to the core count fits better.

### R6. Splitting chunks at entry boundaries in a pre-pass

- **Rejected because:** A pre-pass to find entry starts is a full scan on its own, which breaks "opening is O(1)" (PRD 5.1) and doubles the I/O. The ownership rule (an entry belongs to the chunk containing its first byte, with alignment done by each worker) needs no pre-pass.

### R7. Overlap-and-deduplicate chunk boundaries

- **Rejected because:** Letting chunks overlap and removing duplicate hits by offset afterwards needs a set of seen offsets (memory) and still has to define which chunk owns line counts. The ownership rule avoids duplicates by construction.

### R8. Running each regex match on a separate thread with `Future.get(timeout)`

- **Rejected because:** `Matcher` ignores thread interrupts, so a timed-out match keeps burning a core until it finishes on its own, and a task hand-off per entry is far too slow for millions of entries. The `GuardedCharSequence` wrapper stops the match from inside, on the same thread, at almost no cost.

### R9. RE2/J (linear-time regex) instead of `java.util.regex`

- **Rejected because:** PRD 5.4 requires Java regex syntax. RE2/J lacks backreferences and lookaround and differs in details, so users' patterns would behave differently from what they expect. It would remove catastrophic backtracking, but the guard already contains it.
- **Reconsider if:** users report frequent regex timeouts. RE2/J could be offered as an opt-in engine (`--engine re2`).

### R10. Prefilter that can produce false negatives (e.g. skipping entries whose header lacks the value)

- **Rejected because:** Any false negative is a silent wrong answer. The prefilter is restricted to checks that can only let extra entries through, and an equivalence test runs every query with the prefilter disabled.

## Format handling

### R11. Jackson databind / tree model (`ObjectMapper.readTree`) per JSON line

- **Rejected because:** Builds a full object tree for every line, most of which are then rejected. The streaming `JsonParser` reads keys and scalar values directly and copies raw text only for nested values, with much less allocation. Databind also adds a large dependency.

### R12. Gson, org.json, or Jakarta JSON-P

- **Rejected because:** Jackson's streaming parser is the fastest of the common options, gives raw-text positions for nested values, and also provides the `JsonGenerator` needed for `--json` output, so one library covers both needs.

### R13. Flattening nested JSON into dotted field names (`logData.responseStatusCode`)

- **Rejected because:** The PRD only asks for top-level keys. Flattening would collide with real dotted keys that ECS already uses at top level (`log.level`, `service.name`), and large nested objects (headers, bodies) would create many fields per line. Nested values remain searchable through text/regex.
- **Reconsider if:** users need fields inside `logData` (for example `responseStatusCode`). A `logData.responseStatusCode`-style path syntax with a distinct prefix (e.g. `$.logData.responseStatusCode`) would avoid the collision.

### R14. Stripping `|>` / `|<` prefixes from request/response sections before matching

- **Rejected because:** It would make the matched text differ from the displayed text, so highlight offsets would need a mapping layer, and the user could not search for the prefixes themselves. Keeping the prefixes is simpler; regex users can account for them.
- **Reconsider if:** users find `^`-anchored regexes on bodies awkward. A `--strip-prefixes` option could be added.

### R15. Header detection with the full regex on every line

- **Rejected because:** Most lines in the plain sample are continuation lines (stack traces, long JSON bodies up to 22 KB per line). Decoding and regex-matching each one costs far more than checking a few bytes. The byte prefilter rejects almost all non-header lines, and the full regex runs only on candidates.

### R16. Mapping JSONC request/response fields (`logData.requestBody` / `responseBody`) to the Request/Response scopes

- **Rejected because:** PRD 4.2 states JSONC has no sections and scope is always All. The sample's `logData` objects do carry request/response bodies, so this is a plausible future feature, but it would be a PRD change.
- **Reconsider if:** the PRD owner wants request/response scope for JSONC; `JsonEntry.section()` is the single place to add it.

### R17. Erroring (exit 2) or matching nothing for `--in request|response` on JSON files

- **Rejected because:** PRD 4.2 says scope is always All for JSONC. Erroring would break multi-file runs that mix formats; matching nothing would silently return an empty result. A warning plus fallback to All follows the PRD and stays visible. (Recorded as assumption A19 in the TRD.)

## Front ends

### R18. Apache Commons CLI, JCommander, or a hand-written argument parser

- **Rejected because:** picocli supports repeatable options, typed converters, mutually exclusive groups (`--text` vs `--regex`), generated help and clean exit-code handling with no transitive dependencies. Hand-written parsing would re-implement all of that.

### R19. JavaFX `TextArea` for the detail pane

- **Rejected because:** It cannot style ranges, so matches cannot be highlighted (PRD 5.5), and it is slow with multi-MB text.

### R20. `TextFlow` of `Text` nodes for the detail pane

- **Rejected because:** Not virtualized: a 16 MB entry with many highlights creates huge node graphs and freezes the FX thread. Text selection across nodes is also poor, which hurts copy (PRD 5.6).

### R21. `WebView` (HTML rendering) for the detail pane

- **Rejected because:** Adds the `javafx-web` module (large native WebKit libraries per platform), requires HTML-escaping all log text, and copy from a WebView may carry HTML formatting rather than exact plain text.

### R22. `java.util.prefs.Preferences` for recent files

- **Rejected because:** On Windows it writes to the registry (`HKCU\Software\JavaSoft\Prefs`), not to the user profile directory that PRD 6 specifies, and it is harder for users to inspect or delete. A properties file under `${user.home}/.simplelogparser/` is transparent.

### R23. Holding full entry text in the result list

- **Rejected because:** 10,000 results of multi-MB entries could exceed the heap. Hits store offsets and a short summary; text is re-read on demand (edge #21).

### R24. Separate CLI and GUI jars, or a multi-module Maven build (`core`, `cli`, `gui`)

- **Rejected because:** PRD 6 requires one artifact that serves both. Separate modules would enforce the layering at compile time, but the project is small and an ArchUnit test enforces the same rules within one module with less build complexity.
- **Reconsider if:** the core is reused by another tool, or the GUI dependencies need to be optional.

### R25. JPMS `module-info.java` for the application

- **Rejected because:** The shaded jar puts everything on the classpath, which conflicts with a named application module, and JavaFX on the module path would need launcher flags that `java -jar` cannot carry. The cost (a startup warning about JavaFX in the unnamed module) is acceptable.
- **Reconsider if:** packaging moves to `jlink`/`jpackage` (currently a PRD non-goal).

### R26. Always enforcing the 10,000 result cap in the CLI

- **Rejected because:** grep-style users expect all matches when piping to a file, and the CLI does not hold results in memory (hits are printed and discarded), so the reason for the GUI cap does not apply. `--max-count` gives users the cap when they want it. (Recorded as assumption A22.)

### R27. `-i` meaning `--case-sensitive`, as written in PRD 5.7

- **Rejected because:** It inverts grep's `-i` (ignore case), which is the main habit of the target users, and matching is already insensitive by default. (Recorded as assumption A24, pending PRD owner confirmation.)

## Testing

### R28. Tests that read `sample-logs/` directly

- **Rejected because:** The folder is git-ignored, so tests would fail in other checkouts, and it contains personal data that should not be copied into the repository as-is. Sanitized fixtures and a seeded generator replace it.

### R29. jqwik (property-based testing library) for boundary tests

- **Rejected because:** JUnit parameterized tests over a fixed list of chunk sizes and seeds cover the boundary space well, reproduce exactly, and need no extra dependency or engine.
- **Reconsider if:** boundary bugs slip through the fixed seeds.

### R30. Mockito for test doubles

- **Rejected because:** The seams that need doubles (`ScanEngine` for the CLI and GUI controllers, the clipboard) are small interfaces that hand-written fakes cover clearly.

### R31. TestFX GUI tests in the default build

- **Rejected because:** They need a display or a working Monocle headless build for the chosen JavaFX version, which is a frequent source of flaky CI failures. Behavior lives in view-models tested in the default build; TestFX runs under `-Pgui`.

### R32. Asserting the 60-second throughput target in an automated test

- **Rejected because:** The result depends on disk, CPU and background load, so the assertion would be flaky and machine-specific. The perf profile reports throughput; the memory criterion (AC11), which is hardware-independent, is asserted.

### R33. Maven Failsafe for integration tests

- **Rejected because:** The integration tests call `CliRunner` and `ScanEngine` in-process and do not need the packaged jar, so Surefire is enough. A single packaged-jar smoke test can be added to Failsafe later if packaging regressions appear.
