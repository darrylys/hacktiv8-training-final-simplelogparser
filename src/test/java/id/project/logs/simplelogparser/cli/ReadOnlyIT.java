package id.project.logs.simplelogparser.cli;

import id.project.logs.simplelogparser.Fixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.DosFileAttributeView;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static id.project.logs.simplelogparser.cli.CliHarness.run;
import static org.assertj.core.api.Assertions.assertThat;

/** AC15: searching an OS-level read-only file succeeds and leaves the file and its directory unchanged. */
class ReadOnlyIT {

    @TempDir
    Path dir;
    private Path log;

    @AfterEach
    void makeWritableAgain() throws Exception {
        if (log != null) {
            setReadOnly(log, false);
        }
    }

    @Test
    void searchesNeverWriteNextToOrIntoTheLog() throws Exception {
        log = dir.resolve("app.log");
        Files.copy(Fixtures.path("plain/basic.log"), log, StandardCopyOption.REPLACE_EXISTING);
        Path json = dir.resolve("app.json");
        Files.copy(Fixtures.path("json/basic.log"), json, StandardCopyOption.REPLACE_EXISTING);
        setReadOnly(log, true);
        setReadOnly(json, true);
        try {
            List<String> before = listing();
            long size = Files.size(log);
            FileTime mtime = Files.getLastModifiedTime(log);
            String hash = sha256(log);

            assertThat(run("--field", "merchantId=786", log.toString()).code()).isZero();
            assertThat(run("--text", "orderId=2837537", "--in", "request", log.toString()).code()).isZero();
            assertThat(run("--regex", "\"status\":\\s*\\d+", log.toString()).code()).isZero();
            assertThat(run("--count", log.toString(), json.toString()).code()).isZero();
            assertThat(run("--json", "--field", "shiftId=4891", json.toString()).code()).isZero();

            assertThat(listing()).isEqualTo(before);
            assertThat(Files.size(log)).isEqualTo(size);
            assertThat(Files.getLastModifiedTime(log)).isEqualTo(mtime);
            assertThat(sha256(log)).isEqualTo(hash);
        } finally {
            setReadOnly(json, false);
        }
    }

    private List<String> listing() throws Exception {
        try (Stream<Path> s = Files.list(dir)) {
            return s.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static String sha256(Path p) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p)));
    }

    private static void setReadOnly(Path p, boolean readOnly) throws Exception {
        DosFileAttributeView dos = Files.getFileAttributeView(p, DosFileAttributeView.class);
        if (dos != null) {
            dos.setReadOnly(readOnly);
            return;
        }
        PosixFileAttributeView posix = Files.getFileAttributeView(p, PosixFileAttributeView.class);
        posix.setPermissions(readOnly
                ? Set.of(PosixFilePermission.OWNER_READ)
                : Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
    }
}
