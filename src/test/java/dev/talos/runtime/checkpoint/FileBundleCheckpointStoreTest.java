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

    @Test
    void restoreRejectsCorruptBlobBeforeOverwritingWorkspaceFile(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("safe.txt"), "captured-before");
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall("talos.write_file", Map.of("path", "safe.txt", "content", "after")),
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("safe.txt"), "current-must-survive");

        Path manifestFile = manifestFile(checkpointRoot, workspace, capture.checkpointId());
        Map<String, Object> manifest = MAPPER.readValue(
                Files.readString(manifestFile),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files =
                (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
        String blobSha = String.valueOf(files.getFirst().get("blobSha256"));
        Files.writeString(manifestFile.getParent().resolve("blobs").resolve(blobSha), "corrupted-bytes");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertFalse(restore.success(), "corrupt blob must fail restore");
        assertTrue(restore.message().toLowerCase(java.util.Locale.ROOT).contains("integrity")
                        || restore.message().toLowerCase(java.util.Locale.ROOT).contains("sha"),
                restore.message());
        assertEquals("current-must-survive", Files.readString(workspace.resolve("safe.txt")),
                "restore must not write corrupt checkpoint bytes over the current file");
    }

    @Test
    void restorePreflightsCorruptChildBlobBeforeWipingCapturedDirectory(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets"));
        Files.writeString(workspace.resolve("assets/original.txt"), "captured-before");
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeRestore(
                workspace,
                config(true),
                List.of("assets"),
                "directory child corrupt preflight",
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("assets/original.txt"), "current-must-survive");
        Files.writeString(workspace.resolve("assets/added-after.txt"), "must-not-be-deleted-before-preflight");

        Path manifestFile = manifestFile(checkpointRoot, workspace, capture.checkpointId());
        String blobSha = blobShaFor(manifestFile, "assets/original.txt");
        Files.writeString(manifestFile.getParent().resolve("blobs").resolve(blobSha), "corrupted-bytes");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertFalse(restore.success(), "corrupt child blob must fail restore");
        assertEquals(0, restore.restoredFiles(), "preflight failure must not restore entries");
        assertEquals(0, restore.deletedFiles(), "preflight failure must not delete entries");
        assertTrue(restore.message().toLowerCase(java.util.Locale.ROOT).contains("integrity"),
                restore.message());
        assertEquals("current-must-survive", Files.readString(workspace.resolve("assets/original.txt")),
                "restore must not wipe a live directory before child blob integrity is proven");
        assertEquals("must-not-be-deleted-before-preflight",
                Files.readString(workspace.resolve("assets/added-after.txt")),
                "restore must not remove later children before child blob integrity is proven");
    }

    @Test
    void restorePreflightsMissingChildBlobBeforeWipingCapturedDirectory(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets"));
        Files.writeString(workspace.resolve("assets/original.txt"), "captured-before");
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeRestore(
                workspace,
                config(true),
                List.of("assets"),
                "directory child missing preflight",
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("assets/original.txt"), "current-must-survive");
        Files.writeString(workspace.resolve("assets/added-after.txt"), "must-not-be-deleted-before-preflight");

        Path manifestFile = manifestFile(checkpointRoot, workspace, capture.checkpointId());
        String blobSha = blobShaFor(manifestFile, "assets/original.txt");
        Files.delete(manifestFile.getParent().resolve("blobs").resolve(blobSha));

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertFalse(restore.success(), "missing child blob must fail restore");
        assertEquals(0, restore.restoredFiles(), "preflight failure must not restore entries");
        assertEquals(0, restore.deletedFiles(), "preflight failure must not delete entries");
        assertTrue(restore.message().toLowerCase(java.util.Locale.ROOT).contains("missing blob"),
                restore.message());
        assertEquals("current-must-survive", Files.readString(workspace.resolve("assets/original.txt")),
                "restore must not wipe a live directory before child blob presence is proven");
        assertEquals("must-not-be-deleted-before-preflight",
                Files.readString(workspace.resolve("assets/added-after.txt")),
                "restore must not remove later children before child blob presence is proven");
    }

    @Test
    void restoringCapturedDirectoryRemovesLaterChildrenAndKeepsCapturedEmptyDirectories(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets/empty"));
        Files.writeString(workspace.resolve("assets/original.txt"), "captured-before");
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeRestore(
                workspace,
                config(true),
                List.of("assets"),
                "test directory restore",
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("assets/original.txt"), "changed-after");
        Files.writeString(workspace.resolve("assets/added-after.txt"), "must be removed");
        Files.createDirectories(workspace.resolve("assets/new-subdir"));
        Files.writeString(workspace.resolve("assets/new-subdir/later.txt"), "must be removed too");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertEquals("captured-before", Files.readString(workspace.resolve("assets/original.txt")));
        assertFalse(Files.exists(workspace.resolve("assets/added-after.txt")),
                "directory restore must remove children added after capture");
        assertFalse(Files.exists(workspace.resolve("assets/new-subdir")),
                "directory restore must remove nested directories added after capture");
        assertTrue(Files.isDirectory(workspace.resolve("assets/empty")),
                "captured empty directories are part of the directory state");
    }

    @Test
    void restoreSafetyCaptureWithDirectoryAndChildTargetsPreservesCapturedChildren(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets/empty"));
        Files.writeString(workspace.resolve("assets/original.txt"), "captured-before");
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeRestore(
                workspace,
                config(true),
                List.of("assets", "assets/empty", "assets/original.txt"),
                "duplicate directory safety capture",
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("assets/original.txt"), "changed-after");
        Files.writeString(workspace.resolve("assets/added-after.txt"), "must be removed");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertEquals("captured-before", Files.readString(workspace.resolve("assets/original.txt")),
                "duplicate directory and child entries must not lose captured child content");
        assertTrue(Files.isDirectory(workspace.resolve("assets/empty")),
                "duplicate directory and child entries must preserve captured empty directories");
        assertFalse(Files.exists(workspace.resolve("assets/added-after.txt")),
                "duplicate directory and child entries must still remove later-added children");
    }

    @Test
    void checkpointBlobCaptureUsesTempFileAndAtomicPromotion() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/checkpoint/FileBundleCheckpointStore.java"));

        assertTrue(source.contains("Files.createTempFile"),
                "checkpoint blobs must be written to a temp path first");
        assertTrue(source.contains("StandardCopyOption.ATOMIC_MOVE"),
                "checkpoint blobs must request atomic promotion where supported");
        assertTrue(source.contains("Files.move"),
                "checkpoint blobs must be promoted with Files.move rather than plain final-path write");
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

    private static Path manifestFile(Path checkpointRoot, Path workspace, String checkpointId) {
        return checkpointRoot
                .resolve(JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize()))
                .resolve("checkpoints")
                .resolve(checkpointId)
                .resolve("manifest.json");
    }

    private static String blobShaFor(Path manifestFile, String relativePath) throws Exception {
        Map<String, Object> manifest = MAPPER.readValue(
                Files.readString(manifestFile),
                new TypeReference<>() {});
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> files =
                (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
        return files.stream()
                .filter(entry -> relativePath.equals(String.valueOf(entry.getOrDefault("relativePath", ""))))
                .map(entry -> String.valueOf(entry.getOrDefault("blobSha256", "")))
                .filter(sha -> !sha.isBlank())
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing blob entry for " + relativePath));
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
