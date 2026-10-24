# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project state

`simplelogparser` (`id.project.logs:simplelogparser`) is a fresh Maven skeleton: `src/main/java`, `src/main/resources` and `src/test/java` exist but are empty, and `pom.xml` declares no dependencies or plugins. Compiler target is Java 21 (the local JDK is 25).

## Build

Use the Maven wrapper (Maven 3.9.4); `mvn` is not on PATH. The wrapper requires `JAVA_HOME`, which is not set globally on this machine — set it per command (JDK lives at `C:\programs\jdk-25.0.2`):

```powershell
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd compile
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd test
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd test -Dtest=ClassName#methodName
```

From Git Bash use `./mvnw` with `JAVA_HOME=/c/programs/jdk-25.0.2`. JDK 25 prints `sun.misc.Unsafe` / `System::load` warnings from Maven's own libraries; they are harmless.

No test framework is configured yet; add JUnit (and the Surefire plugin if needed) to `pom.xml` before writing tests.

## Input log formats

`sample-logs/` holds example inputs. It is listed in `.gitignore`, so don't rely on it existing in other checkouts.

- `log-plain.log` — Spring Boot text logs. Each entry starts on a line matching
  `[<mdc>] yyyy-MM-dd HH:mm:ss.SSS [<thread>] LEVEL <abbreviated.logger> - <message>`.
  The `<mdc>` bracket is colon-separated (trace/span ids plus fields like `merchantId: 786, outletId: 1665`) and its field count varies. Entries are multi-line: request/response dumps from `GistRequestResponseLoggingFilter` use `-- REQUEST --` / `-- RESPONSE --` sections with `|>` / `|<` line prefixes and inline JSON bodies, and ERROR entries carry stack traces. Any line not matching the header belongs to the previous entry.
- `log-jsonc.log` — one ECS-style JSON object per line with keys `@timestamp`, `log.level`, `process.thread.name`, `log.logger`, `message` (messages may contain escaped newlines).
