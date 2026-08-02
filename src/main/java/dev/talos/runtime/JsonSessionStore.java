package dev.talos.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.core.io.AtomicFileWriter;
import dev.talos.core.security.NoFollowPathPolicy;
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
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * File-backed {@link SessionStore} that persists session state as JSON
 * under {@code ~/.talos/sessions/}.
 *
 * <p>Each run reserves an entropy-bearing instance ID below the workspace's
 * SHA-1 prefix. Legacy bare-hash and timestamp-only records remain readable.
 * Snapshot write errors remain nonfatal to the user turn and are logged;
 * ownership and reservation failures fail visibly before mutation. Load
 * returns empty on any I/O or parse failure.
 *
 * <p>Read methods are self-contained. Mutations require a
 * {@link SessionWriterLease}; the lease serializes this JVM and holds the
 * stable cross-process sidecar lock for its complete lifetime.
 */
public final class JsonSessionStore implements SessionStore, AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(JsonSessionStore.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern OBSERVED_TRACE_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Pattern SAFE_SESSION_ID =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,199}");
    private static final Pattern WINDOWS_DEVICE_SESSION_ID = Pattern.compile(
            "(?i)(?:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(?:\\..*)?");
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    private static final int SESSION_ENTROPY_BYTES = 16;
    private static final int MAX_RESERVATION_ATTEMPTS = 32;
    private static final String OWNERSHIP_DIRECTORY = ".ownership";
    private static final String RESERVATION_SUFFIX = ".session.reserve";
    private static final String WRITER_LOCK_SUFFIX = ".session.writer.lock";
    private static final Object JVM_LOCK_MUTEX = new Object();
    private static final Map<Path, SessionWriterLease> JVM_ACTIVE_LEASES =
            new java.util.HashMap<>();

    private final Path sessionsDir;
    private final AtomicFileWriter atomicFileWriter;
    private final Supplier<byte[]> entropySource;
    private final Map<String, SessionWriterLease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Default location: {@code ~/.talos/sessions/}. */
    public JsonSessionStore() {
        this(Path.of(System.getProperty("user.home"), ".talos", "sessions"));
    }

    /** Custom directory (useful for testing with {@code @TempDir}). */
    public JsonSessionStore(Path sessionsDir) {
        this(sessionsDir, new AtomicFileWriter(), JsonSessionStore::secureEntropy);
    }

    JsonSessionStore(
            Path sessionsDir,
            AtomicFileWriter atomicFileWriter,
            Supplier<byte[]> entropySource
    ) {
        this.sessionsDir = java.util.Objects.requireNonNull(
                sessionsDir, "sessionsDir").toAbsolutePath().normalize();
        this.atomicFileWriter = java.util.Objects.requireNonNull(
                atomicFileWriter, "atomicFileWriter");
        this.entropySource = java.util.Objects.requireNonNull(
                entropySource, "entropySource");
        try {
            NoFollowPathPolicy.createUnlinkedDirectories(sessionsDir);
        } catch (IOException e) {
            LOG.warn("Could not create sessions directory {}: {}",
                    SafeLogFormatter.value(sessionsDir), SafeLogFormatter.throwableMessage(e));
        }
    }

    // ── SessionStore contract ─────────────────────────────────────────

    @Override
    public void save(SessionData data) {
        if (data == null || data.sessionId().isBlank()) return;
        requireSafeSessionId(data.sessionId());
        throw leaseRequired(data.sessionId());
    }

    void save(SessionWriterLease lease, SessionData data) {
        if (data == null || data.sessionId().isBlank()) return;
        lease.underLock(this, data.sessionId(), () -> {
            writeSnapshot(data);
            return null;
        });
    }

    private void writeSnapshot(SessionData data) {
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
            Path file = mutationGuard().requireRegularFileTarget(fileFor(data.sessionId()));
            atomicFileWriter.replaceCurrent(file, json.getBytes(StandardCharsets.UTF_8));
            LOG.debug("Session saved: {} ({} turns)", SafeLogFormatter.value(file.getFileName()), data.turnCount());
        } catch (Exception e) {
            LOG.warn("Failed to save session {}: {}",
                    SafeLogFormatter.value(data.sessionId()), SafeLogFormatter.throwableMessage(e));
        }
    }

    @Override
    public Optional<SessionData> load(String sessionId) {
        if (!safeSessionId(sessionId)) return Optional.empty();
        Path file = fileFor(sessionId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();

        try {
            Map<String, Object> root = MAPPER.readValue(
                    NoFollowPathPolicy.readString(file, StandardCharsets.UTF_8),
                    new TypeReference<>() {});

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
        requireSafeSessionId(sessionId);
        throw leaseRequired(sessionId);
    }

    boolean delete(SessionWriterLease lease, String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return false;
        return lease.underLock(this, sessionId, () -> deleteOwnedSession(sessionId));
    }

    private boolean deleteOwnedSession(String sessionId) {
        try {
            NoFollowPathPolicy.GuardedRoot guard = mutationGuard();
            // Authenticate the entire delete set before removing any entry. A
            // linked trace descendant must not erase a valid snapshot/log and
            // only then report failure.
            Path snapshot = regularIfPresent(guard, fileFor(sessionId));
            Path turnLog = regularIfPresent(guard, turnsFileFor(sessionId));
            List<NoFollowPathPolicy.TreeEntry> traceEntries =
                    traceEntriesIfPresent(guard, sessionId);

            boolean snap = snapshot != null && Files.deleteIfExists(snapshot);
            boolean turns = turnLog != null && Files.deleteIfExists(turnLog);
            boolean traces = false;
            for (int index = traceEntries.size() - 1; index >= 0; index--) {
                traces |= Files.deleteIfExists(traceEntries.get(index).path());
            }
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
        requireSafeSessionId(sessionId);
        throw leaseRequired(sessionId);
    }

    void appendTurn(SessionWriterLease lease, String sessionId, TurnRecord record) {
        if (sessionId == null || sessionId.isBlank() || record == null) return;
        lease.underLock(this, sessionId, () -> {
            appendOwnedTurn(lease, sessionId, record);
            return null;
        });
    }

    private void appendOwnedTurn(
            SessionWriterLease lease,
            String sessionId,
            TurnRecord record
    ) {
        try {
            writeTurn(lease, sessionId, record);
        } catch (IOException failure) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.PERSISTENCE_IO,
                    "Session turn log append failed",
                    failure);
        }
    }

    private void writeTurn(
            SessionWriterLease lease,
            String sessionId,
            TurnRecord record
    ) throws IOException {
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

        byte[] line = (sanitizedCompactJson(row) + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            Path target = mutationGuard().requireRegularFileTarget(turnsFileFor(sessionId));
            requireCompleteTurnLogTail(lease, target);
            try (FileChannel channel = FileChannel.open(
                    target,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                    LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer source = ByteBuffer.wrap(line);
            while (source.hasRemaining()) {
                int written = channel.write(source);
                if (written <= 0) throw new IOException("Turn log append made no progress");
            }
            channel.force(true);
            }
        } catch (SessionWriterUnavailableException failure) {
            throw failure;
        } catch (IOException failure) {
            lease.poison();
            throw failure;
        }
    }

    /**
     * A crash may leave a final JSON fragment without its canonical LF commit
     * marker. Never append a newly acknowledged row behind that fragment: it
     * would make the new row unreadable too. T1163 owns any later recovery or
     * truncation policy; this boundary only detects the condition and stops.
     */
    private static void requireCompleteTurnLogTail(
            SessionWriterLease lease,
            Path target
    ) throws IOException {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        try (FileChannel existing = FileChannel.open(
                target,
                StandardOpenOption.READ,
                LinkOption.NOFOLLOW_LINKS)) {
            long size = existing.size();
            if (size == 0L) return;
            ByteBuffer last = ByteBuffer.allocate(1);
            existing.position(size - 1L);
            if (existing.read(last) != 1 || last.array()[0] != (byte) '\n') {
                lease.poison();
                throw new SessionWriterUnavailableException(
                        SessionWriterUnavailableException.Reason.INCOMPLETE_TURN_LOG,
                        "Session turn log ends with an incomplete record");
            }
        }
    }

    @Override
    public List<TurnRecord> loadTurns(String sessionId) {
        if (!safeSessionId(sessionId)) return List.of();
        Path file = turnsFileFor(sessionId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return List.of();
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
        try {
            byte[] bytes = NoFollowPathPolicy.readAllBytes(file);
            int completeLength = 0;
            for (int index = bytes.length - 1; index >= 0; index--) {
                if (bytes[index] == (byte) '\n') {
                    completeLength = index + 1;
                    break;
                }
            }
            if (completeLength == 0) return List.of();
            String complete = decoder.decode(ByteBuffer.wrap(bytes, 0, completeLength)).toString();
            for (String rawLine : complete.split("\n", -1)) {
                String line = rawLine.endsWith("\r")
                        ? rawLine.substring(0, rawLine.length() - 1)
                        : rawLine;
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
        requireSafeSessionId(sessionId);
        throw leaseRequired(sessionId);
    }

    void saveTrace(
            SessionWriterLease lease,
            String sessionId,
            LocalTurnTrace trace
    ) {
        if (sessionId == null || sessionId.isBlank()
                || trace == null || trace.traceId().isBlank()) return;
        lease.underLock(this, sessionId, () -> {
            saveOwnedTrace(sessionId, trace);
            return null;
        });
    }

    private void saveOwnedTrace(String sessionId, LocalTurnTrace trace) {
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
        NoFollowPathPolicy.GuardedRoot guard = mutationGuard();
        guard.createDirectories(dir);
        Path target = guard.requireRegularFileTarget(dir.resolve(traceFileName(trace)));
        atomicFileWriter.replaceCurrent(target, json.getBytes(StandardCharsets.UTF_8));
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
        requireSafeSessionId(sessionId);
        throw leaseRequired(sessionId);
    }

    TurnEnvelopePersistenceReceipt persistTurnEnvelope(
            SessionWriterLease lease,
            String sessionId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {
        if (sessionId == null || sessionId.isBlank() || record == null) {
            return new TurnEnvelopePersistenceReceipt(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED);
        }
        return lease.underLock(
                this,
                sessionId,
                () -> persistOwnedTurnEnvelope(lease, sessionId, record, trace));
    }

    private TurnEnvelopePersistenceReceipt persistOwnedTurnEnvelope(
            SessionWriterLease lease,
            String sessionId,
            TurnRecord record,
            LocalTurnTrace trace
    ) {
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
            writeTurn(lease, sessionId, row);
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
        if (!safeSessionId(sessionId) || traceId == null || traceId.isBlank()) {
            return Optional.empty();
        }
        Path dir = traceDirFor(sessionId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Map<String, String> requestBindings = traceRequestBindings(sessionId);
        try (var stream = Files.list(NoFollowPathPolicy.requireUnlinkedDirectory(dir))) {
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
        if (!safeSessionId(sessionId)) return Optional.empty();
        Path dir = traceDirFor(sessionId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Map<String, String> requestBindings = traceRequestBindings(sessionId);
        try (var stream = Files.list(NoFollowPathPolicy.requireUnlinkedDirectory(dir))) {
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
            JsonNode root = MAPPER.readTree(
                    NoFollowPathPolicy.readString(path, StandardCharsets.UTF_8));
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

    // ── Session allocation and writer ownership ──────────────────────

    /**
     * Atomically reserves a fresh storage identity and acquires its exclusive
     * writer lock. Reservation artifacts are permanent collision tombstones;
     * they are never treated as user sessions.
     */
    public SessionWriterLease reserveNewSession(Path workspace, Instant createdAt) {
        if (workspace == null || createdAt == null) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.INVALID_SESSION_ID,
                    "Workspace and creation time are required for session allocation");
        }
        synchronized (JVM_LOCK_MUTEX) {
            requireStoreOpen();
            NoFollowPathPolicy.GuardedRoot guard;
            try {
                guard = mutationGuard();
            } catch (IOException failure) {
                throw new SessionWriterUnavailableException(
                        SessionWriterUnavailableException.Reason.RESERVATION_IO,
                        "Session namespace is unavailable",
                        failure);
            }
            for (int attempt = 0; attempt < MAX_RESERVATION_ATTEMPTS; attempt++) {
                byte[] entropy = requireEntropy(entropySource.get());
                String candidate = candidateSessionInstanceId(workspace, createdAt, entropy);
                requireSafeSessionId(candidate);
                try {
                    Path reservation = createReservation(guard, candidate);
                    return acquireSessionWriterLocked(candidate, reservation);
                } catch (FileAlreadyExistsException collision) {
                    // A permanent reservation already owns this exact identity.
                } catch (SessionWriterUnavailableException failure) {
                    throw failure;
                } catch (IOException failure) {
                    throw new SessionWriterUnavailableException(
                            SessionWriterUnavailableException.Reason.RESERVATION_IO,
                            "Session reservation failed",
                            failure);
                }
            }
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.RESERVATION_EXHAUSTED,
                    "Session reservation collision limit was exhausted");
        }
    }

    /**
     * Acquire the sole writer capability for an explicit ID. The first open
     * atomically reserves that exact safe filename; later opens authenticate
     * the retained marker before taking its stable writer lock. This also
     * gives legacy IDs the same cross-process ownership boundary without
     * rewriting their payload files.
     */
    public SessionWriterLease acquireSessionWriter(String sessionId) {
        requireSafeSessionId(sessionId);
        synchronized (JVM_LOCK_MUTEX) {
            requireStoreOpen();
            try {
                NoFollowPathPolicy.GuardedRoot guard = mutationGuard();
                Path reservation = ensureExplicitReservation(guard, sessionId);
                return acquireSessionWriterLocked(sessionId, reservation);
            } catch (SessionWriterUnavailableException failure) {
                throw failure;
            } catch (IOException failure) {
                throw new SessionWriterUnavailableException(
                        SessionWriterUnavailableException.Reason.RESERVATION_IO,
                        "Explicit session reservation failed",
                        failure);
            }
        }
    }

    private SessionWriterLease acquireSessionWriterLocked(
            String sessionId,
            Path reservationPath
    ) {
        requireSafeSessionId(sessionId);
        requireStoreOpen();
        requireReservationMarker(reservationPath, sessionId);
        if (activeLeases.containsKey(sessionId)) throw writerBusy();

        Path lockPath;
        FileChannel channel = null;
        FileLock lock = null;
        try {
            NoFollowPathPolicy.GuardedRoot guard = mutationGuard();
            lockPath = guard.requireRegularFileTarget(writerLockPathFor(sessionId));
            lockPath = lockPath.getParent().toRealPath()
                    .resolve(lockPath.getFileName()).normalize();
            if (JVM_ACTIVE_LEASES.containsKey(lockPath)) throw writerBusy();
            channel = FileChannel.open(
                    lockPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
            try {
                lock = channel.tryLock(0L, 1L, false);
            } catch (OverlappingFileLockException overlapping) {
                throw writerBusy();
            }
            if (lock == null) throw writerBusy();

            SessionWriterLease lease = new SessionWriterLease(
                    this, sessionId, reservationPath, lockPath, channel, lock);
            activeLeases.put(sessionId, lease);
            JVM_ACTIVE_LEASES.put(lockPath, lease);
            return lease;
        } catch (SessionWriterUnavailableException failure) {
            closeAcquisition(channel, lock);
            throw failure;
        } catch (IOException failure) {
            closeAcquisition(channel, lock);
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.LOCK_IO,
                    "Session writer lock could not be acquired",
                    failure);
        }
    }

    void requireMatchingLease(SessionWriterLease lease, String sessionId) {
        if (closed.get()) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.STORE_CLOSED,
                    "Session store is closed");
        }
        if (lease == null) throw leaseRequired(sessionId);
        if (!lease.belongsTo(this)) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.FOREIGN_LEASE,
                    "Session writer lease belongs to another store");
        }
        if (!lease.owns(sessionId)) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.WRONG_SESSION,
                    "Session writer lease does not own the requested session");
        }
        if (lease.closed()) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.LEASE_CLOSED,
                    "Session writer lease is closed");
        }
        if (lease.poisoned()) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.LEASE_POISONED,
                    "Session writer lease is poisoned after an uncertain write");
        }
        if (!lease.lockValid() || activeLeases.get(sessionId) != lease) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.LEASE_CLOSED,
                    "Session writer lease is no longer active");
        }
    }

    void release(SessionWriterLease lease) {
        if (lease == null) return;
        synchronized (JVM_LOCK_MUTEX) {
            IOException failure = null;
            try {
                lease.releaseResources();
            } catch (IOException releaseFailure) {
                failure = releaseFailure;
            } finally {
                activeLeases.remove(lease.sessionId(), lease);
                JVM_ACTIVE_LEASES.remove(lease.lockPath(), lease);
            }
            if (failure != null) {
                throw new SessionWriterUnavailableException(
                        SessionWriterUnavailableException.Reason.LOCK_IO,
                        "Failed to release the session writer lease",
                        failure);
            }
        }
    }

    @Override
    public void close() {
        List<SessionWriterLease> leases;
        synchronized (JVM_LOCK_MUTEX) {
            if (!closed.compareAndSet(false, true)) return;
            leases = List.copyOf(activeLeases.values());
        }
        RuntimeException failure = null;
        for (SessionWriterLease lease : leases) {
            try {
                lease.close();
            } catch (RuntimeException closeFailure) {
                if (failure == null) failure = closeFailure;
                else failure.addSuppressed(closeFailure);
            }
        }
        if (failure != null) throw failure;
    }

    private void requireStoreOpen() {
        if (closed.get()) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.STORE_CLOSED,
                    "Session store is closed");
        }
    }

    @Override
    public Optional<Path> rawTurnLogPath(String sessionId) {
        if (!safeSessionId(sessionId)) return Optional.empty();
        Path path = turnsFileFor(sessionId);
        try {
            return Files.exists(path, LinkOption.NOFOLLOW_LINKS)
                    ? Optional.of(NoFollowPathPolicy.requireUnlinkedRegularFile(path))
                    : Optional.of(path);
        } catch (IOException unsafe) {
            return Optional.empty();
        }
    }

    private NoFollowPathPolicy.GuardedRoot mutationGuard() throws IOException {
        Path root = NoFollowPathPolicy.createUnlinkedDirectories(sessionsDir);
        NoFollowPathPolicy.GuardedRoot guard = NoFollowPathPolicy.bindExistingDirectory(root);
        guard.createDirectories(ownershipDir());
        return guard;
    }

    private Path ownershipDir() {
        return sessionsDir.resolve(OWNERSHIP_DIRECTORY);
    }

    private Path reservationPathFor(String sessionId) {
        return ownershipDir().resolve(sessionId + RESERVATION_SUFFIX);
    }

    private Path writerLockPathFor(String sessionId) {
        return ownershipDir().resolve(sessionId + WRITER_LOCK_SUFFIX);
    }

    private Path createReservation(
            NoFollowPathPolicy.GuardedRoot guard,
            String sessionId
    ) throws IOException {
        Path reservation = guard.requireRegularFileTarget(reservationPathFor(sessionId));
        byte[] marker = reservationMarker(sessionId);
        try (FileChannel channel = FileChannel.open(
                reservation,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer source = ByteBuffer.wrap(marker);
            while (source.hasRemaining()) {
                int written = channel.write(source);
                if (written <= 0) {
                    throw new IOException("Session reservation made no progress");
                }
            }
            channel.force(true);
        }
        AtomicFileWriter.synchronizeDirectory(reservation.getParent());
        requireReservationMarker(reservation, sessionId);
        return reservation;
    }

    private Path ensureExplicitReservation(
            NoFollowPathPolicy.GuardedRoot guard,
            String sessionId
    ) throws IOException {
        Path reservation = guard.requireRegularFileTarget(reservationPathFor(sessionId));
        if (Files.exists(reservation, LinkOption.NOFOLLOW_LINKS)) {
            requireReservationMarker(reservation, sessionId);
            return reservation;
        }
        try {
            return createReservation(guard, sessionId);
        } catch (FileAlreadyExistsException raced) {
            requireReservationMarker(reservation, sessionId);
            return reservation;
        }
    }

    private static void requireReservationMarker(Path reservation, String sessionId) {
        if (reservation == null) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.RESERVATION_IO,
                    "Session reservation marker is missing");
        }
        try {
            byte[] observed = NoFollowPathPolicy.readAllBytes(reservation);
            if (!java.util.Arrays.equals(observed, reservationMarker(sessionId))) {
                throw new SessionWriterUnavailableException(
                        SessionWriterUnavailableException.Reason.RESERVATION_IO,
                        "Session reservation marker does not match its identity");
            }
        } catch (SessionWriterUnavailableException failure) {
            throw failure;
        } catch (IOException failure) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.RESERVATION_IO,
                    "Session reservation marker could not be authenticated",
                    failure);
        }
    }

    private static byte[] reservationMarker(String sessionId) {
        return ("talos-session-reservation-v1\n" + sessionId + "\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    private static void closeAcquisition(FileChannel channel, FileLock lock) {
        if (lock != null) {
            try { if (lock.isValid()) lock.release(); }
            catch (IOException ignored) { }
        }
        if (channel != null) {
            try { channel.close(); }
            catch (IOException ignored) { }
        }
    }

    private static SessionWriterUnavailableException writerBusy() {
        return new SessionWriterUnavailableException(
                SessionWriterUnavailableException.Reason.WRITER_BUSY,
                "Session is already owned by another writer");
    }

    private static SessionWriterUnavailableException leaseRequired(String sessionId) {
        return new SessionWriterUnavailableException(
                SessionWriterUnavailableException.Reason.LEASE_REQUIRED,
                "Session mutation requires its active writer lease");
    }

    private static void requireSafeSessionId(String sessionId) {
        if (!safeSessionId(sessionId)) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.INVALID_SESSION_ID,
                    "Session ID must be one bounded local filename");
        }
    }

    private static boolean safeSessionId(String sessionId) {
        return sessionId != null
                && SAFE_SESSION_ID.matcher(sessionId).matches()
                && !sessionId.endsWith(".")
                && !WINDOWS_DEVICE_SESSION_ID.matcher(sessionId).matches();
    }

    private static byte[] requireEntropy(byte[] candidate) {
        if (candidate == null || candidate.length != SESSION_ENTROPY_BYTES) {
            throw new SessionWriterUnavailableException(
                    SessionWriterUnavailableException.Reason.INVALID_ENTROPY,
                    "Session entropy source must return exactly 16 bytes");
        }
        return candidate.clone();
    }

    private static byte[] secureEntropy() {
        byte[] bytes = new byte[SESSION_ENTROPY_BYTES];
        SESSION_RANDOM.nextBytes(bytes);
        return bytes;
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
     * {@code <ws-hash>-<UTC yyyyMMddHHmmss>-<32-hex-entropy>}. This method
     * formats a candidate only; production allocation must use
     * {@link #reserveNewSession(Path, Instant)} so the identity is atomically
     * reserved before use. The hash prefix is fixed-length 40-hex (no
     * {@code -}), so prefix matching against {@link #sessionIdFor} is
     * unambiguous across workspaces.
     */
    public static String newSessionInstanceId(Path workspace, Instant createdAt) {
        return candidateSessionInstanceId(workspace, createdAt, secureEntropy());
    }

    private static String candidateSessionInstanceId(
            Path workspace,
            Instant createdAt,
            byte[] entropy
    ) {
        return sessionIdFor(workspace)
                + "-" + INSTANCE_ID_TIMESTAMP.format(createdAt)
                + "-" + HexFormat.of().formatHex(requireEntropy(entropy));
    }

    @Override
    public List<SessionSummary> listSessions(String workspaceId) {
        if (!safeSessionId(workspaceId)) return List.of();
        if (!Files.exists(sessionsDir, LinkOption.NOFOLLOW_LINKS)) return List.of();

        java.util.Set<String> snapshotIds = new java.util.LinkedHashSet<>();
        java.util.Set<String> turnLogIds = new java.util.LinkedHashSet<>();
        try {
            Path root = NoFollowPathPolicy.requireUnlinkedDirectory(sessionsDir);
            NoFollowPathPolicy.GuardedRoot guard =
                    NoFollowPathPolicy.bindExistingDirectory(root);
            try (var stream = Files.list(root)) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    try {
                        if (guard.inspect(path)
                                != NoFollowPathPolicy.EntryType.REGULAR_FILE) continue;
                    } catch (IOException unsafeEntry) {
                        LOG.warn("Skipping unsafe session-list entry: {}",
                                SafeLogFormatter.throwableMessage(unsafeEntry));
                        continue;
                    }
                    String name = path.getFileName().toString();
                    if (name.endsWith(TURNS_SUFFIX)) {
                        String id = name.substring(0, name.length() - TURNS_SUFFIX.length());
                        if (safeSessionId(id) && belongsTo(id, workspaceId)) {
                            turnLogIds.add(id);
                        }
                    } else if (name.endsWith(".json")) {
                        String id = name.substring(0, name.length() - ".json".length());
                        if (safeSessionId(id) && belongsTo(id, workspaceId)) {
                            snapshotIds.add(id);
                        }
                    }
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
                    NoFollowPathPolicy.readString(
                            fileFor(sessionId), StandardCharsets.UTF_8),
                    new TypeReference<>() {});
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
    Path sessionsDir() {
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

    private static Path regularIfPresent(
            NoFollowPathPolicy.GuardedRoot guard,
            Path path
    ) throws IOException {
        NoFollowPathPolicy.EntryType type = guard.inspect(path);
        return type == NoFollowPathPolicy.EntryType.MISSING
                ? null
                : guard.requireRegularFile(path);
    }

    private List<NoFollowPathPolicy.TreeEntry> traceEntriesIfPresent(
            NoFollowPathPolicy.GuardedRoot guard,
            String sessionId
    ) throws IOException {
        Path dir = traceDirFor(sessionId);
        NoFollowPathPolicy.EntryType type = guard.inspect(dir);
        return type == NoFollowPathPolicy.EntryType.MISSING
                ? List.of()
                : guard.walk(dir);
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

