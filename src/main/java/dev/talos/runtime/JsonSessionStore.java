package dev.talos.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * File-backed {@link SessionStore} that persists session state as JSON
 * under {@code ~/.talos/sessions/<workspace-hash>.json}.
 *
 * <p>Each workspace gets a single session file keyed by the SHA-1 hash
 * of its absolute normalized path. Save is fire-and-forget (errors are
 * logged but never thrown). Load returns empty on any I/O or parse failure.
 *
 * <p>Thread-safe: each method is self-contained with no shared mutable state.
 */
public final class JsonSessionStore implements SessionStore {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Path sessionsDir;

    /** Default location: {@code ~/.talos/sessions/}. */
    public JsonSessionStore() {
        this(Path.of(System.getProperty("user.home"), ".talos", "sessions"));
    }

    /** Custom directory (useful for testing with {@code @TempDir}). */
    public JsonSessionStore(Path sessionsDir) {
        this.sessionsDir = sessionsDir;
        try {
            Files.createDirectories(sessionsDir);
        } catch (IOException e) {
            LOG.warn("Could not create sessions directory {}: {}", sessionsDir, e.getMessage());
        }
    }

    // ── SessionStore contract ─────────────────────────────────────────

    @Override
    public void save(SessionData data) {
        if (data == null || data.sessionId().isBlank()) return;
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            root.put("sessionId", data.sessionId());
            root.put("workspace", data.workspace());
            root.put("sketch", data.sketch());
            root.put("turnCount", data.turnCount());
            root.put("createdAt", data.createdAt().toString());
            root.put("turns", data.turns().stream()
                    .map(t -> Map.of("role", t.role(), "content", t.content()))
                    .toList());

            String json = MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
            Path file = fileFor(data.sessionId());
            Files.writeString(file, json);
            LOG.debug("Session saved: {} ({} turns)", file.getFileName(), data.turnCount());
        } catch (Exception e) {
            LOG.warn("Failed to save session {}: {}", data.sessionId(), e.getMessage());
        }
    }

    @Override
    public Optional<SessionData> load(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        Path file = fileFor(sessionId);
        if (!Files.exists(file)) return Optional.empty();

        try {
            Map<String, Object> root = MAPPER.readValue(
                    Files.readString(file), new TypeReference<>() {});

            String sid       = str(root, "sessionId");
            String workspace = str(root, "workspace");
            String sketch    = str(root, "sketch");
            int turnCount    = intVal(root, "turnCount");
            Instant created  = parseInstant(root.get("createdAt"));

            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawTurns =
                    (List<Map<String, String>>) root.getOrDefault("turns", List.of());

            List<SessionData.Turn> turns = rawTurns.stream()
                    .map(m -> new SessionData.Turn(
                            m.getOrDefault("role", ""),
                            m.getOrDefault("content", "")))
                    .toList();

            return Optional.of(new SessionData(sid, workspace, sketch, turnCount, created, turns));
        } catch (Exception e) {
            LOG.warn("Failed to load session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            return Files.deleteIfExists(fileFor(sessionId));
        } catch (IOException e) {
            LOG.warn("Failed to delete session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ── Utility ───────────────────────────────────────────────────────

    /**
     * Derive a session ID from a workspace path.
     * Uses SHA-1 of the absolute normalized path string.
     */
    public static String sessionIdFor(Path workspace) {
        return Hash.sha1Hex(workspace.toAbsolutePath().normalize().toString());
    }

    /** The directory where session files are stored. */
    public Path sessionsDir() {
        return sessionsDir;
    }

    // ── Internal ──────────────────────────────────────────────────────

    private Path fileFor(String sessionId) {
        return sessionsDir.resolve(sessionId + ".json");
    }

    private static String str(Map<String, Object> map, String key) {
        Object v = map.get(key);
        return v == null ? "" : String.valueOf(v);
    }

    private static int intVal(Map<String, Object> map, String key) {
        Object v = map.get(key);
        if (v instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(v)); }
        catch (Exception e) { return 0; }
    }

    private static Instant parseInstant(Object v) {
        if (v == null) return Instant.now();
        try { return Instant.parse(String.valueOf(v)); }
        catch (Exception e) { return Instant.now(); }
    }
}

