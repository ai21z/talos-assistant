package dev.talos.runtime.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FileBundleCheckpointStoreTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

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
    void rejectsWindowsPrefixSiblingEscapeBeforeCapture(@TempDir Path temp) throws Exception {
        withOsName("Windows 11", () -> {
            Path workspace = temp.resolve("workspace");
            Files.createDirectories(workspace);

            CheckpointService service = new CheckpointService(
                    new FileBundleCheckpointStore(temp.resolve("checkpoints")));

            CheckpointCaptureResult capture = service.captureBeforeMutation(
                    workspace,
                    config(true),
                    new ToolCall(
                            "talos.write_file",
                            Map.of("path", "../workspace-sibling/escape.txt", "content", "x")),
                    "trc-test",
                    1);

            assertFalse(capture.success(), "prefix sibling target must not be captured: " + capture.message());
            assertTrue(capture.message().contains("workspace"), capture.message());
        });
    }

    @Test
    void restoreRejectsWindowsPrefixSiblingManifestEntryBeforeWriting(@TempDir Path temp) throws Exception {
        withOsName("Windows 11", () -> {
            Path workspace = temp.resolve("workspace");
            Path sibling = temp.resolve("workspace-sibling");
            Files.createDirectories(workspace);
            Files.writeString(workspace.resolve("safe.txt"), "safe-before");

            Path checkpointRoot = temp.resolve("checkpoints");
            CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

            CheckpointCaptureResult capture = service.captureBeforeMutation(
                    workspace,
                    config(true),
                    new ToolCall("talos.write_file", Map.of("path", "safe.txt", "content", "after")),
                    "trc-test",
                    1);
            assertTrue(capture.success(), capture.message());

            Path manifestFile = checkpointRoot
                    .resolve(JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize()))
                    .resolve("checkpoints")
                    .resolve(capture.checkpointId())
                    .resolve("manifest.json");
            Map<String, Object> manifest = MAPPER.readValue(
                    Files.readString(manifestFile),
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
            assertFalse(files.isEmpty(), "checkpoint fixture must contain at least one file entry");
            files.getFirst().put("relativePath", "../workspace-sibling/restored.txt");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);

            CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

            assertFalse(restore.success(), "escaped manifest entry must make restore partial/failure");
            assertFalse(Files.exists(sibling.resolve("restored.txt")),
                    "restore must not write into a prefix-sibling directory");
            assertEquals("safe-before", Files.readString(workspace.resolve("safe.txt")));
        });
    }

    @Test
    void restoreRejectsWindowsPrefixSiblingManifestEntryBeforeDeleting(@TempDir Path temp) throws Exception {
        withOsName("Windows 11", () -> {
            Path workspace = temp.resolve("workspace");
            Path sibling = temp.resolve("workspace-sibling");
            Files.createDirectories(workspace);
            Files.createDirectories(sibling);
            Files.writeString(workspace.resolve("new.txt"), "created-after-checkpoint");
            Files.writeString(sibling.resolve("keep.txt"), "must-stay");

            Path checkpointRoot = temp.resolve("checkpoints");
            CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

            CheckpointCaptureResult capture = service.captureBeforeMutation(
                    workspace,
                    config(true),
                    new ToolCall("talos.write_file", Map.of("path", "new.txt", "content", "after")),
                    "trc-test",
                    1);
            assertTrue(capture.success(), capture.message());

            Path manifestFile = checkpointRoot
                    .resolve(JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize()))
                    .resolve("checkpoints")
                    .resolve(capture.checkpointId())
                    .resolve("manifest.json");
            Map<String, Object> manifest = MAPPER.readValue(
                    Files.readString(manifestFile),
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
            assertFalse(files.isEmpty(), "checkpoint fixture must contain at least one file entry");
            files.getFirst().put("relativePath", "../workspace-sibling/keep.txt");
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(manifestFile.toFile(), manifest);

            CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

            assertFalse(restore.success(), "escaped manifest entry must make restore partial/failure");
            assertEquals("must-stay", Files.readString(sibling.resolve("keep.txt")),
                    "restore must not delete from a prefix-sibling directory");
        });
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

    private static void withOsName(String value, ThrowingRunnable runnable) throws Exception {
        String previous = System.getProperty("os.name");
        System.setProperty("os.name", value);
        try {
            runnable.run();
        } finally {
            if (previous == null) {
                System.clearProperty("os.name");
            } else {
                System.setProperty("os.name", previous);
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
