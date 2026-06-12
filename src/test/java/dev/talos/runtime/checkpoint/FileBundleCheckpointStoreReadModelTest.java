package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** T793: checkpoint read model — true ordering, trigger, tolerance, safety capture. */
class FileBundleCheckpointStoreReadModelTest {

    @TempDir Path tempDir;

    @Test
    void summariesSortNewestFirstByCreatedAtNotById() throws Exception {
        Workspace ws = workspace();
        String first = capture(ws, "a.txt");
        String second = capture(ws, "a.txt");
        // Force timestamps that INVERT any id-based ordering: make `first`
        // the newest regardless of UUID sort order.
        setCreatedAt(ws, first, "2026-06-12T10:00:00Z");
        setCreatedAt(ws, second, "2026-06-12T09:00:00Z");

        List<CheckpointSummary> summaries = ws.store.listSummaries(ws.root);

        assertEquals(List.of(first, second),
                summaries.stream().map(CheckpointSummary::id).toList(),
                "ordering must follow createdAt, never the random UUID id");
        assertEquals(List.of(first, second), ws.store.listIds(ws.root),
                "listIds follows the same true-chronological ordering since T793");
    }

    @Test
    void summariesCarryTriggerTurnAndCounts() throws Exception {
        Workspace ws = workspace();
        String id = capture(ws, "a.txt");

        CheckpointSummary summary = ws.store.listSummaries(ws.root).get(0);

        assertEquals(id, summary.id());
        assertEquals("talos.write_file a.txt", summary.trigger());
        assertEquals(7, summary.turnNumber());
        assertEquals(1, summary.fileCount());
        assertTrue(summary.byteCount() > 0);
        assertEquals("CREATED", summary.status());
    }

    @Test
    void preT793AndCorruptMetadataStayListable() throws Exception {
        Workspace ws = workspace();
        String legacy = capture(ws, "a.txt");
        String corrupt = capture(ws, "a.txt");
        // Pre-T793 checkpoints have no trigger key.
        Path legacyMetadata = metadataFile(ws, legacy);
        Files.writeString(legacyMetadata,
                Files.readString(legacyMetadata).replaceAll("\\s*\"trigger\".*,\\R", "\n"));
        Files.writeString(metadataFile(ws, corrupt), "{not json");

        List<CheckpointSummary> summaries = ws.store.listSummaries(ws.root);

        CheckpointSummary legacySummary = summaries.stream()
                .filter(s -> s.id().equals(legacy)).findFirst().orElseThrow();
        CheckpointSummary corruptSummary = summaries.stream()
                .filter(s -> s.id().equals(corrupt)).findFirst().orElseThrow();
        assertEquals("(unknown)", legacySummary.trigger());
        assertEquals("(metadata unavailable)", corruptSummary.status());
    }

    @Test
    void describeExposesManifestEntriesAndBlobsRoundTrip() throws Exception {
        Workspace ws = workspace();
        Files.writeString(ws.root.resolve("a.txt"), "captured content", StandardCharsets.UTF_8);
        String id = capture(ws, "a.txt");

        Optional<CheckpointDetail> detail = ws.store.describe(ws.root, id);

        assertTrue(detail.isPresent());
        CheckpointDetail.Entry entry = detail.get().entries().get(0);
        assertEquals("a.txt", entry.relativePath());
        assertTrue(entry.existedBefore());
        byte[] blob = ws.store.blob(ws.root, id, entry.blobSha256()).orElseThrow();
        assertEquals("captured content", new String(blob, StandardCharsets.UTF_8));
    }

    @Test
    void safetyCaptureMakesARestoreItselfUndoable() throws Exception {
        Workspace ws = workspace();
        Files.writeString(ws.root.resolve("a.txt"), "original", StandardCharsets.UTF_8);
        String checkpoint = capture(ws, "a.txt"); // captures "original"
        Files.writeString(ws.root.resolve("a.txt"), "edited", StandardCharsets.UTF_8);

        CheckpointCaptureResult safety = ws.store.captureBeforeRestore(
                ws.root, config(), List.of("a.txt"), "undo of " + checkpoint, "trc", 8);
        assertTrue(safety.success(), safety.message());

        assertTrue(ws.store.restore(ws.root, checkpoint).success());
        assertEquals("original", Files.readString(ws.root.resolve("a.txt")));

        assertTrue(ws.store.restore(ws.root, safety.checkpointId()).success());
        assertEquals("edited", Files.readString(ws.root.resolve("a.txt")),
                "restoring the safety checkpoint redoes the undone state");

        CheckpointSummary safetySummary = ws.store.listSummaries(ws.root).stream()
                .filter(s -> s.id().equals(safety.checkpointId())).findFirst().orElseThrow();
        assertEquals("undo of " + checkpoint, safetySummary.trigger());
    }

    @Test
    void restoreTraceIsBestEffortWithoutAnActiveBag() {
        assertDoesNotThrow(() ->
                LocalTurnTraceCapture.recordCheckpointRestore("chk-x", true, 1, 0, ""));
        assertDoesNotThrow(() ->
                LocalTurnTraceCapture.recordCheckpointRestore("chk-x", false, 0, 0, "boom"));
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private record Workspace(Path root, FileBundleCheckpointStore store, Path storeRoot) {}

    private Workspace workspace() throws Exception {
        Path root = tempDir.resolve("ws-" + System.nanoTime());
        Files.createDirectories(root);
        Files.writeString(root.resolve("a.txt"), "seed", StandardCharsets.UTF_8);
        Path storeRoot = tempDir.resolve("checkpoints-" + System.nanoTime());
        return new Workspace(root, new FileBundleCheckpointStore(storeRoot), storeRoot);
    }

    private static String capture(Workspace ws, String path) {
        CheckpointCaptureResult result = ws.store.captureBeforeMutation(
                ws.root, config(),
                new ToolCall("talos.write_file", Map.of("path", path, "content", "x")),
                "trc", 7);
        assertTrue(result.success(), result.message());
        return result.checkpointId();
    }

    private static Config config() {
        Config config = new Config();
        config.data.put("checkpoint", Map.of("enabled", true, "fail_closed", true));
        return config;
    }

    private Path metadataFile(Workspace ws, String checkpointId) {
        String workspaceId = JsonSessionStore.sessionIdFor(ws.root.toAbsolutePath().normalize());
        return ws.storeRoot.resolve(workspaceId).resolve("checkpoints")
                .resolve(checkpointId).resolve("metadata.json");
    }

    private void setCreatedAt(Workspace ws, String checkpointId, String instant) throws Exception {
        Path file = metadataFile(ws, checkpointId);
        String json = Files.readString(file)
                .replaceAll("\"createdAt\"\\s*:\\s*\"[^\"]*\"",
                        "\"createdAt\" : \"" + instant + "\"");
        Files.writeString(file, json);
    }
}
