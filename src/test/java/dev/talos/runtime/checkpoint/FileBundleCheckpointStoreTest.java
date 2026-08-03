package dev.talos.runtime.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.io.AtomicFileWriter;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.UserDefinedFileAttributeView;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

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
    void capturesAndRestoresArbitraryBinaryBytesExactly(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path target = workspace.resolve("payload.bin");
        byte[] captured = {
                0,
                (byte) 0xff,
                (byte) 0xc3,
                0x28,
                '\r',
                '\n',
                (byte) 0xed,
                (byte) 0xa0,
                (byte) 0x80
        };
        Files.write(target, captured);
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "payload.bin", "content", "changed")),
                "trc-binary",
                1);
        assertTrue(capture.success(), capture.message());
        Files.write(target, new byte[] { 1, 2, 3, 4 });

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertArrayEquals(captured, Files.readAllBytes(target));
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
    void linkedDescendantFailsBeforeCheckpointNamespaceOrBlobPublication(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = Files.createDirectories(temp.resolve("workspace/tree"));
        Path external = Files.writeString(temp.resolve("external-canary.txt"),
                "T1170_EXTERNAL_CHECKPOINT_CANARY");
        createSymbolicLinkOrAbort(workspace.resolve("linked.txt"), external);
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeRestore(
                temp.resolve("workspace"),
                config(true),
                List.of("tree"),
                "linked descendant fixture",
                "trace-linked",
                1);

        assertFalse(capture.success(), "linked checkpoint input must fail closed");
        assertTrue(service.listIds(temp.resolve("workspace")).isEmpty(),
                "failed containment must not publish a listable checkpoint");
        assertFalse(Files.exists(checkpointRoot),
                "the checkpoint namespace must not exist before all targets pass preflight");
        assertFalse(capture.message().contains(external.toString()),
                "containment failures must not disclose the external target");
        assertFalse(capture.message().contains("T1170_EXTERNAL_CHECKPOINT_CANARY"));
    }

    @Test
    void linkedMutationTargetFailsBeforeCheckpointNamespacePublication(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path external = Files.writeString(
                temp.resolve("external-mutation-canary.txt"),
                "T1170_EXTERNAL_MUTATION_CHECKPOINT_CANARY");
        Path linkedTarget = workspace.resolve("linked.txt");
        createSymbolicLinkOrAbort(linkedTarget, external);
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "linked.txt", "content", "replacement")),
                "trace-linked-mutation",
                1);

        assertFalse(capture.success());
        assertTrue(capture.message().contains("LINKED_COMPONENT"), capture.message());
        assertFalse(capture.message().contains(external.toString()));
        assertFalse(capture.message().contains(
                "T1170_EXTERNAL_MUTATION_CHECKPOINT_CANARY"));
        assertFalse(Files.exists(checkpointRoot));
    }

    @Test
    void linkedDirectoryMutationTargetFailsThroughTheSamePolicy(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path external = Files.createDirectory(temp.resolve("external-directory"));
        Files.writeString(
                external.resolve("canary.txt"),
                "T1170_EXTERNAL_DIRECTORY_CHECKPOINT_CANARY");
        createSymbolicLinkOrAbort(workspace.resolve("linked-directory"), external);
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "linked-directory", "content", "replacement")),
                "trace-linked-directory",
                1);

        assertFalse(capture.success());
        assertTrue(capture.message().contains("LINKED_COMPONENT"), capture.message());
        assertFalse(capture.message().contains(external.toString()));
        assertFalse(Files.exists(checkpointRoot));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionFailsBeforeCheckpointNamespacePublication(
            @TempDir Path temp
    ) throws Exception {
        Path tree = Files.createDirectories(temp.resolve("workspace/tree"));
        Path external = Files.createDirectory(temp.resolve("external-junction-target"));
        Files.writeString(external.resolve("canary.txt"), "T1170_WINDOWS_CHECKPOINT_CANARY");
        Path junction = tree.resolve("junction");
        Process process = new ProcessBuilder(
                "cmd.exe", "/d", "/c", "mklink", "/J",
                junction.toString(), external.toString())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);

        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot));
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                temp.resolve("workspace"),
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "tree/junction", "content", "replacement")),
                "trace-junction",
                1);

        assertFalse(capture.success());
        assertTrue(service.listIds(temp.resolve("workspace")).isEmpty());
        assertFalse(Files.exists(checkpointRoot));
        assertFalse(capture.message().contains("T1170_WINDOWS_CHECKPOINT_CANARY"));
        assertFalse(capture.message().contains(external.toString()));
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

        String authorityPlanId = "a".repeat(64);
        String operationId = "b".repeat(64);
        WorkspaceOperationPlan plan = new WorkspaceOperationPlan(
                authorityPlanId,
                operationId,
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
        assertEquals(authorityPlanId, capture.authorityPlanId());
        assertEquals(operationId, capture.operationId());
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

    /**
     * Restore atomicity: a directory restore must never strip the live tree
     * before the replacement content is safely in place. A write failure
     * mid-restore may leave a mix of live and checkpoint content, but it
     * must not LOSE anything - no file deleted, no extra removed.
     */
    @Test
    void directoryRestoreWriteFailureLeavesLiveFilesAndExtrasIntact(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets"));
        Files.writeString(workspace.resolve("assets/a.txt"), "a-before");
        Files.writeString(workspace.resolve("assets/b.txt"), "b-before");

        java.util.concurrent.atomic.AtomicInteger writes = new java.util.concurrent.atomic.AtomicInteger();
        FileBundleCheckpointStore store = new FileBundleCheckpointStore(
                temp.resolve("checkpoints"),
                (target, bytes) -> {
                    if (writes.incrementAndGet() >= 2) {
                        throw new java.io.IOException("injected write failure");
                    }
                    FileBundleCheckpointStore.replaceFileAtomically(target, bytes);
                });
        CheckpointService service = new CheckpointService(store);

        // ghost.txt is captured absent-before and listed FIRST, so a restore
        // that deletes in manifest order would remove it before any write.
        CheckpointCaptureResult capture = service.captureBeforeRestore(
                workspace,
                config(true),
                List.of("ghost.txt", "assets"),
                "fault injection",
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("assets/a.txt"), "a-current");
        Files.writeString(workspace.resolve("assets/b.txt"), "b-current");
        Files.writeString(workspace.resolve("assets/added-after.txt"), "extra-must-survive-failure");
        Files.writeString(workspace.resolve("ghost.txt"), "created-after-capture");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertFalse(restore.success(), "injected write failure must not report success");
        assertTrue(Files.exists(workspace.resolve("assets/a.txt")),
                "no live file may be lost to a mid-restore failure");
        assertEquals("b-current", Files.readString(workspace.resolve("assets/b.txt")),
                "the file whose write failed must keep its live content");
        assertEquals("extra-must-survive-failure",
                Files.readString(workspace.resolve("assets/added-after.txt")),
                "extras must not be deleted before all replacement content is in place");
        assertEquals("created-after-capture", Files.readString(workspace.resolve("ghost.txt")),
                "recorded-absent targets must not be deleted before all writes succeed");
    }

    /**
     * Ordering contract: extras (children added after capture) and
     * recorded-absent targets are deleted only AFTER every replacement
     * write has succeeded - observed from inside the writer itself.
     */
    @Test
    void extrasAreDeletedOnlyAfterAllReplacementWritesSucceed(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("assets"));
        Files.writeString(workspace.resolve("assets/a.txt"), "a-before");
        Files.writeString(workspace.resolve("assets/b.txt"), "b-before");
        Path extra = workspace.resolve("assets/added-after.txt");
        Path ghost = workspace.resolve("ghost.txt");

        List<Boolean> extraPresentAtWrite = new java.util.ArrayList<>();
        List<Boolean> ghostPresentAtWrite = new java.util.ArrayList<>();
        FileBundleCheckpointStore store = new FileBundleCheckpointStore(
                temp.resolve("checkpoints"),
                (target, bytes) -> {
                    extraPresentAtWrite.add(Files.exists(extra));
                    ghostPresentAtWrite.add(Files.exists(ghost));
                    FileBundleCheckpointStore.replaceFileAtomically(target, bytes);
                });
        CheckpointService service = new CheckpointService(store);

        CheckpointCaptureResult capture = service.captureBeforeRestore(
                workspace,
                config(true),
                List.of("ghost.txt", "assets"),
                "ordering contract",
                "trc-test",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(workspace.resolve("assets/a.txt"), "a-current");
        Files.writeString(workspace.resolve("assets/b.txt"), "b-current");
        Files.writeString(extra, "extra");
        Files.writeString(ghost, "created-after-capture");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertEquals(2, extraPresentAtWrite.size(), "both captured files must be written");
        assertTrue(extraPresentAtWrite.stream().allMatch(Boolean::booleanValue),
                "extras must still exist while replacement writes are running");
        assertTrue(ghostPresentAtWrite.stream().allMatch(Boolean::booleanValue),
                "recorded-absent targets must still exist while replacement writes are running");
        assertFalse(Files.exists(extra), "extras are deleted after the writes succeed");
        assertFalse(Files.exists(ghost), "recorded-absent targets are deleted after the writes succeed");
        assertEquals("a-before", Files.readString(workspace.resolve("assets/a.txt")));
        assertEquals("b-before", Files.readString(workspace.resolve("assets/b.txt")));
    }

    @Test
    void checkpointSingleFileWritesUseTheSharedFailClosedAtomicOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/checkpoint/FileBundleCheckpointStore.java"));

        assertTrue(source.contains("AtomicFileWriter"),
                "checkpoint restore and blob publication must use the shared single-file owner");
        assertFalse(source.contains("AtomicMoveNotSupportedException"),
                "checkpoint code must not retain a plain-move fallback");
        assertFalse(source.contains("StandardCopyOption.REPLACE_EXISTING"),
                "checkpoint code must not own a private non-atomic replacement path");
        assertFalse(source.contains("Files.write("),
                "checkpoint primary bytes must not bypass the governed owner");
        assertFalse(source.contains("Files.writeString("),
                "checkpoint primary text must not bypass the governed owner");
    }

    @Test
    void injectedSharedOwnerObservesBothBlobPublicationAndDefaultRestore(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path target = workspace.resolve("safe.bin");
        byte[] captured = { 0, (byte) 0xff, 1, 2 };
        Files.write(target, captured);
        List<Path> governedTargets = new java.util.ArrayList<>();
        AtomicFileWriter observingWriter = new AtomicFileWriter(
                (stage, observedTarget, ignoredTemp) -> {
                    if (stage == AtomicFileWriter.FaultStage.AFTER_TEMP_CREATE) {
                        governedTargets.add(observedTarget);
                    }
                });
        Path checkpointRoot = temp.resolve("checkpoints");
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(checkpointRoot, observingWriter));

        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "safe.bin", "content", "changed")),
                "trc-owner",
                1);
        assertTrue(capture.success(), capture.message());
        Files.write(target, new byte[] { 9, 8, 7, 6 });
        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertArrayEquals(captured, Files.readAllBytes(target));
        assertTrue(governedTargets.stream().anyMatch(path ->
                        path.startsWith(checkpointRoot) && path.getParent().endsWith("blobs")),
                "checkpoint blob publication must traverse the injected atomic owner");
        assertTrue(governedTargets.contains(target.toAbsolutePath().normalize()),
                "default checkpoint restore must traverse the same injected atomic owner");
    }

    @Test
    void checkpointRestorePreservesCurrentUserDefinedAttributeBytes(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path target = workspace.resolve("safe.bin");
        byte[] captured = { 0, (byte) 0xff, 1, 2 };
        Files.write(target, captured);
        UserDefinedFileAttributeView view = userDefinedView(target);
        assumeTrue(view != null, "user-defined attributes are unsupported");
        byte[] binary = { 5, 0, (byte) 0xff, 6 };
        assertEquals(binary.length, view.write(
                "talos_checkpoint_metadata_probe",
                ByteBuffer.wrap(binary)));
        assertEquals(0, view.write(
                "talos_checkpoint_empty_probe",
                ByteBuffer.allocate(0)));
        CheckpointService service = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "safe.bin", "content", "changed")),
                "trc-metadata",
                1);
        assertTrue(capture.success(), capture.message());
        Files.write(target, new byte[] { 9, 8, 7, 6 });

        CheckpointRestoreResult restore = service.restore(
                workspace,
                capture.checkpointId());

        assertTrue(restore.success(), restore.message());
        assertArrayEquals(captured, Files.readAllBytes(target));
        assertArrayEquals(
                binary,
                readUserDefinedAttribute(
                        userDefinedView(target),
                        "talos_checkpoint_metadata_probe"));
        assertArrayEquals(
                new byte[0],
                readUserDefinedAttribute(
                        userDefinedView(target),
                        "talos_checkpoint_empty_probe"));
        assertNoAtomicTemps(workspace);
    }

    @Test
    void sharedAtomicWriterPrepromotionFaultKeepsRestoreTargetExact(@TempDir Path temp)
            throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path target = workspace.resolve("safe.bin");
        byte[] captured = { 0, (byte) 0xff, 1, 2 };
        byte[] current = { 9, 8, 7, 6 };
        Files.write(target, captured);
        AtomicFileWriter failingWriter = new AtomicFileWriter(
                (stage, ignoredTarget, ignoredTemp) -> {
                    if (stage == AtomicFileWriter.FaultStage.BEFORE_PROMOTION) {
                        throw new java.io.IOException("PRIVATE_CHECKPOINT_CANARY");
                    }
                });
        FileBundleCheckpointStore store = new FileBundleCheckpointStore(
                temp.resolve("checkpoints"),
                failingWriter::replaceCurrent);
        CheckpointService service = new CheckpointService(store);
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "safe.bin", "content", "changed")),
                "trc-fault",
                1);
        assertTrue(capture.success(), capture.message());
        Files.write(target, current);

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertFalse(restore.success());
        assertArrayEquals(current, Files.readAllBytes(target));
        assertFalse(restore.message().contains("PRIVATE_CHECKPOINT_CANARY"), restore.message());
        assertNoAtomicTemps(workspace);
    }

    @Test
    void committedUnverifiedRestoreIsNotDescribedAsAnUnchangedTarget(@TempDir Path temp)
            throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Path target = workspace.resolve("safe.txt");
        Files.writeString(target, "captured-before");
        AtomicFileWriter failingWriter = new AtomicFileWriter(
                (stage, ignoredTarget, ignoredTemp) -> {
                    if (stage == AtomicFileWriter.FaultStage.AFTER_PROMOTION) {
                        throw new java.io.IOException("PRIVATE_POST_COMMIT_CANARY");
                    }
                });
        FileBundleCheckpointStore store = new FileBundleCheckpointStore(
                temp.resolve("checkpoints"),
                failingWriter::replaceCurrent);
        CheckpointService service = new CheckpointService(store);
        CheckpointCaptureResult capture = service.captureBeforeMutation(
                workspace,
                config(true),
                new ToolCall(
                        "talos.write_file",
                        Map.of("path", "safe.txt", "content", "changed")),
                "trc-post-commit",
                1);
        assertTrue(capture.success(), capture.message());
        Files.writeString(target, "current-before-restore");

        CheckpointRestoreResult restore = service.restore(workspace, capture.checkpointId());

        assertFalse(restore.success());
        assertEquals("captured-before", Files.readString(target));
        assertTrue(restore.message().contains("committed"), restore.message());
        assertFalse(restore.message().contains("PRIVATE_POST_COMMIT_CANARY"), restore.message());
        assertNoAtomicTemps(workspace);
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

    private static UserDefinedFileAttributeView userDefinedView(Path target) {
        return Files.getFileAttributeView(
                target,
                UserDefinedFileAttributeView.class,
                LinkOption.NOFOLLOW_LINKS);
    }

    private static byte[] readUserDefinedAttribute(
            UserDefinedFileAttributeView view,
            String name
    ) throws Exception {
        if (view == null) {
            throw new java.io.IOException("user-defined attribute view is unavailable");
        }
        int size = view.size(name);
        ByteBuffer value = ByteBuffer.allocate(size);
        int read = view.read(name, value);
        if (read != size || value.position() != size) {
            throw new java.io.IOException("user-defined attribute read was incomplete");
        }
        return value.array();
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

    private static void assertNoAtomicTemps(Path directory) throws Exception {
        try (var paths = Files.list(directory)) {
            assertTrue(paths.noneMatch(path -> {
                String name = path.getFileName().toString();
                return name.startsWith(".talos-atomic-") && name.endsWith(".tmp");
            }), "checkpoint consumer must not leave an atomic-write temp");
        }
    }

    private static void createSymbolicLinkOrAbort(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
        } catch (java.io.IOException | UnsupportedOperationException unavailable) {
            Assumptions.abort("symbolic-link fixture is unavailable: "
                    + unavailable.getClass().getSimpleName());
        }
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
