Create a simple program to parse log files. Sample in folder sample-logs.
The log file has 2 formats:
1. plain text. One request might span multiple lines. sample: log-plain.log.
2. json object per line (no arrays). sample: log-jsonc.log

Goal:
A program where user can open the file and search things in the file such as:
1. all requests with specified requestId or correlationId or particular requestId AND correlationId, merchantId, shiftId, etc.
2. search requests with request and/or response containing some text.
3. support regex search
4. the files can be in gigabytes in size

Constraints:
1. files are read only and won't change.
2. the program only read files, don't update.
3. used as both GUI and CLI. in CLI, it doesn't have to be interactive (think like grep)
4. can copy text to clipboard and paste it outside (like to notepad)

Allowed to use publicly available libraries.
