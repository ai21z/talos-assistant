package dev.talos.runtime.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.security.WorkspaceContainment;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class FileBundleCheckpointStore implements CheckpointStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    /**
     * Monotonic capture sequence (T795): two checkpoints captured within the
     * same clock tick (the undo safety checkpoint follows the original by
     * milliseconds) would tie on createdAt and fall to a RANDOM UUID
     * tiebreak - making "newest" a coin flip. The sequence disambiguates
     * same-instant captures within a session; cross-session ordering stays
     * on createdAt.
     */
    private static final java.util.concurrent.atomic.AtomicLong CAPTURE_SEQUENCE =
            new java.util.concurrent.atomic.AtomicLong();
    private final Path root;
    private final RestoreFileWriter restoreFileWriter;

    public FileBundleCheckpointStore(Path root) {
        this(root, FileBundleCheckpointStore::replaceFileAtomically);
    }

    /** Package-private seam so tests can inject write faults mid-restore. */
    FileBundleCheckpointStore(Path root, RestoreFileWriter restoreFileWriter) {
        this.root = root == null ? CheckpointConfig.defaultRoot() : root;
        this.restoreFileWriter = restoreFileWriter == null
                ? FileBundleCheckpointStore::replaceFileAtomically
                : restoreFileWriter;
    }

    /** How restore puts a single file's replacement bytes in place. */
    interface RestoreFileWriter {
        void replaceFile(Path target, byte[] bytes) throws IOException;
    }

    /**
     * Per-file atomic replace: the bytes land in a temp file beside the
     * target and are promoted with a move, so a crash or fault mid-write
     * can never leave a half-written target - the file holds either its
     * previous content or the full replacement.
     */
    static void replaceFileAtomically(Path target, byte[] bytes) throws IOException {
        Path parent = target.getParent();
        if (parent == null) {
            Files.write(target, bytes);
            return;
        }
        Path tmp = Files.createTempFile(parent, ".talos-restore", ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Override
    public CheckpointCaptureResult captureBeforeMutation(
            Path workspace,
            Config config,
            ToolCall call,
            String traceId,
            int turnNumber
    ) {
        if (workspace == null || call == null) {
            return CheckpointCaptureResult.failure("Checkpoint requires workspace and tool call.");
        }
        String pathParam = pathParam(call);
        if (pathParam.isBlank()) {
            return CheckpointCaptureResult.failure("Checkpoint requires a target path.");
        }

        Path ws = workspace.toAbsolutePath().normalize();
        Path target = ws.resolve(pathParam).normalize();
        if (!WorkspaceContainment.contains(ws, target)) {
            return CheckpointCaptureResult.failure("Checkpoint target escapes workspace: " + pathParam);
        }
        if (Files.isDirectory(target)) {
            return CheckpointCaptureResult.failure("Checkpoint target is a directory: " + pathParam);
        }

        return captureRelativePaths(
                ws,
                config,
                List.of(pathParam),
                traceId,
                turnNumber,
                "file-bundle",
                call.toolName() + " " + pathParam);
    }

    @Override
    public CheckpointCaptureResult captureBeforeOperation(
            Path workspace,
            Config config,
            WorkspaceOperationPlan plan,
            String traceId,
            int turnNumber
    ) {
        if (workspace == null || plan == null) {
            return CheckpointCaptureResult.failure("Bundle checkpoint requires workspace and operation plan.");
        }
        List<String> checkpointPaths = plan.checkpointPaths();
        if (checkpointPaths.isEmpty()) {
            return CheckpointCaptureResult.skipped("Operation plan has no checkpoint paths.");
        }
        return captureRelativePaths(
                workspace.toAbsolutePath().normalize(),
                config,
                checkpointPaths,
                traceId,
                turnNumber,
                "workspace-operation",
                "workspace operation: " + String.join(", ", checkpointPaths));
    }

    @Override
    public CheckpointCaptureResult captureBeforeRestore(
            Path workspace,
            Config config,
            List<String> relativePaths,
            String trigger,
            String traceId,
            int turnNumber
    ) {
        if (workspace == null) {
            return CheckpointCaptureResult.failure("Restore-safety capture requires a workspace.");
        }
        return captureRelativePaths(
                workspace.toAbsolutePath().normalize(),
                config,
                relativePaths,
                traceId,
                turnNumber,
                "restore-safety",
                trigger == null || trigger.isBlank() ? "restore safety" : trigger);
    }

    private CheckpointCaptureResult captureRelativePaths(
            Path ws,
            Config config,
            List<String> relativePaths,
            String traceId,
            int turnNumber,
            String backend,
            String trigger
    ) {
        if (ws == null || relativePaths == null || relativePaths.isEmpty()) {
            return CheckpointCaptureResult.failure("Checkpoint requires at least one target path.");
        }
        CheckpointConfig cfg = CheckpointConfig.from(config);
        Set<String> normalizedPaths = new LinkedHashSet<>();
        for (String rel : relativePaths) {
            if (rel != null && !rel.isBlank()) {
                normalizedPaths.add(rel.replace('\\', '/'));
            }
        }
        if (normalizedPaths.isEmpty()) {
            return CheckpointCaptureResult.failure("Checkpoint requires at least one target path.");
        }

        try {
            List<Path> targets = new ArrayList<>();
            for (String requestedRel : normalizedPaths) {
                Path target = ws.resolve(requestedRel).normalize();
                if (!WorkspaceContainment.contains(ws, target)) {
                    return CheckpointCaptureResult.failure("Checkpoint target escapes workspace: " + requestedRel);
                }
                targets.add(target);
            }

            String workspaceId = JsonSessionStore.sessionIdFor(ws);
            String checkpointId = newCheckpointId();
            Path dir = checkpointDir(workspaceId, checkpointId);
            Path blobs = dir.resolve("blobs");
            Files.createDirectories(blobs);

            List<Map<String, Object>> files = new ArrayList<>();
            long byteCount = 0L;
            for (Path target : targets) {
                CaptureStats stats = capturePath(ws, target, blobs, cfg, files);
                byteCount += stats.byteCount();
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", 1);
            metadata.put("checkpointId", checkpointId);
            metadata.put("workspaceId", workspaceId);
            metadata.put("createdAt", Instant.now().toString());
            metadata.put("turnNumber", turnNumber);
            metadata.put("traceId", traceId == null ? "" : traceId);
            metadata.put("backend", backend == null || backend.isBlank() ? "file-bundle" : backend);
            // T793: optional human trigger; schemaVersion stays 1 - pre-T793
            // readers ignore unknown keys, pre-T793 checkpoints render
            // "(unknown)".
            metadata.put("trigger", trigger == null ? "" : trigger);
            metadata.put("sequence", CAPTURE_SEQUENCE.incrementAndGet());
            metadata.put("status", "CREATED");
            metadata.put("fileCount", files.size());
            metadata.put("byteCount", byteCount);

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("checkpointId", checkpointId);
            manifest.put("files", files);

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metadata.json").toFile(), metadata);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("manifest.json").toFile(), manifest);

            return CheckpointCaptureResult.captured(checkpointId, files.size());
        } catch (Exception e) {
            return CheckpointCaptureResult.failure("Failed to create checkpoint: " + e.getMessage());
        }
    }

    @Override
    public CheckpointRestoreResult restore(Path workspace, String checkpointId) {
        if (workspace == null || checkpointId == null || checkpointId.isBlank()) {
            return CheckpointRestoreResult.failure(checkpointId, "Workspace and checkpoint id are required.");
        }
        Path ws = workspace.toAbsolutePath().normalize();
        String workspaceId = JsonSessionStore.sessionIdFor(ws);
        Path dir = checkpointDir(workspaceId, sanitizeId(checkpointId));
        Path manifestFile = dir.resolve("manifest.json");
        if (!Files.exists(manifestFile)) {
            return CheckpointRestoreResult.failure(checkpointId, "Checkpoint not found: " + checkpointId);
        }

        int restored = 0;
        int deleted = 0;
        try {
            Map<String, Object> manifest = MAPPER.readValue(
                    Files.readString(manifestFile),
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
            RestorePreflight preflight = preflightRestoreEntries(ws, dir, files);
            if (preflight.failed() > 0) {
                String message = preflight.failureReasons().isEmpty()
                        ? "Checkpoint restore preflight failed."
                        : "Checkpoint restore preflight failed: "
                                + String.join("; ", preflight.failureReasons());
                return CheckpointRestoreResult.partial(
                        checkpointId,
                        message,
                        0,
                        0,
                        preflight.failed());
            }
            // Phase 1 - constructive. Every expected path is put in place
            // WITHOUT deleting anything: directories are created (never
            // pre-wiped), file overwrites are per-file atomic, and a path
            // whose type flipped since capture is displaced aside instead
            // of destroyed. A failure anywhere in this phase therefore
            // cannot strip the live tree - the residual is a mix of live
            // and checkpoint content, never a loss (the restore-safety
            // checkpoint taken before restore covers rolling that back).
            List<Path> displaced = new ArrayList<>();
            for (RestoreEntry entry : preflight.entries()) {
                if (!entry.existedBefore()) continue;
                if ("DIRECTORY".equals(entry.entryType())) {
                    displaceTypeConflict(entry.target(), true, displaced);
                    Files.createDirectories(entry.target());
                    restored++;
                    continue;
                }
                Path parent = entry.target().getParent();
                if (parent != null) Files.createDirectories(parent);
                displaceTypeConflict(entry.target(), false, displaced);
                restoreFileWriter.replaceFile(entry.target(), entry.bytes());
                restored++;
            }

            // Phase 2 - destructive, only now that the replacement content
            // is fully in place: children created after capture inside
            // restored directory trees, recorded-absent targets, and any
            // displaced type-conflict remnants.
            Set<Path> expected = expectedTargets(preflight.entries());
            for (RestoreEntry entry : preflight.entries()) {
                if (entry.existedBefore() && "DIRECTORY".equals(entry.entryType())) {
                    deleteExtrasUnder(entry.target(), expected);
                }
            }
            for (RestoreEntry entry : preflight.entries()) {
                if (!entry.existedBefore()) {
                    deletePathIfExists(entry.target());
                    deleted++;
                }
            }
            for (Path leftover : displaced) {
                deletePathIfExists(leftover);
            }
        } catch (Exception e) {
            return CheckpointRestoreResult.partial(
                    checkpointId,
                    "Checkpoint restore failed: " + e.getMessage(),
                    restored,
                    deleted,
                    1);
        }
        return CheckpointRestoreResult.success(checkpointId, restored, deleted);
    }

    private static RestorePreflight preflightRestoreEntries(
            Path workspace,
            Path checkpointDir,
            List<Map<String, Object>> files
    ) throws Exception {
        List<RestoreEntry> entries = new ArrayList<>();
        List<String> failureReasons = new ArrayList<>();
        int failed = 0;
        for (Map<String, Object> entry : files) {
            String rel = String.valueOf(entry.getOrDefault("relativePath", ""));
            if (rel.isBlank()) {
                failed++;
                failureReasons.add("blank manifest path");
                continue;
            }
            Path target = workspace.resolve(rel).normalize();
            if (!WorkspaceContainment.contains(workspace, target)) {
                failed++;
                failureReasons.add("manifest path escapes workspace: " + rel);
                continue;
            }
            boolean existedBefore = Boolean.TRUE.equals(entry.get("existedBefore"));
            String entryType = String.valueOf(entry.getOrDefault("entryType", "FILE"));
            if (!existedBefore || "DIRECTORY".equals(entryType)) {
                entries.add(new RestoreEntry(rel, target, existedBefore, entryType, null));
                continue;
            }
            String blobSha = String.valueOf(entry.getOrDefault("blobSha256", ""));
            if (blobSha.isBlank()) {
                failed++;
                failureReasons.add("missing blob hash for " + rel);
                continue;
            }
            if (!isSha256Hex(blobSha)) {
                failed++;
                failureReasons.add("invalid blob sha256 for " + rel);
                continue;
            }
            Path blob = checkpointDir.resolve("blobs").resolve(blobSha);
            if (!Files.isRegularFile(blob)) {
                failed++;
                failureReasons.add("missing blob for " + rel);
                continue;
            }
            byte[] bytes = Files.readAllBytes(blob);
            String actualSha = sha256(bytes);
            if (!blobSha.equalsIgnoreCase(actualSha)) {
                failed++;
                failureReasons.add("blob integrity mismatch for " + rel);
                continue;
            }
            entries.add(new RestoreEntry(rel, target, true, entryType, bytes));
        }
        return new RestorePreflight(List.copyOf(entries), failed, List.copyOf(failureReasons));
    }

    /**
     * A restore target whose on-disk type no longer matches the checkpoint
     * (a file where a directory was captured, or the reverse) is moved
     * aside instead of deleted, so the content survives a later phase-1
     * failure. Successful restores clean the displaced remnant in phase 2;
     * after a failure it remains beside the target as recoverable data.
     */
    private static void displaceTypeConflict(
            Path target,
            boolean wantDirectory,
            List<Path> displaced
    ) throws IOException {
        if (!Files.exists(target)) return;
        if (Files.isDirectory(target) == wantDirectory) return;
        Path aside = target.resolveSibling(
                target.getFileName() + ".talos-displaced-" + UUID.randomUUID());
        Files.move(target, aside);
        displaced.add(aside);
    }

    /** All paths the manifest says should exist after the restore. */
    private static Set<Path> expectedTargets(List<RestoreEntry> entries) {
        Set<Path> expected = new LinkedHashSet<>();
        for (RestoreEntry entry : entries) {
            if (entry.existedBefore()) {
                expected.add(entry.target().toAbsolutePath().normalize());
            }
        }
        return expected;
    }

    /**
     * Deletes everything under {@code dir} that the manifest does not
     * expect - the replacement for the old delete-then-rewrite, run only
     * after every expected path is already in place. Reverse walk order
     * removes children before their directories.
     */
    private static void deleteExtrasUnder(Path dir, Set<Path> expected) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (expected.contains(path.toAbsolutePath().normalize())) continue;
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deletePathIfExists(Path target) throws IOException {
        if (!Files.exists(target)) return;
        if (Files.isDirectory(target)) {
            try (var stream = Files.walk(target)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        } else {
            Files.deleteIfExists(target);
        }
    }

    @Override
    public List<String> listIds(Path workspace) {
        // T793: ids now follow the summary ordering - truly newest-first by
        // createdAt instead of reverse-lexicographic on random UUIDs.
        return listSummaries(workspace).stream().map(CheckpointSummary::id).toList();
    }

    @Override
    public List<CheckpointSummary> listSummaries(Path workspace) {
        if (workspace == null) return List.of();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize());
        Path dir = root.resolve(workspaceId).resolve("checkpoints");
        if (!Files.isDirectory(dir)) return List.of();
        record Sortable(CheckpointSummary summary, long sequence) {}
        List<Sortable> sortable = new ArrayList<>();
        try (var stream = Files.list(dir)) {
            for (Path checkpointDir : stream.filter(Files::isDirectory).toList()) {
                sortable.add(new Sortable(readSummary(checkpointDir), readSequence(checkpointDir)));
            }
        } catch (IOException e) {
            return List.of();
        }
        sortable.sort(Comparator.comparing((Sortable s) -> s.summary().createdAt()).reversed()
                .thenComparing(Comparator.comparingLong(Sortable::sequence).reversed())
                .thenComparing(s -> s.summary().id(), Comparator.reverseOrder()));
        return sortable.stream().map(Sortable::summary).toList();
    }

    private static long readSequence(Path checkpointDir) {
        try {
            Map<String, Object> metadata = MAPPER.readValue(
                    Files.readString(checkpointDir.resolve("metadata.json")),
                    new TypeReference<>() {});
            return asLong(metadata.getOrDefault("sequence", 0L));
        } catch (Exception e) {
            return 0L;
        }
    }

    @Override
    public java.util.Optional<CheckpointDetail> describe(Path workspace, String checkpointId) {
        if (workspace == null || checkpointId == null || checkpointId.isBlank()) {
            return java.util.Optional.empty();
        }
        String workspaceId = JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize());
        Path dir = checkpointDir(workspaceId, sanitizeId(checkpointId));
        if (!Files.isDirectory(dir)) return java.util.Optional.empty();
        List<CheckpointDetail.Entry> entries = new ArrayList<>();
        try {
            Map<String, Object> manifest = MAPPER.readValue(
                    Files.readString(dir.resolve("manifest.json")),
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files =
                    (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
            for (Map<String, Object> entry : files) {
                entries.add(new CheckpointDetail.Entry(
                        String.valueOf(entry.getOrDefault("relativePath", "")),
                        String.valueOf(entry.getOrDefault("entryType", "FILE")),
                        Boolean.TRUE.equals(entry.get("existedBefore")),
                        String.valueOf(entry.getOrDefault("blobSha256", "")),
                        asLong(entry.get("sizeBytes"))));
            }
        } catch (Exception e) {
            return java.util.Optional.of(new CheckpointDetail(readSummary(dir), List.of()));
        }
        return java.util.Optional.of(new CheckpointDetail(readSummary(dir), entries));
    }

    @Override
    public java.util.Optional<byte[]> blob(Path workspace, String checkpointId, String blobSha256) {
        if (workspace == null || checkpointId == null || checkpointId.isBlank()
                || blobSha256 == null || blobSha256.isBlank()) {
            return java.util.Optional.empty();
        }
        String workspaceId = JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize());
        Path blobFile = checkpointDir(workspaceId, sanitizeId(checkpointId))
                .resolve("blobs")
                .resolve(sanitizeId(blobSha256));
        if (!Files.isRegularFile(blobFile)) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(Files.readAllBytes(blobFile));
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** Tolerant metadata read: a corrupt or pre-T793 checkpoint still lists. */
    private static CheckpointSummary readSummary(Path checkpointDir) {
        String id = checkpointDir.getFileName().toString();
        try {
            Map<String, Object> metadata = MAPPER.readValue(
                    Files.readString(checkpointDir.resolve("metadata.json")),
                    new TypeReference<>() {});
            Instant createdAt;
            try {
                createdAt = Instant.parse(String.valueOf(metadata.getOrDefault("createdAt", "")));
            } catch (Exception e) {
                createdAt = Instant.EPOCH;
            }
            return new CheckpointSummary(
                    id,
                    createdAt,
                    (int) asLong(metadata.getOrDefault("turnNumber", -1L)),
                    String.valueOf(metadata.getOrDefault("trigger", "")),
                    (int) asLong(metadata.getOrDefault("fileCount", 0L)),
                    asLong(metadata.getOrDefault("byteCount", 0L)),
                    String.valueOf(metadata.getOrDefault("status", "")));
        } catch (Exception e) {
            return new CheckpointSummary(id, Instant.EPOCH, -1, "", 0, 0L,
                    "(metadata unavailable)");
        }
    }

    private static long asLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0L;
        }
    }

    private Path checkpointDir(String workspaceId, String checkpointId) {
        return root.resolve(workspaceId).resolve("checkpoints").resolve(checkpointId);
    }

    private static CaptureStats capturePath(
            Path workspace,
            Path target,
            Path blobs,
            CheckpointConfig cfg,
            List<Map<String, Object>> files
    ) throws Exception {
        String rel = normalizeRelative(workspace.relativize(target));
        if (!Files.exists(target)) {
            files.add(fileEntry(rel, "PATH", false, "", 0, "RECORDED_ABSENT"));
            return new CaptureStats(0);
        }
        if (Files.isDirectory(target)) {
            long bytes = 0;
            try (var stream = Files.walk(target)) {
                for (Path directory : stream
                        .filter(Files::isDirectory)
                        .sorted()
                        .toList()) {
                    files.add(fileEntry(
                            normalizeRelative(workspace.relativize(directory)),
                            "DIRECTORY",
                            true,
                            "",
                            0,
                            "DIRECTORY_RECORDED"));
                }
            }
            try (var stream = Files.walk(target)) {
                for (Path file : stream
                        .filter(Files::isRegularFile)
                        .sorted()
                        .toList()) {
                    CaptureStats stats = captureExistingFile(workspace, file, blobs, cfg, files);
                    bytes += stats.byteCount();
                }
            }
            return new CaptureStats(bytes);
        }
        return captureExistingFile(workspace, target, blobs, cfg, files);
    }

    private static CaptureStats captureExistingFile(
            Path workspace,
            Path target,
            Path blobs,
            CheckpointConfig cfg,
            List<Map<String, Object>> files
    ) throws Exception {
        byte[] bytes = Files.readAllBytes(target);
        String rel = normalizeRelative(workspace.relativize(target));
        if (bytes.length > cfg.maxFileBytes()) {
            throw new IOException("Checkpoint target exceeds max_file_bytes: " + rel);
        }
        String blobSha = sha256(bytes);
        writeBlobAtomically(blobs.resolve(blobSha), bytes);
        files.add(fileEntry(rel, "FILE", true, blobSha, bytes.length, "CAPTURED"));
        return new CaptureStats(bytes.length);
    }

    private static void writeBlobAtomically(Path blob, byte[] bytes) throws Exception {
        Path parent = blob.getParent();
        if (parent != null) Files.createDirectories(parent);
        if (Files.isRegularFile(blob)) {
            byte[] existing = Files.readAllBytes(blob);
            if (sha256(existing).equalsIgnoreCase(blob.getFileName().toString())) {
                return;
            }
        }
        Path tmp = Files.createTempFile(parent, blob.getFileName().toString(), ".tmp");
        try {
            Files.write(tmp, bytes);
            try {
                Files.move(tmp, blob, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, blob, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static Map<String, Object> fileEntry(
            String rel,
            String entryType,
            boolean existed,
            String blobSha,
            long sizeBytes,
            String captureStatus
    ) throws Exception {
        Map<String, Object> file = new LinkedHashMap<>();
        file.put("relativePath", rel);
        file.put("pathHash", sha256(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        file.put("entryType", entryType);
        file.put("existedBefore", existed);
        file.put("blobSha256", blobSha == null ? "" : blobSha);
        file.put("sizeBytes", sizeBytes);
        file.put("captureStatus", captureStatus);
        return file;
    }

    private static String newCheckpointId() {
        return "chk-" + UUID.randomUUID();
    }

    private static String sanitizeId(String checkpointId) {
        return checkpointId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String pathParam(ToolCall call) {
        for (String key : List.of("path", "file_path", "filepath", "file", "filename")) {
            String value = call.param(key);
            if (value != null && !value.isBlank()) return value;
        }
        return "";
    }

    private static String normalizeRelative(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static boolean isSha256Hex(String value) {
        return value != null && value.matches("(?i)[0-9a-f]{64}");
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private record RestoreEntry(
            String relativePath,
            Path target,
            boolean existedBefore,
            String entryType,
            byte[] bytes
    ) {}

    private record RestorePreflight(
            List<RestoreEntry> entries,
            int failed,
            List<String> failureReasons
    ) {}

    private record CaptureStats(long byteCount) {}
}
