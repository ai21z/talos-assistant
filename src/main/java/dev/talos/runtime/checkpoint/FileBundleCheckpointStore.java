package dev.talos.runtime.checkpoint;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.runtime.JsonSessionStore;
import dev.talos.tools.ToolCall;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        CheckpointConfig cfg = CheckpointConfig.from(config);
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

        try {
            boolean existed = Files.exists(target);
            byte[] bytes = existed ? Files.readAllBytes(target) : new byte[0];
            if (bytes.length > cfg.maxFileBytes()) {
                return CheckpointCaptureResult.failure("Checkpoint target exceeds max_file_bytes: " + pathParam);
            }

            String workspaceId = JsonSessionStore.sessionIdFor(ws);
            String checkpointId = newCheckpointId();
            Path dir = checkpointDir(workspaceId, checkpointId);
            Path blobs = dir.resolve("blobs");
            Files.createDirectories(blobs);

            String rel = normalizeRelative(ws.relativize(target));
            String blobSha = "";
            if (existed) {
                blobSha = sha256(bytes);
                Files.write(blobs.resolve(blobSha), bytes);
            }

            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("schemaVersion", 1);
            metadata.put("checkpointId", checkpointId);
            metadata.put("workspaceId", workspaceId);
            metadata.put("createdAt", Instant.now().toString());
            metadata.put("turnNumber", turnNumber);
            metadata.put("traceId", traceId == null ? "" : traceId);
            metadata.put("backend", "file-bundle");
            metadata.put("status", "CREATED");
            metadata.put("fileCount", 1);
            metadata.put("byteCount", bytes.length);

            Map<String, Object> file = new LinkedHashMap<>();
            file.put("relativePath", rel);
            file.put("pathHash", sha256(rel.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            file.put("existedBefore", existed);
            file.put("blobSha256", blobSha);
            file.put("sizeBytes", bytes.length);
            file.put("captureStatus", existed ? "CAPTURED" : "RECORDED_ABSENT");

            Map<String, Object> manifest = new LinkedHashMap<>();
            manifest.put("schemaVersion", 1);
            manifest.put("checkpointId", checkpointId);
            manifest.put("files", List.of(file));

            MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("metadata.json").toFile(), metadata);
            MAPPER.writerWithDefaultPrettyPrinter().writeValue(dir.resolve("manifest.json").toFile(), manifest);

            return CheckpointCaptureResult.captured(checkpointId, 1);
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
                    Files.deleteIfExists(target);
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
}
