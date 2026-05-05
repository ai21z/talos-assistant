package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileBundleCheckpointStoreTest {

    @Test
    void capturesExistingFileAndRestoresOriginalBytes(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("index.html"), "original");

        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "changed")),
                "trc-test",
                7);

        assertTrue(capture.success(), capture.message());
        assertFalse(capture.checkpointId().isBlank());

        Files.writeString(workspace.resolve("index.html"), "changed");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertEquals("original", Files.readString(workspace.resolve("index.html")));
        assertEquals(1, restore.restoredFiles());
    }

    @Test
    void recordsAbsentFileAndDeletesItOnRestore(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);

        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall("talos.write_file", Map.of("path", "scripts.js", "content", "new")),
                "trc-test",
                1);

        assertTrue(capture.success(), capture.message());

        Files.writeString(workspace.resolve("scripts.js"), "new");
        assertTrue(Files.exists(workspace.resolve("scripts.js")));

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertFalse(Files.exists(workspace.resolve("scripts.js")),
                "restore should remove files that did not exist before the checkpoint");
    }

    @Test
    void rejectsWorkspaceEscapeBeforeCapture(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);

        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall("talos.write_file", Map.of("path", "../escape.txt", "content", "x")),
                "trc-test",
                1);

        assertFalse(capture.success());
        assertTrue(capture.message().contains("workspace"), capture.message());
    }

    @Test
    void capturesBundleBeforeOperationAndRestoresSourceDestinationDeletedAndAbsentPaths(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        Files.writeString(workspace.resolve("dest.txt"), "dest-before");
        Files.writeString(workspace.resolve("delete.txt"), "delete-before");

        WorkspaceOperationPlan plan = WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.BATCH_APPLY,
                java.util.List.of(
                        WorkspaceOperationPlan.PathEffect.source("source.txt", true),
                        WorkspaceOperationPlan.PathEffect.destination("dest.txt", true),
                        WorkspaceOperationPlan.PathEffect.deleted("delete.txt", true),
                        WorkspaceOperationPlan.PathEffect.absentBefore("new.txt", true)),
                dev.talos.tools.ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.OVERWRITE,
                false,
                "Apply bundle",
                "bundle preview");

        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));

        CheckpointCaptureResult capture = service.captureBeforeOperation(
                workspace, config(true), plan, "trc-bundle", 3);

        assertTrue(capture.success(), capture.message());
        assertEquals(4, capture.capturedFiles());

        Files.delete(workspace.resolve("source.txt"));
        Files.writeString(workspace.resolve("dest.txt"), "dest-after");
        Files.delete(workspace.resolve("delete.txt"));
        Files.writeString(workspace.resolve("new.txt"), "new-after");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertEquals("source-before", Files.readString(workspace.resolve("source.txt")));
        assertEquals("dest-before", Files.readString(workspace.resolve("dest.txt")));
        assertEquals("delete-before", Files.readString(workspace.resolve("delete.txt")));
        assertFalse(Files.exists(workspace.resolve("new.txt")),
                "restore should delete paths that were absent before the bundle checkpoint");
    }

    private static Config config(boolean enabled) {
        Config config = new Config();
        config.data.put("checkpoint", Map.of(
                "enabled", enabled,
                "fail_closed", true,
                "max_file_bytes", 1_000_000,
                "max_turn_bytes", 2_000_000));
        return config;
    }
}
