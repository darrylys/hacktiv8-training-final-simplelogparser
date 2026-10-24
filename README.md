# simplelogparser

Read-only, grep-style search over very large Spring Boot text logs and ECS JSON-per-line logs. It returns whole entries (header plus continuation lines) matched by MDC/JSON fields, request/response text or regex. Files are only ever opened for reading, and memory use doesn't depend on file size.

The CLI is implemented. The JavaFX GUI is planned but not built yet.

## Build

```powershell
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd package
```

This produces `target\simplelogparser-1.0-SNAPSHOT.jar` (Java 21+). Copy `src\dist\slp.cmd` (Windows) or `src/dist/slp` next to the jar to run it as `slp`.

## Usage

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
