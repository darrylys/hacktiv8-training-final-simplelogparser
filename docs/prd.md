# PRD: simplelogparser

## 1. Summary

A read-only tool for searching very large (multi-GB) application log files, usable as a JavaFX GUI and as a non-interactive, grep-style CLI. It understands two input formats (Spring Boot multi-line text, and one-JSON-object-per-line ECS-style) and lets users find whole log entries by MDC/context fields, by request/response text, or by regex.

Source requirements: [docs/req.md](req.md). Format details: [CLAUDE.md](../CLAUDE.md).

## 2. Decisions taken

| Topic | Decision | Tradeoff accepted |
|---|---|---|
| Large files | Streaming sequential scan, no index | Every search costs a full pass (seconds to minutes on multi-GB). Nothing is ever written, which satisfies the read-only constraint. Mitigated by parallel scan, progress, cancel, and incremental results. |
| GUI | JavaFX | Needs OpenJFX dependencies and a more involved packaging step than Swing. In return: virtualized lists and better text controls. |
| Filter logic | AND across fields, OR within a field | No arbitrary boolean/NOT expressions in v1. |
| Match unit | Whole entry; text/regex scope selectable (all / request / response) | No cross-entry "request stitching" in v1. |

## 3. Users and use cases

Developers and support engineers triaging production incidents from copied log files.

1. "Show everything for requestId X" (and/or correlationId Y, merchantId 786, shiftId 147581, ...).
2. "Find requests whose request/response body contains `orderId=2837537`."
3. "Regex search for `\"status\":\s*5\d\d` in responses."
4. Do the above on a 5 GB file without loading it into memory.
5. Select a result, copy it, paste into Notepad or a ticket.

## 4. Input formats

### 4.1 Plain text (Spring Boot)

- Entry header: `[<mdc>] yyyy-MM-dd HH:mm:ss.SSS [<thread>] LEVEL <logger> - <message>`.
- `<mdc>` is colon-separated. The sample begins with two positional ids, the first is `correlationId` and the second is `requestId`, followed by `key: value` pairs separated by `, `. The number of fields varies.
- Any line that does not match the header belongs to the previous entry (request/response dumps with `-- REQUEST --` / `-- RESPONSE --`, `|>` / `|<` prefixes, inline JSON, stack traces).
- Request section = lines under `-- REQUEST --` until `-- RESPONSE --`; response section = lines from `-- RESPONSE --` to entry end. Entries without these markers have neither section.

### 4.2 JSONC (one JSON object per line)

- Keys: `@timestamp`, `log.level`, `process.thread.name`, `log.logger`, `message`. Messages may contain escaped newlines, which are decoded for display and search.
- Every other top-level key is a searchable field (e.g. `traceId`, `requestId`, `correlationId`, `shiftId`, `merchantExternalId`, `outletExternalId`, `service.name`). In the sample, 6 of 15 lines carry these; lines without a key never match a filter on it. Values are compared as strings.
- Field names are not aliased across formats: plain `merchantId` and JSONC `merchantExternalId` are different fields.
- Ids that appear only inside `message` text are not extracted; use text/regex search for them.
- Request identity: in plain logs, entries sharing a `requestId` belong to the same request; in JSONC, entries sharing a `traceId` do. An entry with none of requestId/correlationId/traceId is standalone and unrelated to any other entry.
- JSONC has no `-- REQUEST --` / `-- RESPONSE --` sections, so the Request/Response text scopes apply to plain logs only. For JSONC, scope is always All.

### 4.3 Format detection

Auto-detect from the first non-empty lines (JSON object vs. plain header). The user can override (`--format plain|json|auto`). Detection failure is an error, not a guess.

## 5. Functional requirements

### 5.1 Open file
- GUI: file chooser, drag-and-drop, and recent files. CLI: file path argument(s).
- Opening is O(1): no up-front full scan is required to start searching.

### 5.2 Field search
- Searchable fields: any MDC key (`merchantId`, `shiftId`, `outletId`, `merchantUserId`, ...), plus the positional `correlationId` / `requestId` (plain), any top-level key (JSONC), and the built-in `level`, `thread`, `logger`, `timestamp`.
- In plain logs, `correlationId` is the first MDC token and `requestId` is the second. In JSONC they are ordinary top-level keys, and `traceId` identifies a request.
- Searching by `requestId` (plain) or `traceId` (JSONC) returns every entry carrying that id, in file order, so a user sees all entries of one request. Grouped display remains a non-goal.
- Each filter is `field = value`. Several values for one field are OR'd. Different fields are AND'd.
- Value match modes: exact (default), contains, regex.
- Time range filter (from/to) on the entry timestamp.
- Level filter (e.g. ERROR and WARN only).

### 5.3 Text search
- Text query with scope: **All**, **Request**, **Response**.
- Options: case-sensitive toggle (default insensitive), literal vs. regex.
- Field filters and text queries combine with AND.

### 5.4 Regex
- Java `java.util.regex` syntax. Invalid patterns are rejected before the scan starts, with the parse error and position.
- Patterns are matched per entry, so a pattern can span lines within one entry (DOTALL available via flag), never across entries.
- Pathological patterns must not hang the app: each scan is cancellable, and per-entry matching has a timeout guard.

### 5.5 Results
- Each hit is the whole entry. Plain: header plus all continuation lines. JSON: the full original line.
- GUI: a virtualized result list (entry summary: timestamp, level, logger, key ids) and a detail pane with the full entry. Matches are highlighted. Results stream in as they are found, and the user can cancel.
- A result cap (default 10,000 entries) is configurable. When hit, the scan stops and the UI says so, so the user is not shown a silently truncated set.
- CLI output: raw entries to stdout in file order; options `--count`, `--max-count N`, `--json` (one JSON object per hit, with byte offset and line number), `--no-color`.

### 5.6 Clipboard
- GUI: copy the selected entry, multiple selected entries, or the full detail-pane selection as plain text (Ctrl+C). "Copy all results" has a size guard (confirm over a threshold, e.g. 50 MB).
- Copied text is the original text exactly (including line breaks and non-ASCII characters), so it pastes identically into Notepad.
- CLI: stdout piping covers this use case (`| clip` on Windows).

### 5.7 CLI
- Non-interactive, grep-like: `slp [options] <file>...` exits after printing.
- Options mirror the GUI: `--field key=value` (repeatable; repeated keys are OR'd), `--text`, `--regex`, `--in request|response|all`, `--level`, `--from`, `--to`, `-i/--case-sensitive`, `--format`.
- Exit codes: 0 = at least one match, 1 = no match, 2 = error (like grep).
- The same artifact launches the GUI when run with no arguments (or with `--gui`).

## 6. Non-functional requirements

- **Read-only:** files opened with read-only access (`StandardOpenOption.READ` only). No index, cache, temp, or lock file is written next to or about the log. App preferences (recent files) live in the user profile dir, are not derived from log contents, and never contain log data.
- **Scale:** memory use must be bounded and independent of file size. Target: a 5 GB file scanned with a default heap of 512 MB or less. The only unbounded structure is the result list, which is capped (5.5).
- **Throughput target:** on a typical SSD workstation, a literal/field scan of a 5 GB file in under 60 s; regex scans are slower, bounded by regex complexity. Parallelism: split the file into byte-range chunks aligned to entry boundaries and scan on all cores.
- **Responsiveness:** the GUI thread never blocks; first results appear within 1 s when matches are early in the file.
- **Platform:** Java 21 target (JDK 25 available locally), Windows primary; should work on macOS/Linux.
- **Packaging:** a single runnable (shaded) jar serving both CLI and GUI; no installer, no native executable. The JavaFX launch needs a main class that does not extend `Application`, and the jar must bundle the JavaFX platform classifiers it supports (Windows required). Built with the Maven wrapper.

## 7. Edge cases and handling

| # | Case | Expected behavior |
|---|---|---|
| 1 | Chunk boundary falls mid-entry (parallel scan) | The chunk start is moved forward to the next header line; the previous chunk reads past its end until the next header. No entry is lost or duplicated. |
| 2 | Chunk boundary lands mid-UTF-8 sequence | Scan on bytes and decode per entry, so multi-byte characters are never split. |
| 3 | Leading lines before the first header (e.g. truncated file start) | Reported as an entry with no header (no fields, `level` unknown); matchable by text only. Not silently dropped. |
| 4 | Header-like text inside a message body (e.g. a stack-trace line that looks like a header) | A header must match the full strict pattern, including the timestamp. A false positive splits an entry; documented as a known limitation. |
| 5 | MDC with a varying number of fields, missing fields, or values containing `:` or `,` | Parse leniently: first two tokens positional ids when they contain no space and no `: `; remaining tokens split on `, ` into `key: value`. An unparseable MDC leaves the entry matchable by text only. Entries lacking a filtered field never match that filter. |
| 6 | Extremely large single entries (e.g. a multi-MB response body or a huge stack trace) | The entry is held in memory for matching and display, with a per-entry limit (default 16 MB). Over the limit, the entry is truncated for display and flagged `[truncated]`, and the match is evaluated on the truncated text. |
| 7 | Very long lines (minified JSON, no newline for MBs) | Line reading uses a bounded buffer. A line over the entry limit is handled as in #6, not an `OutOfMemoryError`. |
| 8 | JSONC: malformed line, blank line, truncated last line | Skipped with a warning count, shown at the end ("N malformed lines skipped"). Never aborts the scan. `--strict` aborts instead. |
| 9 | JSONC: nested JSON in `message`, escaped `\n`, `\"`, unicode escapes | Text search runs on the decoded `message` value, plus the raw line when scope is All. Documented so users know `\\n` vs newline behavior. |
| 10 | Line endings: `\n`, `\r\n`, mixed | Normalized for matching. Copy preserves the original text. |
| 11 | Encoding | Assume UTF-8; invalid bytes are replaced with U+FFFD, not fatal. BOM stripped. `--encoding` override. |
| 12 | Empty file, a file with no valid entries, or a missing/unreadable file | Clear message and exit code 2 (CLI), or a dialog (GUI). Not a stack trace. |
| 13 | File is locked, or grows while open (contrary to the assumption) | Assumption is that files never change. If the size changes mid-scan, scan to the originally observed length and warn. |
| 14 | Regex with catastrophic backtracking | Per-entry match timeout. On timeout, the entry is counted as `skipped (regex timeout)` and the scan continues; the count is reported. |
| 15 | Regex or text spanning request and response sections | Scope All matches across the whole entry. Request/Response scopes match only inside their section. |
| 16 | Entry with a `-- REQUEST --` but no `-- RESPONSE --` (or the reverse), or markers repeated | Missing section is empty. For repeated markers, the first of each starts its section; documented. |
| 17 | Timestamps without a timezone (plain) vs. UTC `Z` (JSONC) | Plain timestamps are interpreted as Asia/Jakarta (configurable via `--zone`); JSONC `Z` timestamps are UTC. The range filter states which zone it uses. |
| 18 | Case sensitivity and numeric fields (`shiftId: 147581` vs `"147581"`) | Field values compare as strings after trimming; no numeric coercion. `007` does not equal `7`. |
| 19 | Search while a previous scan runs | The previous scan is cancelled first. The UI never mixes results from two queries. |
| 20 | Opening multiple files | CLI accepts many files (like grep, prefixing the filename when more than one). The GUI opens one file per tab. |
| 21 | Huge result sets in the GUI | Capped (5.5). Result-list rows are lightweight records (byte offset + length + summary). Full entry text is re-read from the file on demand. |
| 22 | Clipboard with very large selections or non-BMP characters | Size guard (5.6). Text goes via the system clipboard as Unicode. |
| 23 | Sensitive data (the sample contains emails, phone numbers, addresses) | The tool does not redact. It performs no network access and no telemetry. Noted as a security property. |

## 8. Acceptance criteria

**Open and detect**
- AC1. Given `log-plain.log`, the tool detects the plain format; given `log-jsonc.log`, JSON. A file that is neither fails with exit code 2 and a clear message.

**Field search**
- AC2. `--field merchantId=786` returns exactly the entries whose MDC has `merchantId: 786`, each returned with all of its continuation lines, in file order. Verified against a hand-counted fixture.
- AC3. `--field merchantId=786 --field shiftId=147581` returns only entries that match both. Two values for the same key (`--field shiftId=1 --field shiftId=2`) return entries matching either.
- AC4. A filter on a field that an entry lacks never returns that entry.
- AC5. A `requestId` search (plain) or `traceId` search (JSONC) returns every entry carrying that id, including continuation lines. A `correlationId` search does the same for the first MDC token.
- AC5a. In JSONC, `--field shiftId=4891` returns exactly the lines having that top-level key and value. Lines without the key are never returned.

**Text and regex**
- AC6. `--text "orderId=2837537" --in request` returns only entries whose request section contains the text. With `--in response`, only response matches. The same text appearing only in the opposite section does not match.
- AC7. `--regex` with an invalid pattern exits with code 2 and prints the error before any scan starts.
- AC8. A regex that matches across lines inside one entry returns that entry; the same regex never matches across two entries.
- AC9. Case-insensitive is the default and `--case-sensitive` flips it; the GUI toggle behaves identically.
- AC10. A catastrophic regex does not hang: scan cancel responds within 2 s, and a per-entry timeout is reported in the summary.

**Scale**
- AC11. On a generated 5 GB plain log, a field search completes without `OutOfMemoryError` at `-Xmx512m`, and peak heap stays flat regardless of file size.
- AC12. Parallel chunked scanning returns results identical (same entries, same order) to a single-threaded scan on the same file, including chunk boundaries that fall mid-entry (tested by varying chunk size down to a few bytes).
- AC13. In the GUI, the first match appears before the scan finishes, a progress indicator shows the percent of bytes scanned, and Cancel stops the scan within 2 s.
- AC14. When the result cap is reached, the scan stops and the UI/CLI states the results are capped.

**Read-only**
- AC15. A test run with the log file marked read-only at the OS level succeeds. A directory-listing diff before and after any search shows no new files in the log's directory, and the file's size, mtime and hash are unchanged.

**CLI**
- AC16. `slp` with no matches exits 1. With matches, it exits 0. On errors, it exits 2. `--count` prints only the number of matching entries.
- AC17. Output piped to a file is byte-identical to the original entry text (CRLF/LF preserved) and contains no ANSI codes when not a TTY or when `--no-color` is passed.
- AC18. `--json` emits one valid JSON object per hit, with `offset`, `line`, and `entry` fields.

**GUI and clipboard**
- AC19. In the GUI the user can open a file, run a field search, a text search and a regex search, view highlighted matches and select an entry.
- AC20. Ctrl+C on a selected entry (or "Copy entry") puts its exact text on the system clipboard. Pasting into Notepad preserves line breaks and characters, including non-ASCII. Multi-select copies all selected entries in file order, separated by a blank line.
- AC21. "Copy all results" over the size threshold asks for confirmation instead of silently freezing.

**Robustness**
- AC22. In JSONC, malformed lines are skipped and counted, and the scan completes. Entries before and after them are returned.
- AC23. Entries before the first valid header (plain) are surfaced, not dropped.
- AC24. Invalid UTF-8 bytes do not abort the scan.

## 9. Non-goals

- Editing, annotating, deleting, rotating, or otherwise writing to logs.
- Persistent or on-disk indexes, and any database. (Revisit if streaming scan proves too slow; see Risks.)
- Live tailing and watching files for changes.
- Remote files, network logs, or log shipping; compressed inputs (`.gz`, `.zip`) in v1.
- Formats other than the two listed (e.g. logfmt, syslog, Logback JSON variants). The parser is structured so adding one is possible, but not built.
- Arbitrary boolean query language (OR across fields, NOT, parentheses) and saved queries.
- Cross-entry request stitching/grouping by id, timelines, charts, aggregations, or alerting.
- Redaction or masking of sensitive data.
- Mobile or web UI.
- Multi-user features, authentication, telemetry.

## 10. Resolved questions

1. **Plain id mapping:** first MDC token = `correlationId`, second = `requestId`. An entry with no requestId/correlationId/traceId is standalone.
2. **JSONC fields:** any top-level key is a searchable field.
3. **Plain timezone:** Asia/Jakarta.
4. **Request identity:** same `requestId` in plain logs, same `traceId` in JSONC. JSONC has no request/response sections, so that scope is plain-only (derived by me; see 4.2).
5. **Packaging:** a jar file is enough; no installer.

## 11. Risks

- A full scan per query may feel slow on multi-GB files. Mitigation: parallel scan, early results, narrowing by time range. If this proves unacceptable, revisit the indexing decision. A cache outside the log folder would still honor the read-only constraint.
- Heuristic MDC parsing may mis-split unusual values. Mitigation: a fixture suite built from real samples, and text search always works as a fallback.
- JavaFX dependency/packaging friction with a JDK 25 / Java 21 target. Mitigation: verify the toolchain early with a spike.
