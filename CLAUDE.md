# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

`simplelogparser` (`id.project.logs:simplelogparser`) implements `docs/trd.md` steps 1–7: core, CLI and JavaFX GUI. Running the jar with no arguments, or with `--gui [files]`, opens the GUI; any other arguments run the CLI. Specs: `docs/prd.md` (requirements, edge cases, ACs), `docs/trd.md` (design, assumptions A1–A27), `docs/trd-rejected.md` (alternatives not to use). Compiler `release` is 21 (the local JDK is 25).

Packages under `id.project.logs.simplelogparser`: `io` (read-only `LogFile`, `ByteLineReader`), `model`, `format` (+`plain`, `json`), `query`, `scan` (`ScanEngine`, chunk ownership rule in `ChunkWorker`, ordered merge), `settings`, `cli` (picocli), `gui` (JavaFX 21 + RichTextFX). Core packages must not depend on `cli`/`gui`/picocli/JavaFX, and `cli` and `gui` must not depend on each other; `ArchitectureTest` enforces this and the read-only rules. In `gui`, `SearchViewModel`, `ScanController` and `ClipboardService` hold the behavior and need no running FX toolkit (they take a `UiThread`); the views (`MainWindow`, `FileTab`, `SearchPane`, `ResultListView`, `DetailPane`) only bind to them.

## Build

Use the Maven wrapper (Maven 3.9.4); `mvn` is not on PATH. The wrapper requires `JAVA_HOME`, which is not set globally on this machine — set it per command (JDK lives at `C:\programs\jdk-25.0.2`):

```powershell
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd compile
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd test
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd test -Dtest=ClassName#methodName
```

From Git Bash use `./mvnw` with `JAVA_HOME=/c/programs/jdk-25.0.2`. JDK 25 prints `sun.misc.Unsafe` / `System::load` warnings from Maven's own libraries; they are harmless.

Tests: JUnit 5 + AssertJ + ArchUnit. Surefire runs `*Test` and `*IT` classes in-process; tags `perf` and `gui` are excluded by default. `-Pperf` runs only `ScalePerfTest` with `-Xmx512m` (generates 1 GB and 5 GB files in the system temp dir; takes several minutes). `-Pgui` runs only the TestFX smoke tests (`@Tag("gui")`), headless through Monocle; add `-Dheadless=false -Dglass.platform=win` to watch them on the desktop. `.\mvnw.cmd package` builds the shaded jar `target\simplelogparser-1.0-SNAPSHOT.jar` (`java -jar ... --help`, or no arguments for the GUI); `.\mvnw.cmd javafx:run` starts the GUI during development; `src/dist/slp(.cmd)` are wrapper scripts. The jar bundles JavaFX natives for `win`, `linux` and `mac` (x64) only: `mac-aarch64` natives have the same file names as the x64 ones and cannot share the jar (TRD 2.3). Manual GUI checks are in `docs/manual-test.md`.

Test notes:
- Fixtures in `src/test/resources/fixtures/` depend on exact bytes (trailing spaces after `Filter - `, CRLF, BOM, invalid UTF-8, a JSON line without trailing newline, JSON backslash-u escapes such as the one for é). Editing tools may strip trailing whitespace or decode escapes, so check bytes after editing them (`.gitattributes` marks them `-text`). Expected values in `CliSearchIT` are first-line numbers of the fixture entries.
- `ScanEquivalenceTest` compares the engine with `ReferenceScanner` (test oracle) for chunk sizes down to 1 byte; run it after touching framing, alignment or the prefilter.
- JDK 25's regex engine handles `(a+)+$` quickly, so regex-guard tests use `(.*a){12}x` against 80 `a`s.

## Input log formats

`sample-logs/` holds example inputs. It is listed in `.gitignore`, so don't rely on it existing in other checkouts.

- `log-plain.log` — Spring Boot text logs. Each entry starts on a line matching
  `[<mdc>] yyyy-MM-dd HH:mm:ss.SSS [<thread>] LEVEL <abbreviated.logger> - <message>`.
  The `<mdc>` bracket is colon-separated (trace/span ids plus fields like `merchantId: 786, outletId: 1665`) and its field count varies. Entries are multi-line: request/response dumps from `GistRequestResponseLoggingFilter` use `-- REQUEST --` / `-- RESPONSE --` sections with `|>` / `|<` line prefixes and inline JSON bodies, and ERROR entries carry stack traces. Any line not matching the header belongs to the previous entry.
- `log-jsonc.log` — one ECS-style JSON object per line with keys `@timestamp`, `log.level`, `process.thread.name`, `log.logger`, `message` (messages may contain escaped newlines).
