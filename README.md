# simplelogparser

Read-only, grep-style search over very large Spring Boot text logs and ECS JSON-per-line logs. It returns whole entries (header plus continuation lines) matched by MDC/JSON fields, request/response text or regex. Files are only ever opened for reading, and memory use doesn't depend on file size.

The same jar works as a JavaFX GUI and as a grep-style CLI.

## Build

```powershell
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd package
```

This produces `target\simplelogparser-1.0-SNAPSHOT.jar` (Java 21+). Copy `src\dist\slp.cmd` (Windows) or `src/dist/slp` next to the jar to run it as `slp`.

The jar bundles JavaFX for Windows, Linux and macOS on Intel. On Apple Silicon, run it with an x64 JDK.

## GUI

```powershell
java -jar simplelogparser-1.0-SNAPSHOT.jar                     # empty window
java -jar simplelogparser-1.0-SNAPSHOT.jar --gui app.log other.log
```

- Open files with File > Open (Ctrl+O), File > Open Recent, or by dropping them on the window. Each file gets its own tab.
- Fill in field filters (field, exact/contains/regex, comma-separated values), text or regex, scope, levels and a time range, then press Enter or Search. A bad regex or time is shown under the inputs, and Search stays disabled until it's fixed.
- Results stream into the list while the scan runs, with a progress bar and Cancel (or Esc). At most 10,000 results are shown, and a banner says when results were capped.
- Select a result to see the full entry with matches highlighted.
- Ctrl+C copies the selected entries (several are joined in file order with a blank line) or the text selected in the detail pane. Edit > Copy All Results (Ctrl+Shift+C) asks first when the results add up to more than 50 MB.

## CLI

```
slp [options] <file>...
```

| Task | Command |
|---|---|
| All entries of one request | `slp --field requestId=KwWy5HI0QsG_LMp4r9jWcg app.log` |
| Fields AND'd, values OR'd | `slp --field merchantId=786 --field shiftId=147581 --field shiftId=147582 app.log` |
| Contains / regex on a field | `slp --field "merchantId*=78" --field "shiftId~=^1475\d+$" app.log` |
| Text in request bodies only | `slp --text "orderId=2837537" --in request app.log` |
| Regex in responses | `slp --regex "\"status\":\s*5\d\d" --in response app.log` |
| Levels and time range | `slp --level ERROR,WARN --from 2026-10-18T00:00 --to 2026-10-18T06:00 app.log` |
| Count only / first N | `slp --count --field merchantId=786 app.log`, `slp -m 100 ...` |
| JSON output with offsets | `slp --json --field traceId=utFYmqq3SlqzUGeVT12iog app.json` |
| Copy results (Windows) | `slp --field merchantId=786 app.log \| clip` |

Notes:

- Plain logs: the first MDC token is `correlationId` and the second is `requestId`; other MDC entries are `key: value` fields. The built-in fields are `level`, `thread`, `logger` and `timestamp`.
- JSON logs: every top-level key is a field, and `traceId` identifies a request. Nested values can be searched as compact JSON text. `--in request|response` doesn't apply to JSON and falls back to all text, with a warning.
- Text matching is case-insensitive by default (`--case-sensitive` turns that off). Regexes use `java.util.regex` syntax and can span lines within an entry (`--dotall`), but never cross entries. Each entry has a regex timeout (`--regex-timeout`, 1000 ms by default).
- Times without an offset are read in `--zone` (default `Asia/Jakarta`). `--from` is inclusive and `--to` is exclusive.
- Exit status: 0 if an entry matched, 1 if none matched, 2 on error. Notices go to stderr: malformed JSON lines, regex timeouts, a capped result set, or a file that changed during the scan.

Run `slp --help` for all options.
