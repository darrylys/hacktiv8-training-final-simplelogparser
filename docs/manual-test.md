# Manual GUI checks

These complement the automated tests (TRD 9.1). Run them on Windows before a release. Use `src\test\resources\fixtures\plain\crlf.log` (CRLF), `plain\edge.log` (non-ASCII), or a real log you're allowed to use.

Build first, then start the GUI:

```powershell
$env:JAVA_HOME='C:\programs\jdk-25.0.2'; .\mvnw.cmd package
java -jar target\simplelogparser-1.0-SNAPSHOT.jar
```

| # | Check | Expected |
|---|---|---|
| 1 | Drag `crlf.log` from Explorer onto the window. | A tab named `crlf.log` opens. Its tooltip shows the full path and `(plain)`. |
| 2 | File > Open, pick `json/basic.log`. | A second tab opens. The Request/Response scope buttons are disabled, with a note. |
| 3 | Close the app, start it again, open File > Open Recent. | Both files are listed, newest first. |
| 4 | Open a file that isn't a log (e.g. `plain/neither.txt`) and a file that doesn't exist (via Recent after renaming it). | A dialog with a plain message and no stack trace. No tab opens. |
| 5 | In `crlf.log`, field `merchantId` = `786`, Search. | 3 hits. The status bar shows "3 hits" and the elapsed time. |
| 6 | Select the first hit. | The detail pane shows the whole entry, with `786` highlighted in yellow. |
| 7 | Ctrl+C with that hit selected, then paste into Notepad. | Same text and line breaks as in the file. The REQUEST/RESPONSE lines keep their own lines. |
| 8 | Select hits 1 and 3 (Ctrl+click), Ctrl+C, paste into Notepad. | Both entries in file order, separated by one blank line. |
| 9 | In `edge.log`, search text `Zürich`, select the hit, Ctrl+C, paste into Notepad. | `Zürich`, `café` and `日本語` are pasted correctly. |
| 10 | In the detail pane, select part of a line with the mouse, Ctrl+C, paste. | Exactly the selected text. |
| 11 | Text `orderId=2837537`, scope Request, Search; then scope Response. | Request: line 1 entry only. Response: line 11 entry only. Both highlighted. |
| 12 | Regex on, text `status":(5`. | A red "Invalid regex ... position" message, and Search is disabled. |
| 13 | On a large file (e.g. one written by `LogGenerator`, several GB), search for everything. | Hits appear within a second. The list stops at 10,000 with the "Results capped" banner. The window stays responsive while you scroll. |
| 14 | Start a slow regex on a large file, press Cancel (or Esc). | It stops within 2 seconds and the status shows "cancelled". |
| 15 | Start a search, and before it finishes change the query and search again. | Only the second query's results are listed. |
| 16 | Edit > Copy All Results on a result set over 50 MB. | A confirmation dialog appears first. Cancel copies nothing. |
| 17 | After any searches, look in the log file's folder. | No new files. The log's modified time is unchanged. |
