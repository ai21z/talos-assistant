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
            root.put("model", data.model());
            root.put("turns", data.turns().stream()
                    .map(t -> Map.of("role", t.role(), "content", t.content(), "status", t.status()))
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
            String model     = str(root, "model");

            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawTurns =
                    (List<Map<String, String>>) root.getOrDefault("turns", List.of());

            List<SessionData.Turn> turns = rawTurns.stream()
                    .map(m -> new SessionData.Turn(
                            m.getOrDefault("role", ""),
                            m.getOrDefault("content", ""),
                            m.getOrDefault("status", "")))
                    .toList();

            return Optional.of(new SessionData(sid, workspace, sketch, turnCount, created, turns, model));
        } catch (Exception e) {
            LOG.warn("Failed to load session {}: {}", sessionId, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        try {
            boolean snap = Files.deleteIfExists(fileFor(sessionId));
            // Also remove the companion per-turn log, if any.
            boolean turns = Files.deleteIfExists(turnsFileFor(sessionId));
            return snap || turns;
        } catch (IOException e) {
            LOG.warn("Failed to delete session {}: {}", sessionId, e.getMessage());
            return false;
        }
    }

    // ── Per-turn structured durability (JSONL append-only) ───────────────

    @Override
    public void appendTurn(String sessionId, TurnRecord record) {
        if (sessionId == null || sessionId.isBlank() || record == null) return;
        try {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("turnNumber", record.turnNumber());
            row.put("timestamp", record.timestamp().toString());
            row.put("durationMs", record.durationMs());
            row.put("userInput", record.userInput());
            row.put("assistantText", record.assistantText());
            row.put("approvalsRequired", record.approvalsRequired());
            row.put("approvalsGranted", record.approvalsGranted());
            row.put("approvalsDenied", record.approvalsDenied());
            row.put("retrievalTraceSummary", record.retrievalTraceSummary());
            row.put("status", record.status());
            row.put("policyTrace", policyTraceToMap(record.policyTrace()));
            List<Map<String, Object>> calls = new java.util.ArrayList<>();
            for (TurnRecord.ToolCallSummary s : record.toolCalls()) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("name", s.name());
                c.put("pathHint", s.pathHint());
                c.put("success", s.success());
                c.put("reason", s.reason());
                calls.add(c);
            }
            row.put("toolCalls", calls);

            // JSONL: one compact JSON object per line.
            String line = MAPPER.writeValueAsString(row) + System.lineSeparator();
            Path file = turnsFileFor(sessionId);
            Files.writeString(file, line,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception e) {
            LOG.warn("Failed to append turn record for {}: {}", sessionId, e.getMessage());
        }
    }

    @Override
    public List<TurnRecord> loadTurns(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return List.of();
        Path file = turnsFileFor(sessionId);
        if (!Files.exists(file)) return List.of();
        List<TurnRecord> out = new java.util.ArrayList<>();
        // Lenient UTF-8 decoding: a single malformed byte (e.g. a partial
        // multi-byte character from a power-loss mid-write) must only affect
        // the line it lands in, not abort the whole load. Files.readAllLines
        // uses a strict decoder and would raise MalformedInputException,
        // losing the entire session transcript. With REPLACE, the corrupt
        // region becomes U+FFFD inside the affected line; Jackson then fails
        // to parse that line and skips it, while every surrounding line
        // loads intact.
        java.nio.charset.CharsetDecoder decoder = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE);
        try (var in = Files.newInputStream(file);
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(in, decoder))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                try {
                    Map<String, Object> row = MAPPER.readValue(line, new TypeReference<>() {});
                    out.add(rowToRecord(row));
                } catch (Exception lineErr) {
                    LOG.warn("Skipping malformed turn line in {}: {}", file.getFileName(), lineErr.getMessage());
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read turn log {}: {}", file, e.getMessage());
        }
        return out;
    }

    private static TurnRecord rowToRecord(Map<String, Object> row) {
        int turnNumber = intVal(row, "turnNumber");
        Instant ts = parseInstant(row.get("timestamp"));
        long durationMs = row.get("durationMs") instanceof Number n ? n.longValue() : 0L;
        String userInput = str(row, "userInput");
        String assistantText = str(row, "assistantText");
        int reqd = intVal(row, "approvalsRequired");
        int grnt = intVal(row, "approvalsGranted");
        int deny = intVal(row, "approvalsDenied");
        String traceSummary = str(row, "retrievalTraceSummary");
        String status = str(row, "status");
        TurnPolicyTrace policyTrace = policyTraceFrom(row.get("policyTrace"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rawCalls =
                (List<Map<String, Object>>) row.getOrDefault("toolCalls", List.of());
        List<TurnRecord.ToolCallSummary> calls = new java.util.ArrayList<>();
        for (Map<String, Object> c : rawCalls) {
            String name = c.get("name") == null ? "" : String.valueOf(c.get("name"));
            String pathHint = c.get("pathHint") == null ? "" : String.valueOf(c.get("pathHint"));
            boolean success = c.get("success") instanceof Boolean b && b;
            String reason = c.get("reason") == null ? "" : String.valueOf(c.get("reason"));
            calls.add(new TurnRecord.ToolCallSummary(name, pathHint, success, reason));
        }
        return new TurnRecord(turnNumber, ts, durationMs, userInput, assistantText,
                calls, reqd, grnt, deny, traceSummary, status, policyTrace);
    }

    private static Map<String, Object> policyTraceToMap(TurnPolicyTrace trace) {
        TurnPolicyTrace safe = trace == null ? TurnPolicyTrace.empty() : trace;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskType", safe.taskType());
        out.put("mutationAllowed", safe.mutationAllowed());
        out.put("verificationRequired", safe.verificationRequired());
        out.put("expectedTargets", safe.expectedTargets());
        out.put("forbiddenTargets", safe.forbiddenTargets());
        out.put("initialPhase", safe.initialPhase());
        out.put("finalPhase", safe.finalPhase());
        out.put("nativeTools", safe.nativeTools());
        out.put("promptTools", safe.promptTools());
        out.put("blocks", safe.blocks());
        return out;
    }

    private static TurnPolicyTrace policyTraceFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return TurnPolicyTrace.empty();
        return new TurnPolicyTrace(
                stringVal(map, "taskType", "UNKNOWN"),
                boolVal(map, "mutationAllowed"),
                boolVal(map, "verificationRequired"),
                stringList(map.get("expectedTargets")),
                stringList(map.get("forbiddenTargets")),
                stringVal(map, "initialPhase", "unknown"),
                stringVal(map, "finalPhase", "unknown"),
                stringList(map.get("nativeTools")),
                stringList(map.get("promptTools")),
                stringList(map.get("blocks")));
    }

    private static String stringVal(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static boolean boolVal(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b && b;
    }

    private static List<String> stringList(Object raw) {
        if (!(raw instanceof List<?> list)) return List.of();
        return list.stream()
                .map(value -> value == null ? "" : String.valueOf(value))
                .filter(value -> !value.isBlank())
                .toList();
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

    /** Companion JSONL file for per-turn append-only durability. */
    private Path turnsFileFor(String sessionId) {
        return sessionsDir.resolve(sessionId + ".turns.jsonl");
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

