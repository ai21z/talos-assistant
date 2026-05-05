package dev.talos.runtime.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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
    private final Path root;

    public FileBundleCheckpointStore(Path root) {
        this.root = root == null ? CheckpointConfig.defaultRoot() : root;
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
        if (!startsWithWorkspace(target, ws)) {
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
                "file-bundle");
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
                "workspace-operation");
    }

    private CheckpointCaptureResult captureRelativePaths(
            Path ws,
            Config config,
            List<String> relativePaths,
            String traceId,
            int turnNumber,
            String backend
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
                if (!startsWithWorkspace(target, ws)) {
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
        int failed = 0;
        try {
            Map<String, Object> manifest = MAPPER.readValue(
                    Files.readString(manifestFile),
                    new TypeReference<>() {});
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> files = (List<Map<String, Object>>) manifest.getOrDefault("files", List.of());
            for (Map<String, Object> entry : files) {
                String rel = String.valueOf(entry.getOrDefault("relativePath", ""));
                if (rel.isBlank()) {
                    failed++;
                    continue;
                }
                Path target = ws.resolve(rel).normalize();
                if (!startsWithWorkspace(target, ws)) {
                    failed++;
                    continue;
                }
                boolean existedBefore = Boolean.TRUE.equals(entry.get("existedBefore"));
                if (existedBefore) {
                    String entryType = String.valueOf(entry.getOrDefault("entryType", "FILE"));
                    if ("DIRECTORY".equals(entryType)) {
                        Files.createDirectories(target);
                        restored++;
                        continue;
                    }
                    String blobSha = String.valueOf(entry.getOrDefault("blobSha256", ""));
                    if (blobSha.isBlank()) {
                        failed++;
                        continue;
                    }
                    byte[] bytes = Files.readAllBytes(dir.resolve("blobs").resolve(blobSha));
                    Path parent = target.getParent();
                    if (parent != null) Files.createDirectories(parent);
                    Files.write(target, bytes);
                    restored++;
                } else {
                    deletePathIfExists(target);
                    deleted++;
                }
            }
        } catch (Exception e) {
            return CheckpointRestoreResult.partial(
                    checkpointId,
                    "Checkpoint restore failed: " + e.getMessage(),
                    restored,
                    deleted,
                    failed + 1);
        }
        if (failed > 0) {
            return CheckpointRestoreResult.partial(
                    checkpointId,
                    "Checkpoint restore partially failed.",
                    restored,
                    deleted,
                    failed);
        }
        return CheckpointRestoreResult.success(checkpointId, restored, deleted);
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
        if (workspace == null) return List.of();
        String workspaceId = JsonSessionStore.sessionIdFor(workspace.toAbsolutePath().normalize());
        Path dir = root.resolve(workspaceId).resolve("checkpoints");
        if (!Files.isDirectory(dir)) return List.of();
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(Files::isDirectory)
                    .map(path -> path.getFileName().toString())
                    .sorted(Comparator.reverseOrder())
                    .toList();
        } catch (IOException e) {
            return List.of();
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
            files.add(fileEntry(rel, "DIRECTORY", true, "", 0, "DIRECTORY_RECORDED"));
            long bytes = 0;
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
        Files.write(blobs.resolve(blobSha), bytes);
        files.add(fileEntry(rel, "FILE", true, blobSha, bytes.length, "CAPTURED"));
        return new CaptureStats(bytes.length);
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

    private static boolean startsWithWorkspace(Path resolved, Path workspace) {
        if (resolved.startsWith(workspace)) return true;
        if (isWindows()) {
            return resolved.toString().toLowerCase(java.util.Locale.ROOT)
                    .startsWith(workspace.toString().toLowerCase(java.util.Locale.ROOT));
        }
        return false;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
    }

    private static String normalizeRelative(Path relative) {
        return relative.toString().replace('\\', '/');
    }

    private static String sha256(byte[] bytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(bytes));
    }

    private record CaptureStats(long byteCount) {}
}
