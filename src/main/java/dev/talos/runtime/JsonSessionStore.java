package dev.talos.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.util.Hash;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.runtime.context.ActiveTaskContext;
import dev.talos.runtime.context.ArtifactGoal;
import dev.talos.runtime.intent.IntentDerivation;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskIntentEvidence;
import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.trace.LocalTurnTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

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
    private static final Pattern OBSERVED_TRACE_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");

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
            LOG.warn("Could not create sessions directory {}: {}",
                    SafeLogFormatter.value(sessionsDir), SafeLogFormatter.throwableMessage(e));
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
            root.put("activeTaskContext", activeTaskContextToMap(data.activeTaskContext()));
            root.put("artifactGoal", artifactGoalToMap(data.artifactGoal()));
            root.put("turns", data.turns().stream()
                    .map(t -> Map.of("role", t.role(), "content", t.content(), "status", t.status()))
                    .toList());

            String json = sanitizedPrettyJson(root);
            Path file = fileFor(data.sessionId());
            Files.writeString(file, json);
            LOG.debug("Session saved: {} ({} turns)", SafeLogFormatter.value(file.getFileName()), data.turnCount());
        } catch (Exception e) {
            LOG.warn("Failed to save session {}: {}",
                    SafeLogFormatter.value(data.sessionId()), SafeLogFormatter.throwableMessage(e));
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
            ActiveTaskContext activeTaskContext = activeTaskContextFrom(root.get("activeTaskContext"));
            ArtifactGoal artifactGoal = artifactGoalFrom(root.get("artifactGoal"));

            @SuppressWarnings("unchecked")
            List<Map<String, String>> rawTurns =
                    (List<Map<String, String>>) root.getOrDefault("turns", List.of());

            List<SessionData.Turn> turns = rawTurns.stream()
                    .map(m -> new SessionData.Turn(
                            m.getOrDefault("role", ""),
                            m.getOrDefault("content", ""),
                            m.getOrDefault("status", "")))
                    .toList();

            return Optional.of(new SessionData(sid, workspace, sketch, turnCount, created, turns, model,
                    activeTaskContext, artifactGoal));
        } catch (Exception e) {
            LOG.warn("Failed to load session {}: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
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
            boolean traces = deleteTraceDirectory(sessionId);
            return snap || turns || traces;
        } catch (IOException e) {
            LOG.warn("Failed to delete session {}: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
            return false;
        }
    }

    // ── Per-turn structured durability (JSONL append-only) ───────────────

    @Override
    public void appendTurn(String sessionId, TurnRecord record) {
        if (sessionId == null || sessionId.isBlank() || record == null) return;
        try {
            writeTurn(sessionId, record);
        } catch (Exception e) {
            LOG.warn("Failed to append turn record for {}: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
        }
    }

    private void writeTurn(String sessionId, TurnRecord record) throws IOException {
        requireStoredUserRequestCoordinates(
                record.userInput(),
                record.policyTrace());
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
        row.put("traceId", record.traceId());
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

        String line = sanitizedCompactJson(row) + System.lineSeparator();
        Files.writeString(turnsFileFor(sessionId), line,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
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
                    LOG.warn("Skipping malformed turn line in {}: {}",
                            SafeLogFormatter.value(file.getFileName()), SafeLogFormatter.throwableMessage(lineErr));
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read turn log {}: {}",
                    SafeLogFormatter.value(file), SafeLogFormatter.throwableMessage(e));
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
        requireStoredUserRequestCoordinates(userInput, policyTrace);
        String traceId = str(row, "traceId");

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
                calls, reqd, grnt, deny, traceSummary, status, policyTrace, traceId);
    }

    private static Map<String, Object> activeTaskContextToMap(ActiveTaskContext context) {
        ActiveTaskContext safe = context == null ? ActiveTaskContext.none() : context;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", safe.schemaVersion());
        out.put("state", safe.state().name());
        out.put("kind", safe.kind().name());
        out.put("sourceTurnNumber", safe.sourceTurnNumber());
        out.put("sourceTraceId", safe.sourceTraceId());
        out.put("updatedTurnNumber", safe.updatedTurnNumber());
        out.put("expiresAfterTurnNumber", safe.expiresAfterTurnNumber());
        out.put("targets", safe.targets());
        out.put("operation", safe.operation().name());
        out.put("proposalSummary", safe.proposalSummary());
        out.put("previousOutcomeStatus", safe.previousOutcomeStatus());
        out.put("verifierFindings", safe.verifierFindings());
        out.put("requiredVerificationClaims", safe.requiredVerificationClaims().stream()
                .map(JsonSessionStore::requiredVerificationClaimToMap)
                .toList());
        out.put("staticWebRequirements", staticWebRequirementsToMap(safe.staticWebRequirements()));
        out.put("blockedReason", safe.blockedReason());
        out.put("suppressionReason", safe.suppressionReason());
        return out;
    }

    private static Map<String, Object> staticWebRequirementsToMap(StaticWebRequirements requirements) {
        StaticWebRequirements safe = requirements == null ? StaticWebRequirements.none() : requirements;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("requiredVisibleFacts", safe.requiredVisibleFacts());
        out.put("forbiddenArtifacts", safe.forbiddenArtifacts().stream().sorted().toList());
        return out;
    }

    private static Map<String, Object> requiredVerificationClaimToMap(
            ActiveTaskContext.RequiredVerificationClaim claim) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (claim == null) return out;
        out.put("id", claim.id());
        out.put("description", claim.description());
        out.put("proofKind", claim.proofKind());
        out.put("triggerSelector", claim.triggerSelector());
        out.put("outputSelector", claim.outputSelector());
        out.put("eventType", claim.eventType());
        return out;
    }

    private static ActiveTaskContext activeTaskContextFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return ActiveTaskContext.none();
        try {
            ActiveTaskContext.State state = enumValOrNull(ActiveTaskContext.State.class, map, "state");
            ActiveTaskContext.Kind kind = enumValOrNull(ActiveTaskContext.Kind.class, map, "kind");
            ActiveTaskContext.Operation operation = enumValOrNull(ActiveTaskContext.Operation.class, map, "operation");
            if (state == null || kind == null || operation == null) return ActiveTaskContext.none();
            return new ActiveTaskContext(
                    intValLoose(map, "schemaVersion"),
                    state,
                    kind,
                    intValLoose(map, "sourceTurnNumber"),
                    stringVal(map, "sourceTraceId", ""),
                    intValLoose(map, "updatedTurnNumber"),
                    intValLoose(map, "expiresAfterTurnNumber"),
                    stringList(map.get("targets")),
                    operation,
                    stringVal(map, "proposalSummary", ""),
                    stringVal(map, "previousOutcomeStatus", ""),
                    stringList(map.get("verifierFindings")),
                    requiredVerificationClaimsFrom(map.get("requiredVerificationClaims")),
                    staticWebRequirementsFrom(map.get("staticWebRequirements")),
                    stringVal(map, "blockedReason", ""),
                    stringVal(map, "suppressionReason", ""));
        } catch (Exception e) {
            return ActiveTaskContext.none();
        }
    }

    private static StaticWebRequirements staticWebRequirementsFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return StaticWebRequirements.none();
        return StaticWebRequirements.of(
                stringList(map.get("requiredVisibleFacts")),
                new java.util.LinkedHashSet<>(stringList(map.get("forbiddenArtifacts"))));
    }

    private static List<ActiveTaskContext.RequiredVerificationClaim> requiredVerificationClaimsFrom(Object raw) {
        if (!(raw instanceof List<?> values) || values.isEmpty()) return List.of();
        List<ActiveTaskContext.RequiredVerificationClaim> out = new java.util.ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map)) continue;
            ActiveTaskContext.RequiredVerificationClaim claim = new ActiveTaskContext.RequiredVerificationClaim(
                    stringVal(map, "id", ""),
                    stringVal(map, "description", ""),
                    stringVal(map, "proofKind", ""),
                    stringVal(map, "triggerSelector", ""),
                    stringVal(map, "outputSelector", ""),
                    stringVal(map, "eventType", ""));
            if (!claim.triggerSelector().isBlank() && !claim.outputSelector().isBlank()) {
                out.add(claim);
            }
        }
        return List.copyOf(out);
    }

    private static Map<String, Object> artifactGoalToMap(ArtifactGoal goal) {
        ArtifactGoal safe = goal == null ? ArtifactGoal.none() : goal;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("artifactKind", safe.artifactKind().name());
        out.put("operation", safe.operation().name());
        out.put("targets", safe.targets());
        out.put("verifierProfile", safe.verifierProfile());
        out.put("source", safe.source().name());
        return out;
    }

    private static ArtifactGoal artifactGoalFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) return ArtifactGoal.none();
        try {
            ArtifactGoal.ArtifactKind artifactKind = enumValOrNull(ArtifactGoal.ArtifactKind.class, map, "artifactKind");
            ActiveTaskContext.Operation operation = enumValOrNull(ActiveTaskContext.Operation.class, map, "operation");
            ArtifactGoal.Source source = enumValOrNull(ArtifactGoal.Source.class, map, "source");
            if (artifactKind == null || operation == null || source == null) return ArtifactGoal.none();
            return new ArtifactGoal(
                    artifactKind,
                    operation,
                    stringList(map.get("targets")),
                    stringVal(map, "verifierProfile", ""),
                    source);
        } catch (Exception e) {
            return ArtifactGoal.none();
        }
    }

    // ── Local turn trace v1 artifacts ─────────────────────────────────

    @Override
    public void saveTrace(String sessionId, LocalTurnTrace trace) {
        if (sessionId == null || sessionId.isBlank() || trace == null || trace.traceId().isBlank()) return;
        try {
            writeTrace(sessionId, trace);
        } catch (Exception e) {
            LOG.warn("Failed to save local turn trace for {}: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
        }
    }

    private void writeTrace(String sessionId, LocalTurnTrace trace) throws IOException {
        writeTrace(sessionId, trace, null);
    }

    private void writeTrace(
            String sessionId,
            LocalTurnTrace trace,
            String canonicalUserRequest
    ) throws IOException {
        requireExactLocalTraceEvidence(
                MAPPER.valueToTree(trace),
                canonicalUserRequest);
        String json = sanitizedTraceJson(trace, canonicalUserRequest);
        requireExactLocalTraceEvidence(
                MAPPER.readTree(json),
                canonicalUserRequest);
        Path dir = traceDirFor(sessionId);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(traceFileName(trace)), json);
    }

    @Override
    public TurnEnvelopePersistenceReceipt persistTurnEnvelope(
            String sessionId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {
        if (sessionId == null || sessionId.isBlank() || record == null) {
            return new TurnEnvelopePersistenceReceipt(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED);
        }
        String recordTraceId = record.traceId();
        boolean invalidIdentity = record.turnNumber() <= 0
                || (!recordTraceId.isBlank()
                    && !OBSERVED_TRACE_ID.matcher(recordTraceId).matches())
                || (trace == null && !recordTraceId.isBlank())
                || (trace != null && (trace.turnNumber() <= 0
                    || trace.turnNumber() != record.turnNumber()
                    || trace.traceId().isBlank()
                    || !OBSERVED_TRACE_ID.matcher(trace.traceId()).matches()
                    || !trace.traceId().equals(recordTraceId)));
        if (invalidIdentity) {
            LOG.warn("Rejected mismatched turn envelope for {}",
                    SafeLogFormatter.value(sessionId));
            return new TurnEnvelopePersistenceReceipt(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED);
        }
        try {
            requireStoredUserRequestCoordinates(
                    record.userInput(),
                    record.policyTrace());
            if (trace != null) {
                // Prove the original USER_REQUEST evidence against the original
                // persisted request before either side is sanitized. Redaction
                // may conservatively invalidate a proven coordinate pair, but
                // it must never turn an originally unbound pair into an
                // acceptable unknown-offset envelope.
                requireExactLocalTraceEvidence(
                        MAPPER.valueToTree(trace),
                        record.userInput());
                String candidateJson = sanitizedTraceJson(
                        trace,
                        record.userInput());
                requireExactLocalTraceEvidence(
                        MAPPER.readTree(candidateJson),
                        record.userInput());
            }
        } catch (Exception e) {
            LOG.warn("Rejected malformed turn envelope for {}: {}",
                    SafeLogFormatter.value(sessionId),
                    SafeLogFormatter.throwableMessage(e));
            return new TurnEnvelopePersistenceReceipt(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED);
        }

        boolean traceWritten = false;
        if (trace != null) {
            try {
                writeTrace(sessionId, trace, record.userInput());
                traceWritten = true;
            } catch (Exception e) {
                LOG.warn("Failed to save local turn trace for {}: {}",
                        SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
            }
        }

        TurnRecord row = traceWritten || trace == null
                ? record
                : withTraceId(record, "");
        boolean turnWritten = false;
        try {
            writeTurn(sessionId, row);
            turnWritten = true;
        } catch (Exception e) {
            LOG.warn("Failed to append turn record for {}: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
        }

        TurnEnvelopePersistenceReceipt.State state;
        if (traceWritten && turnWritten) {
            state = TurnEnvelopePersistenceReceipt.State.BOTH_WRITES_OBSERVED;
        } else if (traceWritten) {
            state = TurnEnvelopePersistenceReceipt.State.TRACE_WRITE_ONLY;
        } else if (turnWritten) {
            state = TurnEnvelopePersistenceReceipt.State.TURN_WRITE_ONLY;
        } else {
            state = TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED;
        }
        return new TurnEnvelopePersistenceReceipt(state);
    }

    private static TurnRecord withTraceId(TurnRecord record, String traceId) {
        return new TurnRecord(
                record.turnNumber(),
                record.timestamp(),
                record.durationMs(),
                record.userInput(),
                record.assistantText(),
                record.toolCalls(),
                record.approvalsRequired(),
                record.approvalsGranted(),
                record.approvalsDenied(),
                record.retrievalTraceSummary(),
                record.status(),
                record.policyTrace(),
                traceId);
    }

    @Override
    public Optional<LocalTurnTrace> loadTrace(String sessionId, String traceId) {
        if (sessionId == null || sessionId.isBlank() || traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        Path dir = traceDirFor(sessionId);
        if (!Files.isDirectory(dir)) return Optional.empty();
        Map<String, String> requestBindings = traceRequestBindings(sessionId);
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith("-" + sanitizeTraceId(traceId) + ".json"))
                    .sorted()
                    .map(path -> readTrace(path, requestBindings).filter(trace ->
                            traceId.equals(trace.traceId())
                                    && trace.turnNumber() > 0
                                    && path.getFileName().toString().equals(
                                            traceFileName(trace))))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        } catch (Exception e) {
            LOG.warn("Failed to load local turn trace {} for {}: {}",
                    SafeLogFormatter.value(traceId), SafeLogFormatter.value(sessionId),
                    SafeLogFormatter.throwableMessage(e));
            return Optional.empty();
        }
    }

    @Override
    public Optional<LocalTurnTrace> loadLatestTrace(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return Optional.empty();
        Path dir = traceDirFor(sessionId);
        if (!Files.isDirectory(dir)) return Optional.empty();
        Map<String, String> requestBindings = traceRequestBindings(sessionId);
        try (var stream = Files.list(dir)) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .map(path -> readTrace(path, requestBindings).filter(trace ->
                            !trace.traceId().isBlank()
                                    && trace.turnNumber() > 0
                                    && path.getFileName().toString().equals(
                                            traceFileName(trace))))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .findFirst();
        } catch (Exception e) {
            LOG.warn("Failed to load latest local turn trace for {}: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
            return Optional.empty();
        }
    }

    private Optional<LocalTurnTrace> readTrace(
            Path path,
            Map<String, String> requestBindings
    ) {
        try {
            JsonNode root = MAPPER.readTree(Files.readString(path));
            String traceId = root.path("traceId").isTextual()
                    ? root.path("traceId").asText()
                    : "";
            String canonicalRequest = requestBindings == null
                    ? null
                    : requestBindings.get(traceId);
            requireExactLocalTraceEvidence(root, canonicalRequest);
            return Optional.of(MAPPER.treeToValue(root, LocalTurnTrace.class));
        } catch (Exception e) {
            LOG.warn("Skipping malformed local trace {}: {}",
                    SafeLogFormatter.value(path.getFileName()), SafeLogFormatter.throwableMessage(e));
            return Optional.empty();
        }
    }

    private static String sanitizedPrettyJson(Object value) throws IOException {
        JsonNode root = MAPPER.valueToTree(value);
        sanitizeJsonTextNodes(root, false);
        return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(root);
    }

    private static String sanitizedTraceJson(
            LocalTurnTrace trace,
            String canonicalUserRequest
    ) throws IOException {
        JsonNode root = MAPPER.valueToTree(trace);
        sanitizeJsonTextNodes(root, false);
        if (canonicalUserRequest != null
                && !ProtectedContentPolicy.sanitizeText(canonicalUserRequest)
                .equals(canonicalUserRequest)) {
            invalidateLocalUserRequestCoordinates(root);
        }
        return MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root);
    }

    private static void invalidateLocalUserRequestCoordinates(JsonNode root) {
        JsonNode targets = root == null
                ? null
                : root.path("taskContract").path("rolefulTargets");
        if (targets == null || !targets.isArray()) return;
        for (JsonNode target : targets) {
            if (target instanceof ObjectNode objectTarget
                    && TargetSource.USER_REQUEST.name().equals(
                            objectTarget.path("source").asText())) {
                invalidateCoordinateFields(objectTarget);
            }
        }
    }

    private static String sanitizedCompactJson(Object value) throws IOException {
        JsonNode root = MAPPER.valueToTree(value);
        sanitizeJsonTextNodes(root, false);
        return MAPPER.writeValueAsString(root);
    }

    private static void sanitizeJsonTextNodes(
            JsonNode node,
            boolean inheritedCoordinateInvalidation
    ) {
        if (node == null) return;
        if (node instanceof ObjectNode objectNode) {
            boolean invalidateDescendantCoordinates =
                    inheritedCoordinateInvalidation;
            boolean invalidateOwnCoordinates =
                    inheritedCoordinateInvalidation;
            var fields = objectNode.fields();
            List<Map.Entry<String, JsonNode>> children =
                    new java.util.ArrayList<>();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> field = fields.next();
                JsonNode child = field.getValue();
                if (child != null && child.isTextual()) {
                    String original = child.asText();
                    String sanitized =
                            ProtectedContentPolicy.sanitizeText(original);
                    objectNode.put(field.getKey(), sanitized);
                    if (!sanitized.equals(original)) {
                        if ("userInput".equals(field.getKey())) {
                            invalidateDescendantCoordinates = true;
                        }
                        if ("sourceText".equals(field.getKey())) {
                            invalidateOwnCoordinates = true;
                        }
                    }
                } else {
                    children.add(Map.entry(field.getKey(), child));
                }
            }
            if (invalidateOwnCoordinates) {
                invalidateCoordinateFields(objectNode);
            }
            for (Map.Entry<String, JsonNode> child : children) {
                sanitizeJsonTextNodes(
                        child.getValue(),
                        invalidateDescendantCoordinates);
            }
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            for (int i = 0; i < arrayNode.size(); i++) {
                JsonNode child = arrayNode.get(i);
                if (child != null && child.isTextual()) {
                    arrayNode.set(i, MAPPER.getNodeFactory().textNode(
                            ProtectedContentPolicy.sanitizeText(child.asText())));
                } else {
                    sanitizeJsonTextNodes(
                            child,
                            inheritedCoordinateInvalidation);
                }
            }
        }
    }

    private Map<String, String> traceRequestBindings(String sessionId) {
        LinkedHashMap<String, String> bindings = new LinkedHashMap<>();
        java.util.HashSet<String> conflicts = new java.util.HashSet<>();
        for (TurnRecord record : loadTurns(sessionId)) {
            String traceId = record.traceId();
            if (traceId == null || traceId.isBlank() || conflicts.contains(traceId)) {
                continue;
            }
            String canonical = record.userInput() == null
                    ? ""
                    : record.userInput().strip();
            String previous = bindings.putIfAbsent(traceId, canonical);
            if (previous != null && !previous.equals(canonical)) {
                bindings.remove(traceId);
                conflicts.add(traceId);
            }
        }
        return Map.copyOf(bindings);
    }

    private static void invalidateCoordinateFields(ObjectNode objectNode) {
        if (objectNode == null
                || !objectNode.has("startOffset")
                && !objectNode.has("authorityEndOffset")
                && !objectNode.has("endOffset")) {
            return;
        }
        objectNode.put("startOffset", -1);
        objectNode.put("authorityEndOffset", -1);
        objectNode.put("endOffset", -1);
    }

    private static void requireExactLocalTraceEvidence(
            JsonNode root,
            String canonicalUserRequest
    ) {
        JsonNode targets = root == null
                ? null
                : root.path("taskContract").path("rolefulTargets");
        if (targets == null || targets.isMissingNode() || targets.isNull()) {
            return;
        }
        if (!targets.isArray()) {
            throw new IllegalArgumentException(
                    "local trace rolefulTargets must be an array");
        }
        for (JsonNode target : targets) {
            if (target == null || !target.isObject()) {
                throw new IllegalArgumentException(
                        "local trace roleful target must be an object");
            }
            JsonNode pathNode = target.get("path");
            if (pathNode == null
                    || !pathNode.isTextual()
                    || pathNode.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "local trace roleful target path must be a string");
            }
            String path = TargetRef.normalizePath(pathNode.asText());
            JsonNode sourceNode = target.get("source");
            if (sourceNode == null
                    || !sourceNode.isTextual()
                    || sourceNode.asText().isBlank()) {
                throw new IllegalArgumentException(
                        "local trace roleful target source must be a named string");
            }
            String source = sourceNode.asText();
            try {
                TargetSource.valueOf(source);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "local trace roleful target source must be a named TargetSource",
                        e);
            }
            JsonNode confidenceNode = target.get("confidence");
            if (confidenceNode == null || !confidenceNode.isNumber()) {
                throw new IllegalArgumentException(
                        "local trace roleful target confidence must be numeric");
            }
            double confidence = confidenceNode.doubleValue();
            if (!Double.isFinite(confidence)
                    || confidence < 0.0
                    || confidence > 1.0) {
                throw new IllegalArgumentException(
                        "local trace roleful target confidence must be finite "
                                + "and between 0.0 and 1.0");
            }
            JsonNode sourceTextNode = target.get("sourceText");
            if (sourceTextNode == null || !sourceTextNode.isTextual()) {
                throw new IllegalArgumentException(
                        "local trace roleful target sourceText must be a string");
            }
            String sourceText = sourceTextNode.asText();
            boolean hasStart = target.has("startOffset");
            boolean hasAuthorityEnd = target.has("authorityEndOffset");
            boolean hasEnd = target.has("endOffset");
            if (!hasStart && !hasAuthorityEnd && !hasEnd) {
                TaskIntentEvidence.requireDurableShape(
                        path,
                        source,
                        IntentDerivation.UNKNOWN_OFFSET,
                        IntentDerivation.UNKNOWN_OFFSET,
                        IntentDerivation.UNKNOWN_OFFSET,
                        sourceText,
                        confidence);
                if (target instanceof ObjectNode objectTarget) {
                    objectTarget.put(
                            "startOffset",
                            IntentDerivation.UNKNOWN_OFFSET);
                    objectTarget.put(
                            "authorityEndOffset",
                            IntentDerivation.UNKNOWN_OFFSET);
                    objectTarget.put(
                            "endOffset",
                            IntentDerivation.UNKNOWN_OFFSET);
                }
                continue;
            }
            if (hasStart != hasEnd || hasAuthorityEnd && !hasStart) {
                throw new IllegalArgumentException(
                        "local trace source offsets must have a complete outer span");
            }
            int start = exactJsonInt(target.get("startOffset"), "startOffset");
            int authorityEnd = hasAuthorityEnd
                    ? exactJsonInt(
                            target.get("authorityEndOffset"),
                            "authorityEndOffset")
                    : IntentDerivation.UNKNOWN_OFFSET;
            int end = exactJsonInt(target.get("endOffset"), "endOffset");
            if (!hasAuthorityEnd && target instanceof ObjectNode objectTarget) {
                objectTarget.put(
                        "authorityEndOffset",
                        IntentDerivation.UNKNOWN_OFFSET);
            }
            TaskIntentEvidence.requireDurableShape(
                    path,
                    source,
                    start,
                    authorityEnd,
                    end,
                    sourceText,
                    confidence);
            if (start >= 0) {
                if (canonicalUserRequest == null) {
                    throw new IllegalArgumentException(
                            "local trace USER_REQUEST coordinates require a "
                                    + "matching persisted turn");
                }
                TaskIntentEvidence.requireCanonicalDurableSpan(
                        canonicalUserRequest,
                        path,
                        source,
                        start,
                        authorityEnd,
                        end,
                        sourceText);
            }
        }
    }

    private static int exactJsonInt(JsonNode value, String field) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToInt()) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON 32-bit integer");
        }
        return value.intValue();
    }

    private static void requireStoredUserRequestCoordinates(
            String userInput,
            TurnPolicyTrace policyTrace
    ) {
        if (policyTrace == null || policyTrace.rolefulTargets().isEmpty()) return;
        String canonical = userInput == null ? "" : userInput.strip();
        for (TurnPolicyTrace.RolefulTarget target : policyTrace.rolefulTargets()) {
            TaskIntentEvidence.requireDurableShape(
                    target.path(),
                    target.source(),
                    target.startOffset(),
                    target.authorityEndOffset(),
                    target.endOffset(),
                    target.sourceText(),
                    target.confidence());
            TaskIntentEvidence.requireCanonicalDurableSpan(
                    canonical,
                    target.path(),
                    target.source(),
                    target.startOffset(),
                    target.authorityEndOffset(),
                    target.endOffset(),
                    target.sourceText());
        }
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
        out.put("classificationReason", safe.classificationReason());
        List<Map<String, Object>> rolefulTargets = new java.util.ArrayList<>();
        for (TurnPolicyTrace.RolefulTarget target : safe.rolefulTargets()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("path", target.path());
            row.put("role", target.role());
            row.put("source", target.source());
            row.put("reason", target.reason());
            row.put("sourceText", target.sourceText());
            row.put("confidence", target.confidence());
            row.put("startOffset", target.startOffset());
            row.put("authorityEndOffset", target.authorityEndOffset());
            row.put("endOffset", target.endOffset());
            rolefulTargets.add(row);
        }
        out.put("rolefulTargets", rolefulTargets);
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
                stringList(map.get("blocks")),
                stringVal(map, "classificationReason", ""),
                rolefulTargetsFrom(map.get("rolefulTargets")));
    }

    private static List<TurnPolicyTrace.RolefulTarget> rolefulTargetsFrom(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("rolefulTargets must be an array when present");
        }
        List<TurnPolicyTrace.RolefulTarget> out = new java.util.ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("rolefulTargets entries must be objects");
            }
            Object rawRole = map.get("role");
            if (!(rawRole instanceof String role) || role.isBlank()) {
                throw new IllegalArgumentException("roleful target role must be a named string");
            }
            TargetRole.valueOf(role);
            Object rawSource = map.get("source");
            if (!(rawSource instanceof String source) || source.isBlank()) {
                throw new IllegalArgumentException(
                        "roleful target source must be a named string");
            }
            TargetSource.valueOf(source);
            Object rawConfidence = map.get("confidence");
            if (!(rawConfidence instanceof Number number)) {
                throw new IllegalArgumentException(
                        "roleful target confidence must be numeric");
            }
            double confidence = number.doubleValue();
            if (!Double.isFinite(confidence)
                    || confidence < 0.0
                    || confidence > 1.0) {
                throw new IllegalArgumentException(
                        "roleful target confidence must be finite and "
                                + "between 0.0 and 1.0");
            }
            Object rawSourceText = map.get("sourceText");
            if (!(rawSourceText instanceof String sourceText)) {
                throw new IllegalArgumentException(
                        "roleful target sourceText must be a string");
            }
            boolean hasStart = map.containsKey("startOffset");
            boolean hasAuthorityEnd = map.containsKey("authorityEndOffset");
            boolean hasEnd = map.containsKey("endOffset");
            if (hasStart != hasEnd || hasAuthorityEnd && !hasStart) {
                throw new IllegalArgumentException(
                        "roleful target source offsets must have a complete outer span");
            }
            int start = hasStart
                    ? exactInt(map.get("startOffset"), "startOffset")
                    : IntentDerivation.UNKNOWN_OFFSET;
            int end = hasEnd
                    ? exactInt(map.get("endOffset"), "endOffset")
                    : IntentDerivation.UNKNOWN_OFFSET;
            int authorityEnd = hasAuthorityEnd
                    ? exactInt(
                            map.get("authorityEndOffset"),
                            "authorityEndOffset")
                    : IntentDerivation.UNKNOWN_OFFSET;
            String path = stringVal(map, "path", "");
            TaskIntentEvidence.requireDurableShape(
                    path,
                    source,
                    start,
                    authorityEnd,
                    end,
                    sourceText,
                    confidence);
            out.add(new TurnPolicyTrace.RolefulTarget(
                    path,
                    role,
                    source,
                    stringVal(map, "reason", ""),
                    sourceText,
                    confidence,
                    start,
                    authorityEnd,
                    end));
        }
        return out;
    }

    private static String stringVal(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private static boolean boolVal(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value instanceof Boolean b && b;
    }

    private static int intValLoose(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number n) return n.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (Exception e) { return 0; }
    }

    private static int exactInt(Object value, String key) {
        if (!(value instanceof Number)) {
            throw new IllegalArgumentException(key + " must be an exact integer");
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException malformed) {
            throw new IllegalArgumentException(
                    key + " must be an exact 32-bit integer",
                    malformed);
        }
    }

    private static <E extends Enum<E>> E enumValOrNull(Class<E> enumType, Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value == null) return null;
        try { return Enum.valueOf(enumType, String.valueOf(value)); }
        catch (Exception e) { return null; }
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
     *
     * <p>Since T799 this is the <em>workspace id</em>: the listing prefix
     * for {@link #listSessions} and the key for per-workspace artifacts
     * (checkpoints, trace metadata). New session files are written under
     * {@link #newSessionInstanceId} so one workspace can hold many
     * sessions; legacy bare-hash files remain loadable.
     */
    public static String sessionIdFor(Path workspace) {
        return Hash.sha1Hex(workspace.toAbsolutePath().normalize().toString());
    }

    private static final java.time.format.DateTimeFormatter INSTANCE_ID_TIMESTAMP =
            java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")
                    .withZone(java.time.ZoneOffset.UTC);

    /**
     * Mint the storage id for a new session of this workspace:
     * {@code <ws-hash>-<UTC yyyyMMddHHmmss>}. The clock is a parameter so
     * tests stay deterministic. The hash prefix is fixed-length 40-hex
     * (no {@code -}), so prefix matching against {@link #sessionIdFor}
     * is unambiguous across workspaces.
     */
    public static String newSessionInstanceId(Path workspace, Instant createdAt) {
        return sessionIdFor(workspace) + "-" + INSTANCE_ID_TIMESTAMP.format(createdAt);
    }

    @Override
    public List<SessionSummary> listSessions(String workspaceId) {
        if (workspaceId == null || workspaceId.isBlank()) return List.of();
        if (!Files.isDirectory(sessionsDir)) return List.of();

        java.util.Set<String> snapshotIds = new java.util.LinkedHashSet<>();
        java.util.Set<String> turnLogIds = new java.util.LinkedHashSet<>();
        try (var stream = Files.list(sessionsDir)) {
            for (Path path : (Iterable<Path>) stream::iterator) {
                String name = path.getFileName().toString();
                if (name.endsWith(TURNS_SUFFIX)) {
                    String id = name.substring(0, name.length() - TURNS_SUFFIX.length());
                    if (belongsTo(id, workspaceId)) turnLogIds.add(id);
                } else if (name.endsWith(".json")) {
                    String id = name.substring(0, name.length() - ".json".length());
                    if (belongsTo(id, workspaceId)) snapshotIds.add(id);
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to list sessions in {}: {}",
                    SafeLogFormatter.value(sessionsDir), SafeLogFormatter.throwableMessage(e));
            return List.of();
        }

        List<SessionSummary> out = new java.util.ArrayList<>();
        for (String id : snapshotIds) {
            out.add(snapshotSummary(id, workspaceId, turnLogIds.contains(id)));
        }
        for (String id : turnLogIds) {
            if (!snapshotIds.contains(id)) out.add(turnLogSummary(id, workspaceId));
        }
        out.sort(Comparator.comparing(SessionSummary::createdAt).reversed()
                .thenComparing(SessionSummary::sessionId, Comparator.reverseOrder()));
        return List.copyOf(out);
    }

    private static boolean belongsTo(String sessionId, String workspaceId) {
        return sessionId.equals(workspaceId) || sessionId.startsWith(workspaceId + "-");
    }

    /** Tolerant snapshot read: unparseable metadata lists as EPOCH, never throws. */
    private SessionSummary snapshotSummary(String sessionId, String workspaceId, boolean hasTurnLog) {
        Instant created = Instant.EPOCH;
        int turnCount = 0;
        String model = "";
        try {
            Map<String, Object> root = MAPPER.readValue(
                    Files.readString(fileFor(sessionId)), new TypeReference<>() {});
            created = parseInstantOrEpoch(root.get("createdAt"));
            turnCount = intVal(root, "turnCount");
            model = str(root, "model");
        } catch (Exception e) {
            LOG.warn("Listing session {} without metadata: {}",
                    SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
        }
        return new SessionSummary(sessionId, created, turnCount, model,
                true, hasTurnLog, sessionId.equals(workspaceId));
    }

    /** Orphan crash log (no snapshot): synthesize createdAt/turnCount from the rows. */
    private SessionSummary turnLogSummary(String sessionId, String workspaceId) {
        List<TurnRecord> turns = loadTurns(sessionId);
        Instant created = turns.stream()
                .map(TurnRecord::timestamp)
                .filter(java.util.Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(Instant.EPOCH);
        return new SessionSummary(sessionId, created, turns.size(), "",
                false, true, sessionId.equals(workspaceId));
    }

    private static Instant parseInstantOrEpoch(Object v) {
        try { return Instant.parse(String.valueOf(v)); }
        catch (Exception e) { return Instant.EPOCH; }
    }

    /** The directory where session files are stored. */
    public Path sessionsDir() {
        return sessionsDir;
    }

    // ── Internal ──────────────────────────────────────────────────────

    private static final String TURNS_SUFFIX = ".turns.jsonl";

    private Path fileFor(String sessionId) {
        return sessionsDir.resolve(sessionId + ".json");
    }

    /** Companion JSONL file for per-turn append-only durability. */
    private Path turnsFileFor(String sessionId) {
        return sessionsDir.resolve(sessionId + TURNS_SUFFIX);
    }

    private Path traceDirFor(String sessionId) {
        return sessionsDir.resolve("traces").resolve(sessionId);
    }

    private String traceFileName(LocalTurnTrace trace) {
        return "%06d-%s.json".formatted(trace.turnNumber(), sanitizeTraceId(trace.traceId()));
    }

    private static String sanitizeTraceId(String traceId) {
        if (traceId == null || traceId.isBlank()) return "trace";
        return traceId.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private boolean deleteTraceDirectory(String sessionId) throws IOException {
        Path dir = traceDirFor(sessionId);
        if (!Files.exists(dir)) return false;
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    LOG.warn("Failed to delete trace artifact {}: {}",
                            SafeLogFormatter.value(path), SafeLogFormatter.throwableMessage(e));
                }
            });
        }
        return true;
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

