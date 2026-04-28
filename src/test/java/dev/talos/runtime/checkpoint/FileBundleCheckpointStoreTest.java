package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
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
