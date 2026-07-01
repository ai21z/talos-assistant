package dev.talos.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** T794: restore diffs reuse every write/edit guard and fail closed. */
class ApprovalDiffPreviewForRestoreTest {

    @TempDir Path workspace;

    @Test
    void changedContentDiffsCapturedBlobAgainstCurrentFile() throws Exception {
        Files.writeString(workspace.resolve("app.js"), "current line\n", StandardCharsets.UTF_8);

        ApprovalDiffPreview.Preview preview = ApprovalDiffPreview.forRestore(
                workspace, "app.js", "captured line\n".getBytes(StandardCharsets.UTF_8));

        assertFalse(preview.skipped(), preview.skippedReason());
        assertEquals(1, preview.added());
        assertEquals(1, preview.removed());
        assertTrue(preview.text().contains("-current line"), preview.text());
        assertTrue(preview.text().contains("+captured line"), preview.text());
    }

    @Test
    void identicalContentSaysNoChanges() throws Exception {
        Files.writeString(workspace.resolve("same.txt"), "same\n", StandardCharsets.UTF_8);

        ApprovalDiffPreview.Preview preview = ApprovalDiffPreview.forRestore(
                workspace, "same.txt", "same\n".getBytes(StandardCharsets.UTF_8));

        assertFalse(preview.skipped());
        assertEquals("no changes", preview.note());
    }

    @Test
    void missingCurrentFileNotesRecreation() {
        ApprovalDiffPreview.Preview preview = ApprovalDiffPreview.forRestore(
                workspace, "gone.txt", "back\n".getBytes(StandardCharsets.UTF_8));

        assertFalse(preview.skipped());
        assertEquals("recreates missing file", preview.note());
        assertTrue(preview.text().contains("+back"), preview.text());
    }

    @Test
    void protectedPathsAndBinaryBlobsFailClosed() throws Exception {
        Files.writeString(workspace.resolve(".env"), "API=x", StandardCharsets.UTF_8);
        assertEquals("protected-path", ApprovalDiffPreview.forRestore(
                workspace, ".env", "API=y".getBytes(StandardCharsets.UTF_8)).skippedReason());

        // .talos is a protected CONTROL path since T788.
        assertEquals("protected-path", ApprovalDiffPreview.forRestore(
                workspace, ".talos/profiles.yaml", "x".getBytes(StandardCharsets.UTF_8))
                .skippedReason());

        Files.writeString(workspace.resolve("bin.dat"), "x", StandardCharsets.UTF_8);
        assertEquals("binary-content", ApprovalDiffPreview.forRestore(
                workspace, "bin.dat", new byte[]{1, 0, 2}).skippedReason());

        assertEquals("missing-blob", ApprovalDiffPreview.forRestore(
                workspace, "bin.dat", null).skippedReason());
    }
}
