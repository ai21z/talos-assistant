package dev.talos.runtime.policy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RedactedAuditSnapshotWriterTest {

    @Test
    void writesCanaryCleanSnapshotWithProtectedFilesOmitted(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("README.md"), "safe project summary\n");
        Files.writeString(workspace.resolve("canary.txt"), "FILE_DISCOVERED_CANARY_SAFE_TEXT\n");
        Files.writeString(workspace.resolve("notes.md"), """
                PROJECT_CODENAME=Aster-7
                PRIVATE_MARKER=FILE_DISCOVERED_CANARY_SHOULD_NOT_SURVIVE
                """);
        Files.writeString(workspace.resolve(".env"), "TALOS_FAKE_SECRET=sk-test-DO-NOT-LEAK\n");
        Path protectedDir = Files.createDirectories(workspace.resolve("protected"));
        Files.writeString(protectedDir.resolve("private-notes.md"),
                "PROTECTED_PRIVATE_MARKER = FILE_DISCOVERED_CANARY_PRIVATE\n");
        Files.write(workspace.resolve("report.docx"), new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00});

        Path output = tempDir.resolve("snapshot");
        RedactedAuditSnapshotWriter.Summary summary = RedactedAuditSnapshotWriter.write(
                new RedactedAuditSnapshotWriter.Options(workspace, output, "final"));

        assertEquals("final", summary.label());
        assertTrue(summary.safeTextFiles() >= 1, "safe text files should be included");
        assertTrue(summary.omittedFiles() >= 2, "protected/binary files should be omitted");

        String tree = Files.readString(output.resolve("tree.txt"));
        assertTrue(tree.contains("README.md"), tree);
        assertTrue(tree.contains(".env [omitted: protected]"), tree);
        assertTrue(tree.contains("protected/private-notes.md [omitted: protected]"), tree);
        assertTrue(tree.contains("report.docx [omitted: unsupported-or-binary]"), tree);

        String dump = Files.readString(output.resolve("content-dump.txt"));
        assertTrue(dump.contains("safe project summary"), dump);
        assertTrue(dump.contains("[redacted-canary]"), dump);
        assertTrue(dump.contains("PRIVATE_MARKER=[redacted]"), dump);
        assertFalse(dump.contains("FILE_DISCOVERED_CANARY_SHOULD_NOT_SURVIVE"), dump);
        assertFalse(dump.contains("sk-test-DO-NOT-LEAK"), dump);
        assertFalse(dump.contains("FILE_DISCOVERED_CANARY_PRIVATE"), dump);

        assertTrue(ArtifactCanaryScanner.scanRuntimeArtifacts(List.of(output), List.of()).isEmpty());
    }

    @Test
    void cliRejectsMissingWorkspaceArgument(@TempDir Path tempDir) {
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = RedactedAuditSnapshotCli.run(
                List.of("--output", tempDir.resolve("out").toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(64, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("--workspace requires a value")
                        || err.toString(StandardCharsets.UTF_8).contains("--workspace is required"),
                err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void cliRejectsOutputInsideWorkspace(@TempDir Path tempDir) throws Exception {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        Files.writeString(workspace.resolve("README.md"), "safe\n");
        Path outputInsideWorkspace = workspace.resolve("audit-output");
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int code = RedactedAuditSnapshotCli.run(
                List.of(
                        "--workspace", workspace.toString(),
                        "--output", outputInsideWorkspace.toString()),
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8));

        assertEquals(1, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("output directory must not be inside workspace"),
                err.toString(StandardCharsets.UTF_8));
    }
}
