package dev.talos.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
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
import dev.talos.runtime.intent.MutationPlanSelection;
import dev.talos.runtime.intent.SequentialMutationClausePlan;
import dev.talos.runtime.intent.TargetRef;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.intent.TargetSource;
import dev.talos.runtime.intent.TaskIntentEvidence;
import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskResolution;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LexicalPlanEvidenceTraceEvent;
import dev.talos.runtime.trace.TurnEvidenceProjectionTraceEvent;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.runtime.workspace.WorkspaceOperationPlanEvidence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
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
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int LEGACY_LEXICAL_EVIDENCE_SCHEMA_VERSION = 1;
    private static final int LEXICAL_EVIDENCE_SCHEMA_VERSION = 2;
    private static final int MAX_LEXICAL_DECISIONS = 4_096;
    private static final int MAX_LEXICAL_REGIONS = 4_096;
    private static final int MAX_POLICY_STRING_LIST = 4_096;
    private static final int MAX_TRACE_EVENTS = 4_096;
    private static final int MAX_OBSERVED_TURN_LOG_BYTES =
            50 * 1024 * 1024;
    private static final int MAX_OBSERVED_TRACE_BYTES =
            10 * 1024 * 1024;
    private static final int MAX_OBSERVED_TURN_ROWS = 4_096;
    private static final int MAX_OBSERVED_SESSION_ENTRIES = 65_536;
    private static final int MAX_OBSERVED_SESSION_LOGS = 4_096;
    private static final Pattern OBSERVED_TRACE_ID =
            Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Set<String> LEXICAL_EVENT_KEYS = Set.of(
            "schemaVersion", "decisions", "regions");
    private static final Set<String> LEXICAL_DECISION_KEYS = Set.of(
            "ordinal", "owner", "path", "pathSha256", "authority",
            "storedPathSha256", "action", "actionFamily", "source",
            "evidenceState", "sourceRequestSha256", "sourceRequestChars",
            "sourceRequestBytes", "actionStartOffset", "actionEndOffset",
            "targetStartOffset", "startOffset", "authorityEndOffset",
            "endOffset", "selectedForPath", "activeInResolution",
            "sourceSpanSha256", "occurrenceSha256");
    private static final Set<String> LEXICAL_REGION_KEYS = Set.of(
            "ordinal", "owner", "kind", "source", "evidenceState",
            "sourceRequestSha256", "sourceRequestChars", "sourceRequestBytes",
            "action", "pathSha256", "startOffset", "endOffset",
            "sourceSpanSha256", "regionSha256");
    private static final Set<String> POLICY_TRACE_V1_KEYS = Set.of(
            "taskType", "mutationAllowed", "mutationRequested",
            "verificationRequired", "expectedTargets", "forbiddenTargets",
            "initialPhase", "finalPhase", "nativeTools", "promptTools",
            "blocks", "classificationReason", "rolefulTargets",
            "lexicalEvidenceSchemaVersion", "lexicalDecisions",
             "lexicalRegions");
    private static final Set<String> POLICY_TRACE_V2_KEYS = Set.of(
            "taskType", "mutationAllowed", "mutationRequested",
            "verificationRequired", "expectedTargets", "forbiddenTargets",
            "initialPhase", "finalPhase", "nativeTools", "promptTools",
            "blocks", "classificationReason", "rolefulTargets",
            "lexicalEvidenceSchemaVersion", "lexicalDecisions",
            "lexicalRegions", "workspaceOperationPlan");
    private static final Set<String> WORKSPACE_PLAN_EVIDENCE_KEYS = Set.of(
            "schemaVersion", "planId", "state", "reason",
            "canonicalRequestSha256", "canonicalRequestUtf8Bytes",
            "operations");
    private static final Set<String> WORKSPACE_OPERATION_EVIDENCE_KEYS = Set.of(
            "ordinal", "operationId", "owner", "kind", "toolName",
            "overwritePolicy", "recursivePolicy", "parentPolicy",
            "actionStartOffset", "actionEndOffset", "sourceStartOffset",
            "sourceEndOffset", "sourceSpanSha256", "operands");
    private static final Set<String> WORKSPACE_OPERAND_EVIDENCE_KEYS = Set.of(
            "ordinal", "role", "lexicalValueSha256", "effectivePathSha256",
            "valueStartOffset", "valueEndOffset");
    private static final Set<String> WORKSPACE_AUTHORITY_DECISION_KEYS = Set.of(
            "planId", "planState", "decision", "toolCallId");
    private static final Set<String> WORKSPACE_AUTHORITY_DECISION_WITH_OPERATION_KEYS = Set.of(
            "planId", "planState", "decision", "operationId", "toolCallId");
    private static final Set<String> WORKSPACE_LIFECYCLE_KEYS = Set.of(
            "planId", "operationId", "toolCallId");
    private static final Set<String> WORKSPACE_AUTHORITY_DECISIONS = Set.of(
            "NOT_WORKSPACE_OPERATION", "AUTHORIZED", "NO_TYPED_AUTHORITY",
            "CALL_DOES_NOT_MATCH_PLAN", "PREDECESSOR_NOT_COMPLETE",
            "ALREADY_CONSUMED");
    private static final Set<String> TURN_ROW_KEYS = Set.of(
            "turnNumber", "timestamp", "durationMs", "userInput",
            "assistantText", "approvalsRequired", "approvalsGranted",
            "approvalsDenied", "retrievalTraceSummary", "status", "traceId",
            "policyTrace", "toolCalls");
    private static final Set<String> TURN_TOOL_CALL_KEYS = Set.of(
            "name", "pathHint", "success", "reason");
    private static final Set<String> ROLEFUL_TARGET_V1_KEYS = Set.of(
            "path", "role", "source", "reason", "sourceText",
            "confidence", "startOffset", "authorityEndOffset", "endOffset");
    private static final Pattern WORKSPACE_SESSION_ID =
            Pattern.compile("[0-9a-f]{40}");
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
    private final DiagnosticObservationLimits diagnosticObservationLimits;
    private final Map<String, SessionWriterLease> activeLeases = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** Default location: {@code ~/.talos/sessions/}. */
    public JsonSessionStore() {
        this(Path.of(System.getProperty("user.home"), ".talos", "sessions"));
    }

    /** Custom directory (useful for testing with {@code @TempDir}). */
    public JsonSessionStore(Path sessionsDir) {
        this(
                sessionsDir,
                new AtomicFileWriter(),
                JsonSessionStore::secureEntropy,
                DiagnosticObservationLimits.defaults());
    }

    JsonSessionStore(
            Path sessionsDir,
            AtomicFileWriter atomicFileWriter,
            Supplier<byte[]> entropySource
    ) {
        this(
                sessionsDir,
                atomicFileWriter,
                entropySource,
                DiagnosticObservationLimits.defaults());
    }

    JsonSessionStore(
            Path sessionsDir,
            DiagnosticObservationLimits diagnosticObservationLimits
    ) {
        this(
                sessionsDir,
                new AtomicFileWriter(),
                JsonSessionStore::secureEntropy,
                diagnosticObservationLimits);
    }

    private JsonSessionStore(
            Path sessionsDir,
            AtomicFileWriter atomicFileWriter,
            Supplier<byte[]> entropySource,
            DiagnosticObservationLimits diagnosticObservationLimits
    ) {
        this.sessionsDir = java.util.Objects.requireNonNull(
                sessionsDir, "sessionsDir").toAbsolutePath().normalize();
        this.atomicFileWriter = java.util.Objects.requireNonNull(
                atomicFileWriter, "atomicFileWriter");
        this.entropySource = java.util.Objects.requireNonNull(
                entropySource, "entropySource");
        this.diagnosticObservationLimits = java.util.Objects.requireNonNull(
                diagnosticObservationLimits,
                "diagnosticObservationLimits");
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
                record.policyTrace(),
                true);
        HistoricalRequestIndex prior = loadTurnLedger(sessionId).requests();
        requireNoGlobalEvidenceConflict(record, prior);
        boolean redactHistorical = historicalRedactionRequired(record, prior);
        Map<String, Object> row = turnRow(record);
        String compact = sanitizedCompactJson(row, redactHistorical);
        TurnRecord sanitizedRecord = parsedTurnRecord(compact);
        if (hasHistoricalLexicalEvidence(sanitizedRecord)) {
            requireHistoricalLexicalBindings(sanitizedRecord, prior);
        }
        appendPreparedTurnJson(lease, sessionId, compact);
    }

    private static Map<String, Object> turnRow(TurnRecord record) {
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
        row.put("policyTrace", policyTraceToMap(
                policyTraceForPersistence(
                        record.policyTrace(),
                        record.userInput())));
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
        return row;
    }

    private static TurnRecord parsedTurnRecord(String compact)
            throws IOException {
        Map<String, Object> sanitizedRow = MAPPER.readValue(
                compact,
                new TypeReference<>() {});
        return rowToRecord(sanitizedRow, new LinkedHashMap<>());
    }

    private static boolean exactTurnRowShape(Map<String, Object> row) {
        if (row == null || !TURN_ROW_KEYS.equals(row.keySet())
                || !positiveExactInt(row.get("turnNumber"))
                || !nonNegativeExactLong(row.get("durationMs"))
                || !nonNegativeExactInt(row.get("approvalsRequired"))
                || !nonNegativeExactInt(row.get("approvalsGranted"))
                || !nonNegativeExactInt(row.get("approvalsDenied"))
                || !(row.get("timestamp") instanceof String timestamp)
                || !canonicalInstant(timestamp)
                || !(row.get("userInput") instanceof String)
                || !(row.get("assistantText") instanceof String)
                || !(row.get("retrievalTraceSummary") instanceof String)
                || !(row.get("status") instanceof String)
                || !(row.get("traceId") instanceof String traceId)
                || !OBSERVED_TRACE_ID.matcher(traceId).matches()
                || !(row.get("policyTrace") instanceof Map<?, ?>)
                || !(row.get("toolCalls") instanceof List<?> calls)) {
            return false;
        }
        for (Object value : calls) {
            if (!(value instanceof Map<?, ?> call)
                    || !TURN_TOOL_CALL_KEYS.equals(call.keySet())
                    || !(call.get("name") instanceof String)
                    || !(call.get("pathHint") instanceof String)
                    || !(call.get("success") instanceof Boolean)
                    || !(call.get("reason") instanceof String)) {
                return false;
            }
        }
        return true;
    }

    private static boolean canonicalInstant(String value) {
        try {
            return Instant.parse(value).toString().equals(value);
        } catch (Exception malformed) {
            return false;
        }
    }

    record DiagnosticObservationLimits(
            int maxTurnLogBytes,
            int maxTraceBytes,
            int maxTurnRows,
            int maxSessionEntries,
            int maxSessionLogs
    ) {
        DiagnosticObservationLimits {
            if (maxTurnLogBytes <= 0 || maxTraceBytes <= 0
                    || maxTurnLogBytes == Integer.MAX_VALUE
                    || maxTraceBytes == Integer.MAX_VALUE
                    || maxTurnRows <= 0 || maxSessionEntries <= 0
                    || maxSessionLogs <= 0
                    || maxSessionLogs > maxSessionEntries) {
                throw new IllegalArgumentException(
                        "diagnostic observation limits must be positive and nested");
            }
        }

        private static DiagnosticObservationLimits defaults() {
            return new DiagnosticObservationLimits(
                    MAX_OBSERVED_TURN_LOG_BYTES,
                    MAX_OBSERVED_TRACE_BYTES,
                    MAX_OBSERVED_TURN_ROWS,
                    MAX_OBSERVED_SESSION_ENTRIES,
                    MAX_OBSERVED_SESSION_LOGS);
        }
    }

    private static boolean positiveExactInt(Object value) {
        return exactLongValue(value) >= 1
                && exactLongValue(value) <= Integer.MAX_VALUE;
    }

    private static boolean nonNegativeExactInt(Object value) {
        return exactLongValue(value) >= 0
                && exactLongValue(value) <= Integer.MAX_VALUE;
    }

    private static boolean nonNegativeExactLong(Object value) {
        return exactLongValue(value) >= 0;
    }

    private static long exactLongValue(Object value) {
        return value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                ? ((Number) value).longValue()
                : Long.MIN_VALUE;
    }

    @Override
    public List<TurnRecord> loadTurns(String sessionId) {
        return loadTurnLedger(sessionId).turns();
    }

    /**
     * Observe one physical JSONL tail without the lenient recovery semantics
     * of {@link #loadTurns}. Any torn bytes, malformed row, skipped conflict,
     * unsafe identity, or read failure is typed and cannot become a fallback
     * diagnostic selection.
     */
    @Override
    public TurnLogTailObservation observeTurnLogTail(String sessionId) {
        LoadedTurnLedger ledger = loadObservedTurnLedger(sessionId);
        return switch (ledger.state()) {
            case ABSENT -> TurnLogTailObservation.absent();
            case INVALID -> TurnLogTailObservation.invalid();
            case UNAVAILABLE -> TurnLogTailObservation.unavailable();
            case EXACT -> ledger.turns().isEmpty()
                    ? TurnLogTailObservation.invalid()
                    : TurnLogTailObservation.exact(
                            ledger.turns().getLast(),
                            ledger.turns().size(),
                            ledger.tailRowSha256());
        };
    }

    @Override
    public TerminalTurnPairObservation observeTerminalTurnPair(
            String sessionId
    ) {
        if (!safeSessionId(sessionId)) {
            return TerminalTurnPairObservation.unavailable();
        }
        FileChannel channel = null;
        FileLock lock = null;
        try {
            synchronized (JVM_LOCK_MUTEX) {
                requireStoreOpen();
                NoFollowPathPolicy.GuardedRoot guard =
                        NoFollowPathPolicy.bindExistingDirectory(sessionsDir);
                Path reservationPath = guard.requireRegularFile(
                        reservationPathFor(sessionId));
                requireReservationMarker(reservationPath, sessionId);
                Path lockPath = guard.requireRegularFile(
                        writerLockPathFor(sessionId));
                lockPath = lockPath.getParent().toRealPath()
                        .resolve(lockPath.getFileName()).normalize();
                if (JVM_ACTIVE_LEASES.containsKey(lockPath)) {
                    return TerminalTurnPairObservation.unavailable();
                }
                channel = FileChannel.open(
                        lockPath,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE,
                        LinkOption.NOFOLLOW_LINKS);
                try {
                    lock = channel.tryLock(0L, 1L, false);
                } catch (OverlappingFileLockException overlapping) {
                    return TerminalTurnPairObservation.unavailable();
                }
                if (lock == null) {
                    return TerminalTurnPairObservation.unavailable();
                }
            }
            return TerminalTurnPairObservation.observe(this, sessionId);
        } catch (Exception unavailable) {
            return TerminalTurnPairObservation.unavailable();
        } finally {
            closeAcquisition(channel, lock);
        }
    }

    private LoadedTurnLedger loadTurnLedger(String sessionId) {
        return loadTurnLedgerFromSource(sessionId, false);
    }

    private LoadedTurnLedger loadObservedTurnLedger(String sessionId) {
        return loadTurnLedgerFromSource(sessionId, true);
    }

    private LoadedTurnLedger loadTurnLedgerFromSource(
            String sessionId,
            boolean diagnosticObservation
    ) {
        if (!safeSessionId(sessionId)) {
            return LoadedTurnLedger.unavailable();
        }
        Path file = turnsFileFor(sessionId);
        byte[] observedBytes = null;
        if (diagnosticObservation) {
            try {
                NoFollowPathPolicy.GuardedRoot guard =
                        NoFollowPathPolicy.bindExistingDirectory(sessionsDir);
                NoFollowPathPolicy.EntryType type = guard.inspect(file);
                if (type == NoFollowPathPolicy.EntryType.MISSING) {
                    return LoadedTurnLedger.absent();
                }
                if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                    return LoadedTurnLedger.unavailable();
                }
                observedBytes = readBoundedDiagnosticBytes(
                        guard,
                        file,
                        diagnosticObservationLimits.maxTurnLogBytes());
            } catch (IOException unavailable) {
                return LoadedTurnLedger.unavailable();
            }
        } else if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
            return LoadedTurnLedger.absent();
        }
        List<ParsedTurnRecord> parsed = new java.util.ArrayList<>();
        Map<RequestIdentity, AuthoritativeManifest> manifestCache =
                new LinkedHashMap<>();
        boolean exact = true;
        int physicalRows = 0;
        String tailRowSha256 = "";
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
            byte[] bytes = diagnosticObservation
                    ? observedBytes
                    : NoFollowPathPolicy.readAllBytes(file);
            if (bytes.length == 0) {
                return LoadedTurnLedger.invalid();
            }
            int completeLength = 0;
            for (int index = bytes.length - 1; index >= 0; index--) {
                if (bytes[index] == (byte) '\n') {
                    completeLength = index + 1;
                    break;
                }
            }
            if (completeLength == 0) return LoadedTurnLedger.invalid();
            if (completeLength != bytes.length) exact = false;
            if (diagnosticObservation
                    && exceedsLfDelimitedRowLimit(
                            bytes,
                            completeLength,
                            diagnosticObservationLimits.maxTurnRows())) {
                return LoadedTurnLedger.unavailable();
            }
            try {
                java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(bytes, 0, completeLength));
            } catch (java.nio.charset.CharacterCodingException malformedUtf8) {
                exact = false;
            }
            String complete = decoder.decode(ByteBuffer.wrap(bytes, 0, completeLength)).toString();
            String[] rawLines = complete.split("\n", -1);
            int previousPhysicalTurnNumber = 0;
            for (int lineIndex = 0; lineIndex < rawLines.length; lineIndex++) {
                String rawLine = rawLines[lineIndex];
                if (lineIndex == rawLines.length - 1 && rawLine.isEmpty()) continue;
                boolean carriageReturn = rawLine.endsWith("\r");
                if (carriageReturn) exact = false;
                String line = carriageReturn
                        ? rawLine.substring(0, rawLine.length() - 1)
                        : rawLine;
                if (line.isBlank()) {
                    exact = false;
                    continue;
                }
                physicalRows++;
                tailRowSha256 = Hash.sha256Hex(
                        line.getBytes(StandardCharsets.UTF_8));
                try {
                    Map<String, Object> row = MAPPER.readValue(line, new TypeReference<>() {});
                    boolean exactPhysicalShape = exactTurnRowShape(row);
                    TurnRecord record = rowToRecord(row, manifestCache);
                    if (!exactPhysicalShape
                            || !exactCurrentTurnRowEncoding(row, record)) {
                        exact = false;
                    }
                    if (record.turnNumber() <= previousPhysicalTurnNumber) {
                        exact = false;
                    }
                    previousPhysicalTurnNumber = record.turnNumber();
                    parsed.add(new ParsedTurnRecord(
                            record,
                            hasExactUserRequestLexicalEvidence(
                                    record.policyTrace()),
                            hasCurrentWorkspacePlanSchema(row)));
                } catch (Exception lineErr) {
                    exact = false;
                    LOG.warn("Skipping malformed turn line in {}: {}",
                            SafeLogFormatter.value(file.getFileName()), SafeLogFormatter.throwableMessage(lineErr));
                }
            }
        } catch (IOException e) {
            LOG.warn("Failed to read turn log {}: {}",
                    SafeLogFormatter.value(file), SafeLogFormatter.throwableMessage(e));
            return LoadedTurnLedger.unavailable();
        }
        HistoricalRequestIndex globalRequests = new HistoricalRequestIndex();
        for (ParsedTurnRecord parsedRecord : parsed) {
            addHistoricalRequestBinding(
                    globalRequests,
                    parsedRecord.record(),
                    parsedRecord.canonicalManifestValidated());
        }
        HistoricalRequestIndex historicalRequests =
                new HistoricalRequestIndex();
        historicalRequests.absorbConflicts(globalRequests);
        List<TurnRecord> out = new java.util.ArrayList<>();
        for (ParsedTurnRecord parsedRecord : parsed) {
            TurnRecord record = parsedRecord.record();
            try {
                requireNoGlobalEvidenceConflict(record, globalRequests);
                requireHistoricalLexicalBindings(
                        record,
                        historicalRequests,
                        manifestCache,
                        parsedRecord.currentWorkspacePlanSchema());
                out.add(record);
                addHistoricalRequestBinding(
                        historicalRequests,
                        record,
                        parsedRecord.canonicalManifestValidated());
            } catch (Exception lineErr) {
                exact = false;
                LOG.warn("Skipping conflicted turn line in {}: {}",
                        SafeLogFormatter.value(file.getFileName()),
                        SafeLogFormatter.throwableMessage(lineErr));
            }
        }
        // Only chronology-accepted rows may become future replay bindings.
        // Global conflicts remain absorbed above, but a later or rejected row
        // must never mint authority for an earlier historical replay.
        if (physicalRows == 0 || parsed.size() != physicalRows
                || out.size() != parsed.size()) {
            exact = false;
        }
        return new LoadedTurnLedger(
                out,
                historicalRequests,
                exact ? TurnLedgerState.EXACT : TurnLedgerState.INVALID,
                exact ? tailRowSha256 : "");
    }

    private static boolean exceedsLfDelimitedRowLimit(
            byte[] bytes,
            int completeLength,
            int maxRows
    ) {
        int rows = 0;
        for (int index = 0; index < completeLength; index++) {
            if (bytes[index] == (byte) '\n' && ++rows > maxRows) {
                return true;
            }
        }
        return false;
    }

    private static boolean exactCurrentTurnRowEncoding(
            Map<String, Object> physicalRow,
            TurnRecord decoded
    ) throws IOException {
        JsonNode physical = MAPPER.valueToTree(physicalRow);
        JsonNode currentWriter = MAPPER.readTree(
                sanitizedCompactJson(turnRow(decoded)));
        return physical.equals(currentWriter);
    }

    private static byte[] readBoundedDiagnosticBytes(
            NoFollowPathPolicy.GuardedRoot guard,
            Path path,
            int maxBytes
    ) throws IOException {
        try (InputStream input = guard.openRegularFile(path)) {
            byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException(
                        "Diagnostic artifact exceeds its byte limit");
            }
            return bytes;
        }
    }

    private record LoadedTurnLedger(
            List<TurnRecord> turns,
            HistoricalRequestIndex requests,
            TurnLedgerState state,
            String tailRowSha256
    ) {
        private LoadedTurnLedger {
            turns = turns == null ? List.of() : List.copyOf(turns);
            requests = requests == null
                    ? new HistoricalRequestIndex()
                    : requests;
            state = state == null ? TurnLedgerState.UNAVAILABLE : state;
            tailRowSha256 = tailRowSha256 == null ? "" : tailRowSha256;
        }

        private static LoadedTurnLedger absent() {
            return new LoadedTurnLedger(
                    List.of(),
                    new HistoricalRequestIndex(),
                    TurnLedgerState.ABSENT,
                    "");
        }

        private static LoadedTurnLedger invalid() {
            return new LoadedTurnLedger(
                    List.of(),
                    new HistoricalRequestIndex(),
                    TurnLedgerState.INVALID,
                    "");
        }

        private static LoadedTurnLedger unavailable() {
            return new LoadedTurnLedger(
                    List.of(),
                    new HistoricalRequestIndex(),
                    TurnLedgerState.UNAVAILABLE,
                    "");
        }
    }

    private enum TurnLedgerState {
        ABSENT,
        EXACT,
        INVALID,
        UNAVAILABLE
    }

    private record ParsedTurnRecord(
            TurnRecord record,
            boolean canonicalManifestValidated,
            boolean currentWorkspacePlanSchema
    ) {
        private ParsedTurnRecord {
            if (record == null) {
                throw new IllegalArgumentException(
                        "parsed turn record must not be null");
            }
        }
    }

    private static boolean hasExactUserRequestLexicalEvidence(
            TurnPolicyTrace policyTrace
    ) {
        if (policyTrace == null) return false;
        return policyTrace.lexicalDecisions().stream().anyMatch(decision ->
                        TargetSource.USER_REQUEST.name().equals(
                                decision.source())
                                && MutationPlanSelection.DurableEvidenceState
                                        .EXACT.name().equals(
                                                decision.evidenceState()))
                || policyTrace.lexicalRegions().stream().anyMatch(region ->
                        TargetSource.USER_REQUEST.name().equals(region.source())
                                && MutationPlanSelection.DurableEvidenceState
                                        .EXACT.name().equals(
                                                region.evidenceState()));
    }

    private static void requireNoGlobalEvidenceConflict(
            TurnRecord record,
            HistoricalRequestIndex globalRequests
    ) {
        if (record == null || globalRequests == null) return;
        LexicalEvidenceEnvelope evidence = LexicalEvidenceEnvelope.from(
                record.policyTrace());
        if (!evidence.empty()
                && globalRequests.conflicted(evidence.requestSha256())) {
            throw new IllegalArgumentException(
                    "lexical evidence has a globally conflicted request identity");
        }
    }

    private static boolean historicalRedactionRequired(
            TurnRecord record,
            HistoricalRequestIndex requests
    ) {
        if (!hasHistoricalLexicalEvidence(record)) return false;
        LexicalEvidenceEnvelope evidence = LexicalEvidenceEnvelope.from(
                record.policyTrace());
        String hash = evidence.requestSha256();
        int chars = evidence.requestChars();
        int bytes = evidence.requestBytes();
        if (requests.conflicted(hash)) {
            throw new IllegalArgumentException(
                    "message-history lexical evidence has a conflicted prior identity");
        }
        RequestBinding prior = requests.binding(hash, chars, bytes);
        if (prior == null) {
            throw new IllegalArgumentException(
                    "message-history lexical evidence has no prior identity");
        }
        if (!prior.manifest().equals(LexicalManifestIdentity.from(
                record.policyTrace()))) {
            throw new IllegalArgumentException(
                    "message-history lexical evidence differs from its prior plan");
        }
        if (MutationPlanSelection.DurableEvidenceState.REDACTED_HASH_ONLY
                .name().equals(evidence.evidenceState())) {
            return false;
        }
        return prior.redacted();
    }

    private static boolean hasHistoricalLexicalEvidence(TurnRecord record) {
        return record != null
                && record.policyTrace() != null
                && (record.policyTrace().lexicalDecisions().stream().anyMatch(
                        decision -> TargetSource.MESSAGE_HISTORY.name().equals(
                                decision.source()))
                        || record.policyTrace().lexicalRegions().stream().anyMatch(
                                region -> TargetSource.MESSAGE_HISTORY.name()
                                        .equals(region.source())));
    }

    private static void requireHistoricalLexicalBindings(
            TurnRecord record,
            HistoricalRequestIndex requestsByHash
    ) {
        requireHistoricalLexicalBindings(
                record,
                requestsByHash,
                new LinkedHashMap<>(),
                true);
    }

    private static void requireHistoricalLexicalBindings(
            TurnRecord record,
            HistoricalRequestIndex requestsByHash,
            Map<RequestIdentity, AuthoritativeManifest> manifestCache,
            boolean currentWorkspacePlanSchema
    ) {
        if (record == null || record.policyTrace() == null) return;
        LexicalEvidenceEnvelope evidence = LexicalEvidenceEnvelope.from(
                record.policyTrace());
        String hash = evidence.decisions().isEmpty()
                ? evidence.regions().isEmpty() ? ""
                        : evidence.regions().getFirst().sourceRequestSha256()
                : evidence.decisions().getFirst().sourceRequestSha256();
        String source = evidence.decisions().isEmpty()
                ? evidence.regions().isEmpty() ? ""
                        : evidence.regions().getFirst().source()
                : evidence.decisions().getFirst().source();
        if (!TargetSource.MESSAGE_HISTORY.name().equals(source)) return;
        String state = evidence.decisions().isEmpty()
                ? evidence.regions().getFirst().evidenceState()
                : evidence.decisions().getFirst().evidenceState();
        int requestChars = evidence.decisions().isEmpty()
                ? evidence.regions().getFirst().sourceRequestChars()
                : evidence.decisions().getFirst().sourceRequestChars();
        int requestBytes = evidence.decisions().isEmpty()
                ? evidence.regions().getFirst().sourceRequestBytes()
                : evidence.decisions().getFirst().sourceRequestBytes();
        if (requestsByHash.conflicted(hash)) {
            throw new IllegalArgumentException(
                    "message-history lexical evidence has a conflicted prior identity");
        }
        RequestBinding prior = requestsByHash.binding(
                hash,
                requestChars,
                requestBytes);
        if (prior == null) {
            throw new IllegalArgumentException(
                    "message-history lexical evidence has no prior identity");
        }
        if (!prior.manifest().equals(LexicalManifestIdentity.from(
                record.policyTrace()))) {
            throw new IllegalArgumentException(
                    "message-history lexical evidence differs from its prior plan");
        }
        if (currentWorkspacePlanSchema) {
            String historical = prior.exactCanonical();
            if (historical == null) {
                if (!"NONE".equals(record.policyTrace()
                        .workspaceOperationPlan().state())) {
                    throw new IllegalArgumentException(
                            "typed message-history workspace evidence has no prior exact request");
                }
            } else {
                record.policyTrace().workspaceOperationPlan()
                        .requireBindsCanonicalRequest(historical);
            }
        }
        if (MutationPlanSelection.DurableEvidenceState.REDACTED_HASH_ONLY
                .name().equals(state)) {
            return;
        }
        if (prior.canonicalManifestValidated()) {
            return;
        }
        String historical = prior.exactCanonical();
        if (historical == null) {
            throw new IllegalArgumentException(
                    "exact message-history evidence has no prior exact request");
        }
        TaskResolution authoritative = authoritativeResolution(
                historical,
                manifestCache);
        MutationPlanSelection.requireCanonicalDurableManifest(
                historical,
                authoritative.mutationTailPlan(),
                authoritative.sequentialMutationPlan(),
                authoritative.activeLexicalPaths(),
                evidence.decisions(),
                evidence.regions());
        requireCanonicalPolicySemantics(
                authoritative,
                record.policyTrace(),
                false);
    }

    private static void addHistoricalRequestBinding(
            HistoricalRequestIndex requestsByHash,
            TurnRecord record,
            boolean canonicalManifestValidated
    ) {
        if (requestsByHash == null || record == null) return;
        LexicalEvidenceEnvelope evidence = LexicalEvidenceEnvelope.from(
                record.policyTrace());
        if (evidence.empty()) return;
        String evidenceSource = evidence.decisions().isEmpty()
                ? evidence.regions().isEmpty() ? ""
                        : evidence.regions().getFirst().source()
                : evidence.decisions().getFirst().source();
        if (TargetSource.MESSAGE_HISTORY.name().equals(evidenceSource)) {
            return;
        }
        boolean redacted = evidence.decisions().stream().anyMatch(decision ->
                        TargetSource.USER_REQUEST.name().equals(decision.source())
                                && MutationPlanSelection.DurableEvidenceState
                                        .REDACTED_HASH_ONLY.name().equals(
                                                decision.evidenceState()))
                || evidence.regions().stream().anyMatch(region ->
                        TargetSource.USER_REQUEST.name().equals(region.source())
                                && MutationPlanSelection.DurableEvidenceState
                                        .REDACTED_HASH_ONLY.name().equals(
                                                region.evidenceState()));
        LexicalManifestIdentity manifest = LexicalManifestIdentity.from(
                record.policyTrace());
        if (redacted) {
            String hash = evidence.decisions().isEmpty()
                    ? evidence.regions().getFirst().sourceRequestSha256()
                    : evidence.decisions().getFirst().sourceRequestSha256();
            int chars = evidence.decisions().isEmpty()
                    ? evidence.regions().getFirst().sourceRequestChars()
                    : evidence.decisions().getFirst().sourceRequestChars();
            int bytes = evidence.decisions().isEmpty()
                    ? evidence.regions().getFirst().sourceRequestBytes()
                    : evidence.decisions().getFirst().sourceRequestBytes();
            requestsByHash.addRedacted(hash, chars, bytes, manifest);
        } else if (canonicalManifestValidated) {
            requestsByHash.addExact(record.userInput(), manifest);
        }
    }

    private static final class HistoricalRequestIndex {
        private final Map<RequestIdentity, RequestBinding> bindings =
                new LinkedHashMap<>();
        private final Set<String> conflicts = new HashSet<>();

        private void addExact(
                String userInput,
                LexicalManifestIdentity manifest
        ) {
            String canonical = userInput == null ? "" : userInput.strip();
            MutationPlanSelection.SourceRequestIdentity sourceIdentity =
                    MutationPlanSelection.sourceRequestIdentity(canonical);
            String hash = sourceIdentity.sha256();
            RequestIdentity identity = new RequestIdentity(
                    hash,
                    sourceIdentity.characters(),
                    sourceIdentity.bytes());
            if (conflicts.contains(hash)) return;
            if (hasDifferentIdentity(hash, identity)) {
                poison(hash);
                return;
            }
            RequestBinding candidate = new RequestBinding(
                    canonical,
                    false,
                    manifest,
                    true);
            RequestBinding existing = bindings.putIfAbsent(
                    identity,
                    candidate);
            if (existing != null && !existing.equals(candidate)) {
                poison(hash);
            }
        }

        private void addRedacted(
                String hash,
                int chars,
                int bytes,
                LexicalManifestIdentity manifest
        ) {
            if (hash == null || !hash.matches("[0-9a-f]{64}")
                    || chars < 0 || bytes < 0 || conflicts.contains(hash)) return;
            RequestIdentity identity = new RequestIdentity(hash, chars, bytes);
            if (hasDifferentIdentity(hash, identity)) {
                poison(hash);
                return;
            }
            RequestBinding candidate = new RequestBinding(
                    null,
                    true,
                    manifest,
                    false);
            RequestBinding existing = bindings.putIfAbsent(
                    identity,
                    candidate);
            if (existing != null && !existing.equals(candidate)) {
                poison(hash);
            }
        }

        private void poison(String hash) {
            bindings.keySet().removeIf(identity -> identity.sha256().equals(hash));
            conflicts.add(hash);
        }

        private void absorbConflicts(HistoricalRequestIndex other) {
            if (other == null || other.conflicts.isEmpty()) return;
            for (String hash : other.conflicts) {
                poison(hash);
            }
        }

        private boolean conflicted(String hash) {
            return conflicts.contains(hash);
        }

        private boolean hasExact(String hash, int chars, int bytes) {
            RequestBinding binding = binding(hash, chars, bytes);
            return binding != null && !binding.redacted();
        }

        private boolean hasRedacted(String hash, int chars, int bytes) {
            RequestBinding binding = binding(hash, chars, bytes);
            return binding != null && binding.redacted();
        }

        private String exact(String hash, int chars, int bytes) {
            RequestBinding binding = binding(hash, chars, bytes);
            return binding == null || binding.redacted()
                    ? null
                    : binding.exactCanonical();
        }

        private RequestBinding binding(String hash, int chars, int bytes) {
            return bindings.get(new RequestIdentity(hash, chars, bytes));
        }

        private boolean hasDifferentIdentity(
                String hash,
                RequestIdentity identity
        ) {
            return bindings.keySet().stream().anyMatch(existing ->
                            existing.sha256().equals(hash)
                                    && !existing.equals(identity));
        }
    }

    private record RequestIdentity(String sha256, int chars, int bytes) {}

    private record RequestBinding(
            String exactCanonical,
            boolean redacted,
            LexicalManifestIdentity manifest,
            boolean canonicalManifestValidated
    ) {
        private RequestBinding {
            manifest = manifest == null
                    ? LexicalManifestIdentity.EMPTY
                    : manifest;
        }
    }

    private record LexicalManifestIdentity(
            List<DecisionManifestIdentity> decisions,
            List<RegionManifestIdentity> regions,
            WorkspaceOperationPlanEvidence workspaceOperationPlan
    ) {
        private static final LexicalManifestIdentity EMPTY =
                new LexicalManifestIdentity(
                        List.of(),
                        List.of(),
                        WorkspaceOperationPlanEvidence.from(
                                dev.talos.runtime.workspace
                                        .WorkspaceOperationAuthorityPlan.none(
                                                "")));

        private LexicalManifestIdentity {
            decisions = decisions == null ? List.of() : List.copyOf(decisions);
            regions = regions == null ? List.of() : List.copyOf(regions);
            workspaceOperationPlan = workspaceOperationPlan == null
                    ? WorkspaceOperationPlanEvidence.from(
                            dev.talos.runtime.workspace
                                    .WorkspaceOperationAuthorityPlan.none(""))
                    : workspaceOperationPlan;
        }

        private static LexicalManifestIdentity from(TurnPolicyTrace trace) {
            TurnPolicyTrace safe = trace == null
                    ? TurnPolicyTrace.empty()
                    : trace;
            Map<String, TurnPolicyTrace.RolefulTarget> rolesByPath =
                    new LinkedHashMap<>();
            for (TurnPolicyTrace.RolefulTarget target
                    : safe.rolefulTargets()) {
                rolesByPath.put(target.path(), target);
            }
            return new LexicalManifestIdentity(
                    safe.lexicalDecisions().stream()
                            .map(decision -> DecisionManifestIdentity.from(
                                    decision,
                                    decision.selectedForPath()
                                            ? rolesByPath.get(decision.path())
                                            : null))
                            .toList(),
                    safe.lexicalRegions().stream()
                            .map(RegionManifestIdentity::from)
                            .toList(),
                    safe.workspaceOperationPlan());
        }
    }

    private record DecisionManifestIdentity(
            int ordinal,
            String owner,
            String pathSha256,
            String authority,
            String action,
            String actionFamily,
            int actionStartOffset,
            int actionEndOffset,
            int targetStartOffset,
            int startOffset,
            int authorityEndOffset,
            int endOffset,
            boolean selectedForPath,
            boolean activeInResolution,
            PlanRoleManifestIdentity roleProjection,
            String sourceSpanSha256
    ) {
        private static DecisionManifestIdentity from(
                TurnPolicyTrace.LexicalDecisionEvidence decision,
                TurnPolicyTrace.RolefulTarget target
        ) {
            return new DecisionManifestIdentity(
                    decision.ordinal(),
                    decision.owner(),
                    decision.pathSha256(),
                    decision.authority(),
                    decision.action(),
                    decision.actionFamily(),
                    decision.actionStartOffset(),
                    decision.actionEndOffset(),
                    decision.targetStartOffset(),
                    decision.startOffset(),
                    decision.authorityEndOffset(),
                    decision.endOffset(),
                    decision.selectedForPath(),
                    decision.activeInResolution(),
                    PlanRoleManifestIdentity.from(
                            target,
                            decision.sourceSpanSha256()),
                    decision.sourceSpanSha256());
        }
    }

    private record PlanRoleManifestIdentity(
            String role,
            String normalizedSource,
            String reason,
            String sourceTextSha256,
            double confidence
    ) {
        private static final PlanRoleManifestIdentity EMPTY =
                new PlanRoleManifestIdentity("", "", "", "", 0.0);

        private static PlanRoleManifestIdentity from(
                TurnPolicyTrace.RolefulTarget target,
                String selectedSourceSpanSha256
        ) {
            if (target == null) return EMPTY;
            String source = target.source();
            String normalizedSource = TargetSource.USER_REQUEST.name()
                            .equals(source)
                    || TargetSource.MESSAGE_HISTORY.name().equals(source)
                    ? "REQUEST_EVIDENCE"
                    : source;
            return new PlanRoleManifestIdentity(
                    target.role(),
                    normalizedSource,
                    target.reason(),
                    selectedSourceSpanSha256,
                    target.confidence());
        }
    }

    private record RegionManifestIdentity(
            int ordinal,
            String owner,
            String kind,
            String action,
            String pathSha256,
            int startOffset,
            int endOffset,
            String sourceSpanSha256
    ) {
        private static RegionManifestIdentity from(
                TurnPolicyTrace.LexicalRegionEvidence region
        ) {
            return new RegionManifestIdentity(
                    region.ordinal(),
                    region.owner(),
                    region.kind(),
                    region.action(),
                    region.pathSha256(),
                    region.startOffset(),
                    region.endOffset(),
                    region.sourceSpanSha256());
        }
    }

    private static TurnRecord rowToRecord(
            Map<String, Object> row,
            Map<RequestIdentity, AuthoritativeManifest> manifestCache
    ) {
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
        Object rawPolicyTrace = row.get("policyTrace");
        boolean currentLexicalSchema = rawPolicyTrace instanceof Map<?, ?> map
                && map.containsKey("lexicalEvidenceSchemaVersion");
        boolean currentWorkspacePlanSchema = rawPolicyTrace instanceof Map<?, ?> map
                && map.get("lexicalEvidenceSchemaVersion") instanceof Number number
                && number.intValue() == LEXICAL_EVIDENCE_SCHEMA_VERSION;
        TurnPolicyTrace policyTrace = policyTraceFrom(rawPolicyTrace, userInput);
        requireStoredUserRequestCoordinates(
                userInput,
                policyTrace,
                currentLexicalSchema,
                manifestCache,
                currentWorkspacePlanSchema);
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
            requireTraceSessionNamespace(sessionId, trace);
            LexicalEvidenceEnvelope evidence = lexicalEvidenceFromTrace(trace);
            TraceBindingIndex traceBindings = traceRequestBindings(sessionId);
            if (traceBindings.conflicted(trace.traceId())) {
                throw new IllegalArgumentException(
                        "local trace identity is conflicted in its turn ledger");
            }
            TraceRequestBinding binding = traceBindings.binding(
                    trace.traceId());
            if (binding != null) {
                if (binding.turnNumber() != trace.turnNumber()) {
                    throw new IllegalArgumentException(
                            "lexical trace requires one matching persisted turn");
                }
                String json = matchingTraceJson(trace, binding);
                writeTraceJson(sessionId, trace, json);
            } else if (!evidence.empty()) {
                throw new IllegalArgumentException(
                        "lexical trace requires one matching persisted turn");
            } else if (hasPositiveMessageHistoryRole(trace)) {
                throw new IllegalArgumentException(
                        "positive message-history trace roles require one matching persisted turn");
            } else {
                writeTrace(sessionId, trace);
            }
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
        requireTraceSessionNamespace(sessionId, trace);
        requireExactLocalTraceEvidence(
                MAPPER.valueToTree(trace),
                canonicalUserRequest);
        String json = sanitizedTraceJson(trace, canonicalUserRequest);
        requireExactLocalTraceEvidence(
                MAPPER.readTree(json),
                canonicalUserRequest);
        writeTraceJson(sessionId, trace, json);
    }

    private static String matchingTraceJson(
            LocalTurnTrace trace,
            TraceRequestBinding binding
    ) throws IOException {
        return matchingTraceJson(trace, binding, null);
    }

    private static String matchingTraceJson(
            LocalTurnTrace trace,
            TraceRequestBinding binding,
            String validatedRawRequest
    ) throws IOException {
        String sanitizationRequest = validatedRawRequest == null
                ? binding.canonicalRequest()
                : validatedRawRequest;
        for (boolean redactHistorical : List.of(false, true)) {
            String json = sanitizedTraceJson(
                    trace,
                    sanitizationRequest,
                    redactHistorical);
            JsonNode root = MAPPER.readTree(json);
            requireExactLocalTraceEvidence(root, binding.canonicalRequest());
            LocalTurnTrace stored = MAPPER.treeToValue(
                    root,
                    LocalTurnTrace.class);
            if (binding.lexicalEvidence().equals(
                            lexicalEvidenceFromTrace(stored))) {
                requireTracePolicyBinding(stored, binding.policyTrace());
                return json;
            }
        }
        throw new IllegalArgumentException(
                "local trace evidence does not match its persisted turn");
    }

    private void writeTraceJson(
            String sessionId,
            LocalTurnTrace trace,
            String json
    ) throws IOException {
        requireTraceSessionNamespace(sessionId, trace);
        Path dir = traceDirFor(sessionId);
        NoFollowPathPolicy.GuardedRoot guard = mutationGuard();
        guard.createDirectories(dir);
        Path target = guard.requireRegularFileTarget(
                dir.resolve(traceFileName(trace)));
        atomicFileWriter.replaceCurrent(
                target,
                json.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireTraceSessionNamespace(
            String sessionId,
            LocalTurnTrace trace
    ) {
        String traceSessionId = trace == null ? "" : trace.sessionId();
        if (!traceSessionNamespaceMatches(sessionId, traceSessionId)) {
            throw new IllegalArgumentException(
                    "local trace workspace identity does not match its destination");
        }
    }

    private static boolean traceSessionNamespaceMatches(
            String sessionId,
            String traceSessionId
    ) {
        boolean exactLegacyIdentity = sessionId != null
                && sessionId.equals(traceSessionId);
        boolean workspaceScopedIdentity = WORKSPACE_SESSION_ID.matcher(
                traceSessionId == null ? "" : traceSessionId).matches()
                && sessionId != null
                && sessionId.startsWith(traceSessionId + "-")
                && sessionId.length() > traceSessionId.length() + 1;
        return exactLegacyIdentity || workspaceScopedIdentity;
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
                    || !traceSessionNamespaceMatches(
                            sessionId,
                            trace.sessionId())
                    || trace.traceId().isBlank()
                    || !OBSERVED_TRACE_ID.matcher(trace.traceId()).matches()
                    || !trace.traceId().equals(recordTraceId)));
        if (invalidIdentity) {
            LOG.warn("Rejected mismatched turn envelope for {}",
                    SafeLogFormatter.value(sessionId));
            return new TurnEnvelopePersistenceReceipt(
                    TurnEnvelopePersistenceReceipt.State.NO_WRITES_OBSERVED);
        }
        String preparedRecordJson;
        String preparedFallbackRecordJson;
        String preparedTraceJson = null;
        try {
            try {
                requireStoredUserRequestCoordinates(
                        record.userInput(),
                        record.policyTrace(),
                        true);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "raw turn policy evidence is invalid: "
                                + SafeLogFormatter.throwableMessage(e),
                        e);
            }
            HistoricalRequestIndex prior = loadTurnLedger(sessionId)
                    .requests();
            requireNoGlobalEvidenceConflict(record, prior);
            boolean redactHistorical = historicalRedactionRequired(
                    record,
                    prior);
            TurnRecord sanitizedRecord;
            try {
                sanitizedRecord = parsedTurnRecord(
                        preparedRecordJson = sanitizedCompactJson(
                                turnRow(record),
                                redactHistorical));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "sanitized turn policy evidence is invalid: "
                                + SafeLogFormatter.throwableMessage(e),
                        e);
            }
            if (hasHistoricalLexicalEvidence(sanitizedRecord)) {
                requireHistoricalLexicalBindings(
                        sanitizedRecord,
                        prior);
            }
            // The trace-write fallback clears only traceId, but its complete
            // sanitized row must also be valid before the first publication.
            TurnRecord sanitizedFallback;
            try {
                sanitizedFallback = parsedTurnRecord(
                        preparedFallbackRecordJson = sanitizedCompactJson(
                                turnRow(withTraceId(record, "")),
                                redactHistorical));
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "sanitized fallback turn evidence is invalid: "
                                + SafeLogFormatter.throwableMessage(e),
                        e);
            }
            if (hasHistoricalLexicalEvidence(sanitizedFallback)) {
                requireHistoricalLexicalBindings(sanitizedFallback, prior);
            }
            if (trace != null) {
                requireExactLocalTraceEvidence(
                        MAPPER.valueToTree(trace),
                        record.userInput());
                if (!lexicalEvidenceFromTrace(trace).equals(
                        LexicalEvidenceEnvelope.from(record.policyTrace()))) {
                    throw new IllegalArgumentException(
                            "turn record and local trace lexical evidence must match");
                }
                requireTracePolicyBinding(trace, record.policyTrace());
                TraceRequestBinding binding = new TraceRequestBinding(
                        sanitizedRecord.turnNumber(),
                        sanitizedRecord.userInput().strip(),
                        LexicalEvidenceEnvelope.from(
                                sanitizedRecord.policyTrace()),
                        sanitizedRecord.policyTrace());
                preparedTraceJson = matchingTraceJson(
                        trace,
                        binding,
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
                writeTraceJson(sessionId, trace, preparedTraceJson);
                traceWritten = true;
            } catch (Exception e) {
                LOG.warn("Failed to save local turn trace for {}: {}",
                        SafeLogFormatter.value(sessionId), SafeLogFormatter.throwableMessage(e));
            }
        }

        boolean turnWritten = false;
        try {
            appendPreparedTurnJson(
                    lease,
                    sessionId,
                    traceWritten || trace == null
                            ? preparedRecordJson
                            : preparedFallbackRecordJson);
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

    private void appendPreparedTurnJson(
            SessionWriterLease lease,
            String sessionId,
            String compact
    ) throws IOException {
        if (compact == null || compact.isBlank()) {
            throw new IllegalArgumentException(
                    "prepared turn JSON must not be blank");
        }
        byte[] line = (compact + "\n").getBytes(StandardCharsets.UTF_8);
        try {
            Path target = mutationGuard().requireRegularFileTarget(
                    turnsFileFor(sessionId));
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
                    if (written <= 0) {
                        throw new IOException(
                                "Turn log append made no progress");
                    }
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
            if (existing.read(last) != 1
                    || last.array()[0] != (byte) '\n') {
                lease.poison();
                throw new SessionWriterUnavailableException(
                        SessionWriterUnavailableException.Reason
                                .INCOMPLETE_TURN_LOG,
                        "Session turn log ends with an incomplete record");
            }
        }
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
        TraceBindingIndex requestBindings =
                traceRequestBindings(sessionId);
        try (var stream = Files.list(
                NoFollowPathPolicy.requireUnlinkedDirectory(dir))) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith("-" + sanitizeTraceId(traceId) + ".json"))
                    .sorted()
                    .map(path -> readTrace(sessionId, path, requestBindings).filter(trace ->
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
    public TraceArtifactObservation observeTraceArtifact(
            String sessionId,
            int turnNumber,
            String traceId
    ) {
        if (!safeSessionId(sessionId) || turnNumber <= 0
                || traceId == null
                || !OBSERVED_TRACE_ID.matcher(traceId).matches()) {
            return TraceArtifactObservation.unavailable();
        }
        Path dir = traceDirFor(sessionId);
        Path path = dir.resolve("%06d-%s.json".formatted(
                turnNumber, traceId));
        try {
            NoFollowPathPolicy.GuardedRoot guard =
                    NoFollowPathPolicy.bindExistingDirectory(sessionsDir);
            NoFollowPathPolicy.EntryType type = guard.inspect(path);
            if (type == NoFollowPathPolicy.EntryType.MISSING) {
                return TraceArtifactObservation.absent();
            }
            if (type != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                return TraceArtifactObservation.unavailable();
            }
            byte[] bytes = readBoundedDiagnosticBytes(
                    guard,
                    path,
                    diagnosticObservationLimits.maxTraceBytes());
            LoadedTurnLedger ledger = loadObservedTurnLedger(sessionId);
            if (ledger.state() == TurnLedgerState.UNAVAILABLE) {
                return TraceArtifactObservation.unavailable();
            }
            if (ledger.state() == TurnLedgerState.INVALID) {
                return TraceArtifactObservation.invalid();
            }
            TraceBindingIndex requestBindings = traceRequestBindings(
                    ledger.turns());
            Optional<LocalTurnTrace> loaded = readTraceBytes(
                    sessionId, path, bytes, requestBindings, true);
            if (loaded.isEmpty()) return TraceArtifactObservation.invalid();
            LocalTurnTrace trace = loaded.get();
            if (trace.turnNumber() != turnNumber
                    || !traceId.equals(trace.traceId())
                    || !path.getFileName().toString().equals(
                            traceFileName(trace))) {
                return TraceArtifactObservation.invalid();
            }
            return TraceArtifactObservation.exact(
                    trace, Hash.sha256Hex(bytes));
        } catch (Exception unavailable) {
            return TraceArtifactObservation.unavailable();
        }
    }

    @Override
    public Optional<LocalTurnTrace> loadLatestTrace(String sessionId) {
        if (!safeSessionId(sessionId)) return Optional.empty();
        Path dir = traceDirFor(sessionId);
        if (!Files.exists(dir, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        TraceBindingIndex requestBindings =
                traceRequestBindings(sessionId);
        try (var stream = Files.list(
                NoFollowPathPolicy.requireUnlinkedDirectory(dir))) {
            return stream
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing((Path path) -> path.getFileName().toString()).reversed())
                    .map(path -> readTrace(sessionId, path, requestBindings).filter(trace ->
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
            String sessionId,
            Path path,
            TraceBindingIndex requestBindings
    ) {
        try {
            return readTraceBytes(
                    sessionId,
                    path,
                    NoFollowPathPolicy.readAllBytes(path),
                    requestBindings,
                    false);
        } catch (Exception e) {
            LOG.warn("Skipping malformed local trace {}: {}",
                    SafeLogFormatter.value(path.getFileName()),
                    SafeLogFormatter.throwableMessage(e));
            return Optional.empty();
        }
    }

    private Optional<LocalTurnTrace> readTraceBytes(
            String sessionId,
            Path path,
            byte[] bytes,
            TraceBindingIndex requestBindings,
            boolean strictCurrentWriter
    ) {
        try {
            JsonNode root = MAPPER.readTree(bytes);
            JsonNode physicalRoot = root == null ? null : root.deepCopy();
            String traceId = root.path("traceId").isTextual()
                    ? root.path("traceId").asText()
                    : "";
            if (requestBindings != null
                    && requestBindings.conflicted(traceId)) {
                throw new IllegalArgumentException(
                        "local trace identity is conflicted in its turn ledger");
            }
            TraceRequestBinding binding = requestBindings == null
                    ? null
                    : requestBindings.binding(traceId);
            String canonicalRequest = binding == null
                    ? null
                    : binding.canonicalRequest();
            final LocalTurnTrace trace;
            if (strictCurrentWriter) {
                if (exactJsonInt(root.get("schemaVersion"), "schemaVersion")
                                != 5
                        || root.get("promptAudit") == null
                        || !root.get("promptAudit").isObject()
                        || exactJsonInt(
                                root.get("promptAudit").get("schemaVersion"),
                                "promptAudit.schemaVersion") != 1) {
                    throw new IllegalArgumentException(
                            "local trace must use the current writer schema");
                }
                trace = MAPPER.treeToValue(root, LocalTurnTrace.class);
                JsonNode currentWriter = MAPPER.readTree(
                        sanitizedTraceJson(trace, canonicalRequest));
                if (physicalRoot == null
                        || !physicalRoot.equals(currentWriter)) {
                    throw new IllegalArgumentException(
                            "local trace must use the complete current-writer schema");
                }
                requireExactLocalTraceEvidence(
                        physicalRoot.deepCopy(),
                        canonicalRequest);
            } else {
                requireExactLocalTraceEvidence(root, canonicalRequest);
                trace = MAPPER.treeToValue(root, LocalTurnTrace.class);
            }
            if (!traceSessionNamespaceMatches(
                    sessionId,
                    trace.sessionId())) {
                throw new IllegalArgumentException(
                        "local trace workspace identity does not match its directory");
            }
            LexicalEvidenceEnvelope traceEvidence =
                    lexicalEvidenceFromTrace(trace);
            if (binding == null && hasPositiveMessageHistoryRole(trace)) {
                throw new IllegalArgumentException(
                        "positive message-history trace roles require one matching persisted turn");
            }
            if (binding == null && !traceEvidence.empty()
                    || binding != null
                            && (binding.turnNumber() != trace.turnNumber()
                                    || !traceEvidence.equals(
                                            binding.lexicalEvidence()))) {
                throw new IllegalArgumentException(
                        "local trace lexical evidence has no matching turn record");
            }
            if (binding != null) {
                requireTracePolicyBinding(trace, binding.policyTrace());
            }
            return Optional.of(trace);
        } catch (Exception e) {
            LOG.warn("Skipping malformed local trace {}: {}",
                    SafeLogFormatter.value(path.getFileName()),
                    SafeLogFormatter.throwableMessage(e));
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
        return sanitizedTraceJson(trace, canonicalUserRequest, false);
    }

    private static String sanitizedTraceJson(
            LocalTurnTrace trace,
            String canonicalUserRequest,
            boolean redactHistoricalEvidence
    ) throws IOException {
        JsonNode root = MAPPER.valueToTree(trace);
        sanitizeJsonTextNodes(root, false);
        if (canonicalUserRequest != null
                && !ProtectedContentPolicy.sanitizeText(canonicalUserRequest)
                .equals(canonicalUserRequest)) {
            invalidateLocalUserRequestCoordinates(root);
        }
        if (redactHistoricalEvidence) {
            invalidateEvidenceForSource(
                    root,
                    TargetSource.MESSAGE_HISTORY.name());
        }
        return MAPPER.writerWithDefaultPrettyPrinter()
                .writeValueAsString(root);
    }

    private static void invalidateLocalUserRequestCoordinates(JsonNode root) {
        JsonNode targets = root == null
                ? null
                : root.path("taskContract").path("rolefulTargets");
        if (targets != null && targets.isArray()) {
            for (JsonNode target : targets) {
                if (target instanceof ObjectNode objectTarget
                        && TargetSource.USER_REQUEST.name().equals(
                                objectTarget.path("source").asText())) {
                    invalidateCoordinateFields(objectTarget);
                }
            }
        }
        JsonNode events = root == null ? null : root.path("events");
        if (events != null && events.isArray()) {
            for (JsonNode event : events) {
                if (!LexicalPlanEvidenceTraceEvent.TYPE.equals(
                        event.path("type").asText())) continue;
                for (String listName : List.of("decisions", "regions")) {
                    JsonNode rows = event.path("data").path(listName);
                    if (!rows.isArray()) continue;
                    for (JsonNode row : rows) {
                        if (row instanceof ObjectNode objectRow
                                && TargetSource.USER_REQUEST.name().equals(
                                        objectRow.path("source").asText())) {
                            invalidateCoordinateFields(objectRow);
                        }
                    }
                }
            }
        }
    }

    private static String sanitizedCompactJson(Object value) throws IOException {
        return sanitizedCompactJson(value, false);
    }

    private static String sanitizedCompactJson(
            Object value,
            boolean redactHistoricalEvidence
    ) throws IOException {
        JsonNode root = MAPPER.valueToTree(value);
        sanitizeJsonTextNodes(root, false);
        if (redactHistoricalEvidence) {
            invalidateEvidenceForSource(
                    root,
                    TargetSource.MESSAGE_HISTORY.name());
        }
        return MAPPER.writeValueAsString(root);
    }

    private static void invalidateEvidenceForSource(
            JsonNode node,
            String source
    ) {
        if (node == null || source == null || source.isBlank()) return;
        if (node instanceof ObjectNode objectNode) {
            if (source.equals(objectNode.path("source").asText())
                    && (objectNode.has("evidenceState")
                            || objectNode.has("startOffset")
                            || objectNode.has("authorityEndOffset")
                            || objectNode.has("endOffset"))) {
                invalidateCoordinateFields(objectNode);
            }
            objectNode.elements().forEachRemaining(child ->
                    invalidateEvidenceForSource(child, source));
            return;
        }
        if (node instanceof ArrayNode arrayNode) {
            for (JsonNode child : arrayNode) {
                invalidateEvidenceForSource(child, source);
            }
        }
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
                        if ("sourceText".equals(field.getKey())
                                || objectNode.has("evidenceState")) {
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
            if (objectNode.has("storedPathSha256")
                    && objectNode.path("path").isTextual()) {
                objectNode.put(
                        "storedPathSha256",
                        MutationPlanSelection.pathSha256(
                                objectNode.path("path").asText()));
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

    private TraceBindingIndex traceRequestBindings(
            String sessionId
    ) {
        return traceRequestBindings(loadTurnLedger(sessionId).turns());
    }

    private static TraceBindingIndex traceRequestBindings(
            List<TurnRecord> turns
    ) {
        LinkedHashMap<String, TraceRequestBinding> bindings =
                new LinkedHashMap<>();
        java.util.HashSet<String> conflicts = new java.util.HashSet<>();
        for (TurnRecord record : turns == null ? List.<TurnRecord>of() : turns) {
            String traceId = record.traceId();
            if (traceId == null || traceId.isBlank() || conflicts.contains(traceId)) {
                continue;
            }
            String canonical = record.userInput() == null
                    ? ""
                    : record.userInput().strip();
            TraceRequestBinding candidate = new TraceRequestBinding(
                    record.turnNumber(),
                    canonical,
                    LexicalEvidenceEnvelope.from(record.policyTrace()),
                    record.policyTrace());
            TraceRequestBinding previous = bindings.putIfAbsent(
                    traceId,
                    candidate);
            if (previous != null) {
                bindings.remove(traceId);
                conflicts.add(traceId);
            }
        }
        return new TraceBindingIndex(bindings, conflicts);
    }

    private record TraceBindingIndex(
            Map<String, TraceRequestBinding> bindings,
            Set<String> conflicts
    ) {
        private TraceBindingIndex {
            bindings = bindings == null ? Map.of() : Map.copyOf(bindings);
            conflicts = conflicts == null ? Set.of() : Set.copyOf(conflicts);
        }

        private TraceRequestBinding binding(String traceId) {
            return bindings.get(traceId);
        }

        private boolean conflicted(String traceId) {
            return traceId != null && conflicts.contains(traceId);
        }
    }

    private record TraceRequestBinding(
            int turnNumber,
            String canonicalRequest,
            LexicalEvidenceEnvelope lexicalEvidence,
            TurnPolicyTrace policyTrace
    ) {
        private TraceRequestBinding {
            if (turnNumber <= 0) {
                throw new IllegalArgumentException(
                        "trace request binding requires a positive turn number");
            }
            canonicalRequest = canonicalRequest == null
                    ? ""
                    : canonicalRequest;
            lexicalEvidence = lexicalEvidence == null
                    ? LexicalEvidenceEnvelope.EMPTY
                    : lexicalEvidence;
            policyTrace = policyTrace == null
                    ? TurnPolicyTrace.empty()
                    : policyTrace;
        }
    }

    private static void requireTracePolicyBinding(
            LocalTurnTrace trace,
            TurnPolicyTrace policy
    ) {
        if (trace == null || policy == null) {
            throw new IllegalArgumentException(
                    "trace policy binding requires both trace representations");
        }
        requireWorkspaceOperationTraceBinding(
                trace,
                policy.workspaceOperationPlan());
        LocalTurnTrace.TaskContractSummary task = trace.taskContract();
        LocalTurnTrace.ToolSurface tools = trace.toolSurface();
        TurnEvidenceProjectionTraceEvent.Decoded projection =
                TurnEvidenceProjectionTraceEvent.decode(trace);
        if (projection.present()) {
            if (!terminalProjectionPolicyIsEmpty(policy)
                    || !task.expectedTargets().isEmpty()
                    || !task.forbiddenTargets().isEmpty()
                    || !task.classificationReason().isBlank()
                    || !task.rolefulTargets().isEmpty()
                    || !tools.nativeTools().isEmpty()
                    || !tools.promptTools().isEmpty()
                    || !lexicalEvidenceFromTrace(trace).empty()) {
                throw new IllegalArgumentException(
                        "local terminal trace projection does not match its turn record");
            }
            return;
        }
        if (!normalizedTraceTaskType(task.type()).equals(
                        normalizedTraceTaskType(policy.taskType()))
                || task.mutationAllowed() != policy.mutationAllowed()
                || task.mutationRequested() != policy.mutationRequested()
                || task.verificationRequired()
                        != policy.verificationRequired()
                || !task.expectedTargets().equals(policy.expectedTargets())
                || !task.forbiddenTargets().equals(policy.forbiddenTargets())
                || !task.classificationReason().equals(
                        policy.classificationReason())
                || !tools.nativeTools().equals(policy.nativeTools())
                || !tools.promptTools().equals(policy.promptTools())
                || !traceRoleIdentities(task.rolefulTargets()).equals(
                        policyRoleIdentities(policy.rolefulTargets()))) {
            throw new IllegalArgumentException(
                    "local trace task/tool projection does not match its turn record");
        }
    }

    private static boolean terminalProjectionPolicyIsEmpty(
            TurnPolicyTrace policy
    ) {
        return normalizedTraceTaskType(policy.taskType()).isBlank()
                && !policy.mutationAllowed()
                && !policy.mutationRequested()
                && !policy.verificationRequired()
                && policy.expectedTargets().isEmpty()
                && policy.forbiddenTargets().isEmpty()
                && policy.nativeTools().isEmpty()
                && policy.promptTools().isEmpty()
                && policy.blocks().isEmpty()
                && policy.classificationReason().isBlank()
                && policy.rolefulTargets().isEmpty()
                && policy.lexicalDecisions().isEmpty()
                && policy.lexicalRegions().isEmpty()
                && "NONE".equals(policy.workspaceOperationPlan().state());
    }

    private static String normalizedTraceTaskType(String value) {
        String normalized = value == null ? "" : value.strip();
        return "UNKNOWN".equals(normalized) ? "" : normalized;
    }

    private static List<TraceRoleIdentity> traceRoleIdentities(
            List<LocalTurnTrace.RolefulTargetSummary> targets
    ) {
        if (targets == null || targets.isEmpty()) return List.of();
        return targets.stream().map(target -> new TraceRoleIdentity(
                target.path(),
                target.role(),
                target.source(),
                target.reason(),
                target.sourceText(),
                target.confidence(),
                target.startOffset(),
                target.authorityEndOffset(),
                target.endOffset())).toList();
    }

    private static List<TraceRoleIdentity> policyRoleIdentities(
            List<TurnPolicyTrace.RolefulTarget> targets
    ) {
        if (targets == null || targets.isEmpty()) return List.of();
        return targets.stream().map(target -> new TraceRoleIdentity(
                target.path(),
                target.role(),
                target.source(),
                target.reason(),
                target.sourceText(),
                target.confidence(),
                target.startOffset(),
                target.authorityEndOffset(),
                target.endOffset())).toList();
    }

    private static boolean hasPositiveMessageHistoryRole(
            LocalTurnTrace trace
    ) {
        if (trace == null || trace.taskContract() == null) return false;
        return trace.taskContract().rolefulTargets().stream().anyMatch(
                target -> TargetSource.MESSAGE_HISTORY.name().equals(
                                target.source())
                        && switch (target.role()) {
                            case "MUST_MUTATE",
                                    "MUST_MUTATE_AFTER_SOURCE_EVIDENCE",
                                    "OUTPUT_DESTINATION", "MAY_MUTATE" -> true;
                            default -> false;
                        });
    }

    private record TraceRoleIdentity(
            String path,
            String role,
            String source,
            String reason,
            String sourceText,
            double confidence,
            int startOffset,
            int authorityEndOffset,
            int endOffset
    ) {}

    private record LexicalEvidenceEnvelope(
            List<TurnPolicyTrace.LexicalDecisionEvidence> decisions,
            List<TurnPolicyTrace.LexicalRegionEvidence> regions
    ) {
        private static final LexicalEvidenceEnvelope EMPTY =
                new LexicalEvidenceEnvelope(List.of(), List.of());

        private LexicalEvidenceEnvelope {
            decisions = MutationPlanSelection.validatedDurableDecisions(
                    decisions);
            regions = MutationPlanSelection.validatedDurableRegions(regions);
            if (!decisions.isEmpty() && !regions.isEmpty()) {
                var decision = decisions.getFirst();
                var region = regions.getFirst();
                if (!decision.source().equals(region.source())
                        || !decision.evidenceState().equals(
                                region.evidenceState())
                        || !decision.sourceRequestSha256().equals(
                                region.sourceRequestSha256())
                        || decision.sourceRequestChars()
                                != region.sourceRequestChars()
                        || decision.sourceRequestBytes()
                                != region.sourceRequestBytes()) {
                    throw new IllegalArgumentException(
                            "lexical event rows must share one request identity");
                }
            }
        }

        private static LexicalEvidenceEnvelope from(TurnPolicyTrace trace) {
            TurnPolicyTrace safe = trace == null
                    ? TurnPolicyTrace.empty() : trace;
            if (safe.lexicalDecisions().isEmpty()
                    && safe.lexicalRegions().isEmpty()) return EMPTY;
            return new LexicalEvidenceEnvelope(
                    safe.lexicalDecisions(),
                    safe.lexicalRegions());
        }

        private boolean empty() {
            return decisions.isEmpty() && regions.isEmpty();
        }

        private String requestSha256() {
            return decisions.isEmpty()
                    ? regions.isEmpty() ? ""
                            : regions.getFirst().sourceRequestSha256()
                    : decisions.getFirst().sourceRequestSha256();
        }

        private int requestChars() {
            return decisions.isEmpty()
                    ? regions.isEmpty() ? 0
                            : regions.getFirst().sourceRequestChars()
                    : decisions.getFirst().sourceRequestChars();
        }

        private int requestBytes() {
            return decisions.isEmpty()
                    ? regions.isEmpty() ? 0
                            : regions.getFirst().sourceRequestBytes()
                    : decisions.getFirst().sourceRequestBytes();
        }

        private String evidenceState() {
            return decisions.isEmpty()
                    ? regions.isEmpty() ? ""
                            : regions.getFirst().evidenceState()
                    : decisions.getFirst().evidenceState();
        }
    }

    private static LexicalEvidenceEnvelope lexicalEvidenceFromTrace(
            LocalTurnTrace trace
    ) {
        if (trace == null || trace.events().isEmpty()) {
            return LexicalEvidenceEnvelope.EMPTY;
        }
        if (trace.events().size() > MAX_TRACE_EVENTS) {
            throw new IllegalArgumentException("too many local trace events");
        }
        TurnTraceEvent match = null;
        for (TurnTraceEvent event : trace.events()) {
            if (!LexicalPlanEvidenceTraceEvent.TYPE.equals(event.type())) {
                continue;
            }
            if (match != null) {
                throw new IllegalArgumentException(
                        "local trace contains duplicate lexical plan events");
            }
            match = event;
        }
        if (match == null) return LexicalEvidenceEnvelope.EMPTY;
        if (!match.phase().isBlank() || !match.toolName().isBlank()) {
            throw new IllegalArgumentException(
                    "lexical plan event cannot claim a phase or tool");
        }
        return lexicalEvidenceFromEventData(match.data());
    }

    private static LexicalEvidenceEnvelope lexicalEvidenceFromEventData(
            Map<String, Object> data
    ) {
        requireExactKeys(data, LEXICAL_EVENT_KEYS, "lexical plan event");
        if (exactInt(data.get("schemaVersion"), "schemaVersion")
                != LexicalPlanEvidenceTraceEvent.SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "unsupported lexical plan event schema");
        }
        LexicalEvidenceEnvelope evidence = new LexicalEvidenceEnvelope(
                lexicalDecisionsFrom(data.get("decisions")),
                lexicalRegionsFrom(data.get("regions")));
        if (evidence.empty()) {
            throw new IllegalArgumentException(
                    "lexical plan event must carry typed evidence");
        }
        return evidence;
    }

    private static void requireExactKeys(
            Map<?, ?> map,
            Set<String> expected,
            String label
    ) {
        if (map == null || map.size() != expected.size()
                || !map.keySet().stream().allMatch(key ->
                        key instanceof String text && expected.contains(text))) {
            throw new IllegalArgumentException(
                    label + " must use its exact JSON key set");
        }
    }

    private static void invalidateCoordinateFields(ObjectNode objectNode) {
        if (objectNode != null && objectNode.has("evidenceState")) {
            objectNode.put(
                    "evidenceState",
                    MutationPlanSelection.DurableEvidenceState
                            .REDACTED_HASH_ONLY.name());
            return;
        }
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
            targets = MAPPER.createArrayNode();
        }
        if (!targets.isArray()) {
            throw new IllegalArgumentException(
                    "local trace rolefulTargets must be an array");
        }
        if (targets.size()
                > MutationPlanSelection.MAX_DURABLE_ROLEFUL_TARGETS) {
            throw new IllegalArgumentException(
                    "too many local trace roleful targets");
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
        JsonNode events = root == null ? null : root.get("events");
        if (events == null) return;
        if (!events.isArray()) {
            throw new IllegalArgumentException(
                    "local trace events must be an array");
        }
        if (events.size() > MAX_TRACE_EVENTS) {
            throw new IllegalArgumentException("too many local trace events");
        }
        JsonNode lexicalEvent = null;
        for (JsonNode event : events) {
            if (event == null || !event.isObject()) continue;
            JsonNode type = event.get("type");
            if (type == null || !type.isTextual()
                    || !LexicalPlanEvidenceTraceEvent.TYPE.equals(
                            type.asText())) continue;
            if (lexicalEvent != null) {
                throw new IllegalArgumentException(
                        "local trace contains duplicate lexical plan events");
            }
            lexicalEvent = event;
        }
        if (lexicalEvent == null) return;
        if (!exactJsonText(lexicalEvent, "phase").isBlank()
                || !exactJsonText(lexicalEvent, "toolName").isBlank()) {
            throw new IllegalArgumentException(
                    "lexical plan event cannot claim a phase or tool");
        }
        JsonNode data = lexicalEvent.get("data");
        if (data == null || !data.isObject()) {
            throw new IllegalArgumentException(
                    "lexical plan event data must be an object");
        }
        Map<String, Object> eventData = MAPPER.convertValue(
                data,
                new TypeReference<>() {});
        LexicalEvidenceEnvelope evidence = lexicalEvidenceFromEventData(
                eventData);
        boolean exactUserRequest = evidence.decisions().stream().anyMatch(
                decision -> TargetSource.USER_REQUEST.name().equals(
                                decision.source())
                        && MutationPlanSelection.DurableEvidenceState.EXACT.name()
                                .equals(decision.evidenceState()))
                || evidence.regions().stream().anyMatch(region ->
                        TargetSource.USER_REQUEST.name().equals(region.source())
                                && MutationPlanSelection.DurableEvidenceState.EXACT
                                        .name().equals(region.evidenceState()));
        if (exactUserRequest) {
            if (canonicalUserRequest == null) {
                throw new IllegalArgumentException(
                        "local trace lexical evidence requires a matching turn");
            }
            TaskResolution authoritative =
                    TaskContractResolver.resolveFromUserRequest(
                            canonicalUserRequest);
            MutationPlanSelection.requireCanonicalDurableManifest(
                    canonicalUserRequest,
                    authoritative.mutationTailPlan(),
                    authoritative.sequentialMutationPlan(),
                    authoritative.activeLexicalPaths(),
                    evidence.decisions(),
                    evidence.regions());
        }
    }

    private static String exactJsonText(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON string");
        }
        return value.asText();
    }

    private static boolean exactJsonBoolean(JsonNode object, String field) {
        JsonNode value = object == null ? null : object.get(field);
        if (value == null || !value.isBoolean()) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON boolean");
        }
        return value.booleanValue();
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
            TurnPolicyTrace policyTrace,
            boolean currentLexicalSchema
    ) {
        requireStoredUserRequestCoordinates(
                userInput,
                policyTrace,
                currentLexicalSchema,
                new LinkedHashMap<>(),
                currentLexicalSchema);
    }

    private static boolean hasCurrentWorkspacePlanSchema(
            Map<String, Object> row
    ) {
        Object rawPolicy = row == null ? null : row.get("policyTrace");
        return rawPolicy instanceof Map<?, ?> map
                && map.get("lexicalEvidenceSchemaVersion")
                        instanceof Number number
                && number.intValue() == LEXICAL_EVIDENCE_SCHEMA_VERSION;
    }

    private static void requireStoredUserRequestCoordinates(
            String userInput,
            TurnPolicyTrace policyTrace,
            boolean currentLexicalSchema,
            Map<RequestIdentity, AuthoritativeManifest> manifestCache,
            boolean currentWorkspacePlanSchema
    ) {
        if (policyTrace == null) return;
        String canonical = userInput == null ? "" : userInput.strip();
        policyTrace = policyTraceForPersistence(policyTrace, canonical);
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
        if (!currentLexicalSchema) return;
        LexicalEvidenceEnvelope evidence = LexicalEvidenceEnvelope.from(
                policyTrace);
        String lexicalSource = evidence.decisions().isEmpty()
                ? evidence.regions().isEmpty() ? ""
                        : evidence.regions().getFirst().source()
                : evidence.decisions().getFirst().source();
        if (TargetSource.MESSAGE_HISTORY.name().equals(lexicalSource)) {
            return;
        }
        boolean redacted = policyTrace.lexicalDecisions().stream().anyMatch(
                        decision -> MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name().equals(
                                        decision.evidenceState()))
                || policyTrace.lexicalRegions().stream().anyMatch(region ->
                        MutationPlanSelection.DurableEvidenceState
                                .REDACTED_HASH_ONLY.name().equals(
                                        region.evidenceState()));
        boolean retainedRequestMatchesEvidence = evidence.empty()
                || (evidence.requestSha256().equals(
                                MutationPlanSelection.sourceRequestSha256(
                                        canonical))
                        && evidence.requestChars()
                                == MutationPlanSelection.sourceRequestChars(
                                        canonical)
                        && evidence.requestBytes()
                                == MutationPlanSelection.sourceRequestBytes(
                                        canonical));
        if (redacted && !retainedRequestMatchesEvidence) {
            if (currentWorkspacePlanSchema
                    && !"NONE".equals(
                            policyTrace.workspaceOperationPlan().state())) {
                throw new IllegalArgumentException(
                        "redacted workspace-operation evidence cannot be verified against the retained request");
            }
            return;
        }
        if (currentWorkspacePlanSchema) {
            policyTrace.workspaceOperationPlan()
                    .requireBindsCanonicalRequest(canonical);
        }
        TaskResolution authoritative = authoritativeResolution(
                canonical,
                manifestCache);
        WorkspaceOperationPlanEvidence observed =
                policyTrace.workspaceOperationPlan();
        if (currentWorkspacePlanSchema
                && "AUTHORIZED_SINGLE".equals(observed.state())
                && !observed.matches(authoritative.workspaceOperationPlan())) {
            throw new IllegalArgumentException(
                    "durable workspace operation differs from the authoritative resolution");
        }
        if (redacted) return;
        MutationPlanSelection.requireCanonicalDurableManifest(
                canonical,
                authoritative.mutationTailPlan(),
                authoritative.sequentialMutationPlan(),
                authoritative.activeLexicalPaths(),
                policyTrace.lexicalDecisions(),
                policyTrace.lexicalRegions());
        requireCanonicalPolicySemantics(
                authoritative,
                policyTrace,
                currentWorkspacePlanSchema);
    }

    private static void requireCanonicalPolicySemantics(
            TaskResolution authoritative,
            TurnPolicyTrace actual,
            boolean currentWorkspacePlanSchema
    ) {
        TurnPolicyTrace expected =
                TurnPolicyTrace.lexicalValidationView(authoritative);
        LexicalManifestIdentity expectedManifest =
                LexicalManifestIdentity.from(expected);
        LexicalManifestIdentity actualManifest =
                LexicalManifestIdentity.from(actual);
        if (expectedManifest.decisions().size()
                != actualManifest.decisions().size()) {
            throw new IllegalArgumentException(
                    "durable lexical roles differ from the authoritative resolution");
        }
        for (int index = 0;
                index < actualManifest.decisions().size();
                index++) {
            DecisionManifestIdentity actualDecision =
                    actualManifest.decisions().get(index);
            if (!actualDecision.activeInResolution()) continue;
            DecisionManifestIdentity expectedDecision =
                    expectedManifest.decisions().get(index);
            if (!expectedDecision.activeInResolution()
                    || !expectedDecision.roleProjection().equals(
                            actualDecision.roleProjection())) {
                throw new IllegalArgumentException(
                        "durable lexical roles differ from the authoritative resolution");
            }
        }
    }

    private static TurnPolicyTrace policyTraceForPersistence(
            TurnPolicyTrace trace,
            String userInput
    ) {
        TurnPolicyTrace safe = trace == null ? TurnPolicyTrace.empty() : trace;
        WorkspaceOperationPlanEvidence workspace =
                safe.workspaceOperationPlan();
        if (!"NONE".equals(workspace.state())) return safe;
        if (!safe.lexicalDecisions().isEmpty()
                || !safe.lexicalRegions().isEmpty()) {
            return safe;
        }
        String canonical = userInput == null ? "" : userInput.strip();
        return safe.withWorkspaceOperationPlan(
                WorkspaceOperationPlanEvidence.from(
                        dev.talos.runtime.workspace
                                .WorkspaceOperationAuthorityPlan.none(
                                        canonical)));
    }

    private static void requireWorkspaceOperationTraceBinding(
            LocalTurnTrace trace,
            WorkspaceOperationPlanEvidence evidence
    ) {
        List<TurnTraceEvent> planEvents = trace.events().stream()
                .filter(event -> "WORKSPACE_OPERATION_PLAN_EVIDENCE_RECORDED"
                        .equals(event.type()))
                .toList();
        boolean expectedPlanEvent = !"NONE".equals(evidence.state());
        if (planEvents.size() != (expectedPlanEvent ? 1 : 0)
                || (expectedPlanEvent
                        && (!planEvents.getFirst().phase().isBlank()
                                || !planEvents.getFirst().toolName().isBlank()
                                || !planEvents.getFirst().data().equals(
                                        workspaceOperationPlanEvidenceToMap(
                                                evidence))))) {
            throw new IllegalArgumentException(
                    "local trace workspace-operation plan evidence does not match its turn record");
        }
        String operationId = evidence.operations().isEmpty()
                ? ""
                : evidence.operations().getFirst().operationId();
        Set<String> lifecycleTypes = Set.of(
                "WORKSPACE_OPERATION_APPROVAL_REQUIRED",
                "WORKSPACE_OPERATION_APPROVAL_GRANTED",
                "WORKSPACE_OPERATION_APPROVAL_DENIED",
                "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                "WORKSPACE_OPERATION_CHECKPOINT_SKIPPED",
                "WORKSPACE_OPERATION_CHECKPOINT_FAILED",
                "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED",
                "WORKSPACE_OPERATION_EXECUTION_FAILED",
                "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED",
                "WORKSPACE_OPERATION_VERIFICATION_FAILED",
                "WORKSPACE_OPERATION_OBLIGATION_CONSUMED");
        String lifecycleToolCallId = "";
        String authorizedToolCallId = "";
        List<String> lifecycleOrder = new java.util.ArrayList<>();
        for (TurnTraceEvent event : trace.events()) {
            boolean authorityDecision =
                    "WORKSPACE_OPERATION_AUTHORITY_DECIDED"
                            .equals(event.type());
            boolean lifecycle = lifecycleTypes.contains(event.type());
            if (!authorityDecision && !lifecycle) {
                continue;
            }
            if (authorityDecision) {
                requireExactKeys(
                        event.data(),
                        operationId.isEmpty()
                                ? WORKSPACE_AUTHORITY_DECISION_KEYS
                                : WORKSPACE_AUTHORITY_DECISION_WITH_OPERATION_KEYS,
                        "workspace-operation authority decision");
                String planState = java.util.Objects.toString(
                        event.data().get("planState"), "");
                String decision = java.util.Objects.toString(
                        event.data().get("decision"), "");
                String decisionToolCallId = java.util.Objects.toString(
                        event.data().get("toolCallId"), "");
                if (!evidence.state().equals(planState)
                        || !WORKSPACE_AUTHORITY_DECISIONS.contains(decision)
                        || decisionToolCallId.isBlank()
                        || ("AUTHORIZED".equals(decision)
                                && !"AUTHORIZED_SINGLE".equals(planState))) {
                    throw new IllegalArgumentException(
                            "workspace-operation authority decision differs from its turn record");
                }
                if ("AUTHORIZED".equals(decision)) {
                    if (!authorizedToolCallId.isBlank()
                            && !authorizedToolCallId.equals(
                                    decisionToolCallId)) {
                        throw new IllegalArgumentException(
                                "workspace-operation authority was granted to multiple parsed calls");
                    }
                    authorizedToolCallId = decisionToolCallId;
                }
            }
            if (lifecycle) {
                requireExactKeys(
                        event.data(),
                        WORKSPACE_LIFECYCLE_KEYS,
                        "workspace-operation lifecycle event");
            }
            if (!evidence.planId().equals(java.util.Objects.toString(
                            event.data().get("planId"), ""))
                    || (!operationId.isEmpty()
                            && !operationId.equals(java.util.Objects.toString(
                                    event.data().get("operationId"), "")))
                    || (operationId.isEmpty()
                            && event.data().containsKey("operationId"))) {
                throw new IllegalArgumentException(
                        "local trace workspace-operation lifecycle differs from its turn record");
            }
            if (lifecycle && !"AUTHORIZED_SINGLE".equals(evidence.state())) {
                throw new IllegalArgumentException(
                        "non-authorizing workspace plan cannot record lifecycle events");
            }
            if (lifecycle) {
                String eventToolCallId = java.util.Objects.toString(
                        event.data().get("toolCallId"), "");
                if (eventToolCallId.isBlank()
                        || (!lifecycleToolCallId.isBlank()
                                && !lifecycleToolCallId.equals(
                                        eventToolCallId))) {
                    throw new IllegalArgumentException(
                            "workspace-operation lifecycle must retain one parsed-call identity");
                }
                lifecycleToolCallId = eventToolCallId;
                lifecycleOrder.add(event.type());
            }
        }
        if (!lifecycleToolCallId.isBlank()
                && !lifecycleToolCallId.equals(authorizedToolCallId)) {
            throw new IllegalArgumentException(
                    "workspace-operation lifecycle differs from its authority decision call");
        }
        requireConsumedWorkspaceOperationLifecycle(
                trace,
                lifecycleOrder,
                lifecycleToolCallId,
                evidence);
    }

    private static void requireConsumedWorkspaceOperationLifecycle(
            LocalTurnTrace trace,
            List<String> order,
            String toolCallId,
            WorkspaceOperationPlanEvidence evidence
    ) {
        int consumed = java.util.Collections.frequency(
                order,
                "WORKSPACE_OPERATION_OBLIGATION_CONSUMED");
        if (consumed == 0) {
            requireUnconsumedWorkspaceOperationLifecycle(
                    trace,
                    order,
                    toolCallId,
                    evidence);
            return;
        }
        if (consumed != 1
                || java.util.Collections.frequency(
                        order,
                        "WORKSPACE_OPERATION_APPROVAL_REQUIRED") > 1
                || java.util.Collections.frequency(
                        order,
                        "WORKSPACE_OPERATION_APPROVAL_GRANTED") != 1
                || countAny(
                        order,
                        "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                        "WORKSPACE_OPERATION_CHECKPOINT_SKIPPED") != 1
                || java.util.Collections.frequency(
                        order,
                        "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED") != 1
                || countAny(
                        order,
                        "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED",
                        "WORKSPACE_OPERATION_VERIFICATION_FAILED") != 1
                || order.contains("WORKSPACE_OPERATION_APPROVAL_DENIED")
                || order.contains("WORKSPACE_OPERATION_CHECKPOINT_FAILED")
                || order.contains("WORKSPACE_OPERATION_EXECUTION_FAILED")) {
            throw new IllegalArgumentException(
                    "consumed workspace operation lacks one complete lifecycle");
        }
        int approval = order.indexOf(
                "WORKSPACE_OPERATION_APPROVAL_GRANTED");
        int approvalRequired = order.indexOf(
                "WORKSPACE_OPERATION_APPROVAL_REQUIRED");
        int checkpoint = firstIndexOfAny(
                order,
                "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                "WORKSPACE_OPERATION_CHECKPOINT_SKIPPED");
        int execution = order.indexOf(
                "WORKSPACE_OPERATION_EXECUTION_SUCCEEDED");
        int consumedIndex = order.indexOf(
                "WORKSPACE_OPERATION_OBLIGATION_CONSUMED");
        int verification = firstIndexOfAny(
                order,
                "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED",
                "WORKSPACE_OPERATION_VERIFICATION_FAILED");
        if (!((approvalRequired < 0 || approvalRequired < approval)
                && approval < checkpoint
                && checkpoint < execution
                && execution < consumedIndex
                && consumedIndex < verification
                && verification == order.size() - 1)) {
            throw new IllegalArgumentException(
                    "workspace-operation lifecycle events are out of order");
        }
        boolean genericApproval = trace.events().stream().anyMatch(event ->
                "APPROVAL_GRANTED".equals(event.type())
                        && toolCallId.equals(java.util.Objects.toString(
                                event.data().get("toolCallId"), "")));
        boolean genericExecution = trace.events().stream().anyMatch(event ->
                "TOOL_EXECUTED".equals(event.type())
                        && Boolean.TRUE.equals(event.data().get("success"))
                        && toolCallId.equals(java.util.Objects.toString(
                                event.data().get("toolCallId"), "")));
        String planId = evidence.planId();
        String operationId = evidence.operations().getFirst().operationId();
        boolean checkpointBinding = trace.events().stream().anyMatch(event ->
                ("CHECKPOINT_CREATED".equals(event.type())
                                 || "CHECKPOINT_SKIPPED".equals(event.type()))
                        && planId.equals(java.util.Objects.toString(
                                event.data().get("authorityPlanId"), ""))
                        && operationId.equals(java.util.Objects.toString(
                                event.data().get("operationId"), ""))
                        && toolCallId.equals(java.util.Objects.toString(
                                event.data().get("toolCallId"), "")));
        if (!genericApproval || !genericExecution || !checkpointBinding) {
            throw new IllegalArgumentException(
                    "workspace-operation lifecycle is not joined to approval, checkpoint, and execution evidence");
        }
    }

    private static void requireUnconsumedWorkspaceOperationLifecycle(
            LocalTurnTrace trace,
            List<String> order,
            String toolCallId,
            WorkspaceOperationPlanEvidence evidence
    ) {
        if (order.isEmpty()) return;
        int denied = java.util.Collections.frequency(
                order,
                "WORKSPACE_OPERATION_APPROVAL_DENIED");
        int checkpointFailed = java.util.Collections.frequency(
                order,
                "WORKSPACE_OPERATION_CHECKPOINT_FAILED");
        int executionFailed = java.util.Collections.frequency(
                order,
                "WORKSPACE_OPERATION_EXECUTION_FAILED");
        if (denied + checkpointFailed + executionFailed != 1
                || countAny(
                        order,
                        "WORKSPACE_OPERATION_VERIFICATION_SUCCEEDED",
                        "WORKSPACE_OPERATION_VERIFICATION_FAILED") != 0
                || java.util.Collections.frequency(
                        order,
                        "WORKSPACE_OPERATION_APPROVAL_REQUIRED") > 1) {
            throw new IllegalArgumentException(
                    "unconsumed workspace operation lacks one terminal lifecycle outcome");
        }
        String terminal;
        if (denied == 1) {
            terminal = "WORKSPACE_OPERATION_APPROVAL_DENIED";
            int approvalRequired = order.indexOf(
                    "WORKSPACE_OPERATION_APPROVAL_REQUIRED");
            if ((approvalRequired >= 0
                            && approvalRequired >= order.indexOf(terminal))
                    || order.contains("WORKSPACE_OPERATION_APPROVAL_GRANTED")
                    || countAny(
                            order,
                            "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                            "WORKSPACE_OPERATION_CHECKPOINT_SKIPPED") != 0
                    || order.contains("WORKSPACE_OPERATION_EXECUTION_SUCCEEDED")
                    || order.contains("WORKSPACE_OPERATION_EXECUTION_FAILED")) {
                throw new IllegalArgumentException(
                        "denied workspace operation contains later lifecycle evidence");
            }
            requireGenericToolEvent(
                    trace,
                    "APPROVAL_DENIED",
                    toolCallId,
                    null);
        } else if (checkpointFailed == 1) {
            terminal = "WORKSPACE_OPERATION_CHECKPOINT_FAILED";
            requireOneGrantedBefore(order, terminal);
            if (order.contains("WORKSPACE_OPERATION_EXECUTION_SUCCEEDED")
                    || order.contains("WORKSPACE_OPERATION_EXECUTION_FAILED")) {
                throw new IllegalArgumentException(
                        "checkpoint-failed workspace operation contains execution evidence");
            }
            requireGenericToolEvent(
                    trace,
                    "APPROVAL_GRANTED",
                    toolCallId,
                    null);
            requireCheckpointTraceBinding(
                    trace,
                    evidence,
                    Set.of("CHECKPOINT_FAILED"),
                    toolCallId);
        } else {
            terminal = "WORKSPACE_OPERATION_EXECUTION_FAILED";
            requireOneGrantedBefore(order, terminal);
            int checkpoint = firstIndexOfAny(
                    order,
                    "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                    "WORKSPACE_OPERATION_CHECKPOINT_SKIPPED");
            if (countAny(
                            order,
                            "WORKSPACE_OPERATION_CHECKPOINT_CREATED",
                            "WORKSPACE_OPERATION_CHECKPOINT_SKIPPED") != 1
                    || checkpoint >= order.indexOf(terminal)) {
                throw new IllegalArgumentException(
                        "execution-failed workspace operation lacks prior checkpoint evidence");
            }
            requireGenericToolEvent(
                    trace,
                    "APPROVAL_GRANTED",
                    toolCallId,
                    null);
            requireGenericToolEvent(
                    trace,
                    "TOOL_EXECUTED",
                    toolCallId,
                    Boolean.FALSE);
            requireCheckpointTraceBinding(
                    trace,
                    evidence,
                    Set.of("CHECKPOINT_CREATED", "CHECKPOINT_SKIPPED"),
                    toolCallId);
        }
        if (order.indexOf(terminal) != order.size() - 1) {
            throw new IllegalArgumentException(
                    "workspace-operation terminal lifecycle event must be final");
        }
    }

    private static void requireOneGrantedBefore(
            List<String> order,
            String terminal
    ) {
        if (java.util.Collections.frequency(
                        order,
                        "WORKSPACE_OPERATION_APPROVAL_GRANTED") != 1
                || java.util.Collections.frequency(
                        order,
                        "WORKSPACE_OPERATION_APPROVAL_REQUIRED") > 1
                || (order.indexOf("WORKSPACE_OPERATION_APPROVAL_REQUIRED") >= 0
                        && order.indexOf("WORKSPACE_OPERATION_APPROVAL_REQUIRED")
                                >= order.indexOf(
                                        "WORKSPACE_OPERATION_APPROVAL_GRANTED"))
                || order.indexOf("WORKSPACE_OPERATION_APPROVAL_GRANTED")
                        >= order.indexOf(terminal)
                || order.contains("WORKSPACE_OPERATION_APPROVAL_DENIED")) {
            throw new IllegalArgumentException(
                    "workspace-operation failure lacks one prior approval grant");
        }
    }

    private static void requireGenericToolEvent(
            LocalTurnTrace trace,
            String type,
            String toolCallId,
            Boolean success
    ) {
        boolean found = trace.events().stream().anyMatch(event ->
                type.equals(event.type())
                        && toolCallId.equals(java.util.Objects.toString(
                                event.data().get("toolCallId"), ""))
                        && (success == null
                                || success.equals(event.data().get("success"))));
        if (!found) {
            throw new IllegalArgumentException(
                    "workspace-operation lifecycle lacks matching " + type
                            + " evidence");
        }
    }

    private static void requireCheckpointTraceBinding(
            LocalTurnTrace trace,
            WorkspaceOperationPlanEvidence evidence,
            Set<String> types,
            String toolCallId
    ) {
        String planId = evidence.planId();
        String operationId = evidence.operations().getFirst().operationId();
        boolean found = trace.events().stream().anyMatch(event ->
                types.contains(event.type())
                        && planId.equals(java.util.Objects.toString(
                                event.data().get("authorityPlanId"), ""))
                        && operationId.equals(java.util.Objects.toString(
                                event.data().get("operationId"), ""))
                        && toolCallId.equals(java.util.Objects.toString(
                                event.data().get("toolCallId"), "")));
        if (!found) {
            throw new IllegalArgumentException(
                    "workspace-operation lifecycle lacks matching checkpoint evidence");
        }
    }

    private static int countAny(
            List<String> values,
            String first,
            String second
    ) {
        return java.util.Collections.frequency(values, first)
                + java.util.Collections.frequency(values, second);
    }

    private static int firstIndexOfAny(
            List<String> values,
            String first,
            String second
    ) {
        int firstIndex = values.indexOf(first);
        int secondIndex = values.indexOf(second);
        if (firstIndex < 0) return secondIndex;
        if (secondIndex < 0) return firstIndex;
        return Math.min(firstIndex, secondIndex);
    }

    private static TaskResolution authoritativeResolution(
            String request,
            Map<RequestIdentity, AuthoritativeManifest> manifestCache
    ) {
        String canonical = request == null ? "" : request.strip();
        MutationPlanSelection.SourceRequestIdentity sourceIdentity =
                MutationPlanSelection.sourceRequestIdentity(canonical);
        RequestIdentity identity = new RequestIdentity(
                sourceIdentity.sha256(),
                sourceIdentity.characters(),
                sourceIdentity.bytes());
        Map<RequestIdentity, AuthoritativeManifest> cache =
                manifestCache == null ? new LinkedHashMap<>() : manifestCache;
        AuthoritativeManifest cached = cache.get(identity);
        if (cached != null && !cached.canonicalRequest().equals(canonical)) {
            throw new IllegalArgumentException(
                    "canonical request identity is conflicted during manifest validation");
        }
        if (cached == null) {
            cached = new AuthoritativeManifest(
                    canonical,
                    TaskContractResolver.resolveFromUserRequest(canonical));
            cache.put(identity, cached);
        }
        return cached.resolution();
    }

    private record AuthoritativeManifest(
            String canonicalRequest,
            TaskResolution resolution
    ) {
        private AuthoritativeManifest {
            canonicalRequest = canonicalRequest == null
                    ? ""
                    : canonicalRequest;
            if (resolution == null) {
                throw new IllegalArgumentException(
                        "authoritative manifest resolution must not be null");
            }
        }
    }

    private static Map<String, Object> policyTraceToMap(TurnPolicyTrace trace) {
        TurnPolicyTrace safe = trace == null ? TurnPolicyTrace.empty() : trace;
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("taskType", safe.taskType());
        out.put("mutationAllowed", safe.mutationAllowed());
        out.put("mutationRequested", safe.mutationRequested());
        out.put("verificationRequired", safe.verificationRequired());
        out.put("expectedTargets", safe.expectedTargets());
        out.put("forbiddenTargets", safe.forbiddenTargets());
        out.put("initialPhase", safe.initialPhase());
        out.put("finalPhase", safe.finalPhase());
        out.put("nativeTools", safe.nativeTools());
        out.put("promptTools", safe.promptTools());
        out.put("blocks", safe.blocks());
        out.put("classificationReason", safe.classificationReason());
        out.put("lexicalEvidenceSchemaVersion",
                LEXICAL_EVIDENCE_SCHEMA_VERSION);
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
        List<Map<String, Object>> lexicalDecisions =
                new java.util.ArrayList<>();
        for (TurnPolicyTrace.LexicalDecisionEvidence decision
                : safe.lexicalDecisions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ordinal", decision.ordinal());
            row.put("owner", decision.owner());
            row.put("path", decision.path());
            row.put("pathSha256", decision.pathSha256());
            row.put("storedPathSha256", decision.storedPathSha256());
            row.put("authority", decision.authority());
            row.put("action", decision.action());
            row.put("actionFamily", decision.actionFamily());
            row.put("source", decision.source());
            row.put("evidenceState", decision.evidenceState());
            row.put("sourceRequestSha256",
                    decision.sourceRequestSha256());
            row.put("sourceRequestChars", decision.sourceRequestChars());
            row.put("sourceRequestBytes", decision.sourceRequestBytes());
            row.put("actionStartOffset", decision.actionStartOffset());
            row.put("actionEndOffset", decision.actionEndOffset());
            row.put("targetStartOffset", decision.targetStartOffset());
            row.put("startOffset", decision.startOffset());
            row.put("authorityEndOffset", decision.authorityEndOffset());
            row.put("endOffset", decision.endOffset());
            row.put("selectedForPath", decision.selectedForPath());
            row.put("activeInResolution", decision.activeInResolution());
            row.put("sourceSpanSha256", decision.sourceSpanSha256());
            row.put("occurrenceSha256", decision.occurrenceSha256());
            lexicalDecisions.add(row);
        }
        out.put("lexicalDecisions", lexicalDecisions);
        List<Map<String, Object>> lexicalRegions = new java.util.ArrayList<>();
        for (TurnPolicyTrace.LexicalRegionEvidence region
                : safe.lexicalRegions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ordinal", region.ordinal());
            row.put("owner", region.owner());
            row.put("kind", region.kind());
            row.put("source", region.source());
            row.put("evidenceState", region.evidenceState());
            row.put("sourceRequestSha256", region.sourceRequestSha256());
            row.put("sourceRequestChars", region.sourceRequestChars());
            row.put("sourceRequestBytes", region.sourceRequestBytes());
            row.put("action", region.action());
            row.put("pathSha256", region.pathSha256());
            row.put("startOffset", region.startOffset());
            row.put("endOffset", region.endOffset());
            row.put("sourceSpanSha256", region.sourceSpanSha256());
            row.put("regionSha256", region.regionSha256());
            lexicalRegions.add(row);
        }
        out.put("lexicalRegions", lexicalRegions);
        out.put("workspaceOperationPlan",
                workspaceOperationPlanEvidenceToMap(
                        safe.workspaceOperationPlan()));
        return out;
    }

    private static Map<String, Object> workspaceOperationPlanEvidenceToMap(
            WorkspaceOperationPlanEvidence evidence
    ) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("schemaVersion", evidence.schemaVersion());
        out.put("planId", evidence.planId());
        out.put("state", evidence.state());
        out.put("reason", evidence.reason());
        out.put("canonicalRequestSha256", evidence.canonicalRequestSha256());
        out.put("canonicalRequestUtf8Bytes",
                evidence.canonicalRequestUtf8Bytes());
        List<Map<String, Object>> operations = new java.util.ArrayList<>();
        for (WorkspaceOperationPlanEvidence.OperationEvidence operation
                : evidence.operations()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("ordinal", operation.ordinal());
            row.put("operationId", operation.operationId());
            row.put("owner", operation.owner());
            row.put("kind", operation.kind());
            row.put("toolName", operation.toolName());
            row.put("overwritePolicy", operation.overwritePolicy());
            row.put("recursivePolicy", operation.recursivePolicy());
            row.put("parentPolicy", operation.parentPolicy());
            row.put("actionStartOffset", operation.actionStartOffset());
            row.put("actionEndOffset", operation.actionEndOffset());
            row.put("sourceStartOffset", operation.sourceStartOffset());
            row.put("sourceEndOffset", operation.sourceEndOffset());
            row.put("sourceSpanSha256", operation.sourceSpanSha256());
            List<Map<String, Object>> operands = new java.util.ArrayList<>();
            for (WorkspaceOperationPlanEvidence.OperandEvidence operand
                    : operation.operands()) {
                Map<String, Object> operandRow = new LinkedHashMap<>();
                operandRow.put("ordinal", operand.ordinal());
                operandRow.put("role", operand.role());
                operandRow.put("lexicalValueSha256",
                        operand.lexicalValueSha256());
                operandRow.put("effectivePathSha256",
                        operand.effectivePathSha256());
                operandRow.put("valueStartOffset", operand.valueStartOffset());
                operandRow.put("valueEndOffset", operand.valueEndOffset());
                operands.add(operandRow);
            }
            row.put("operands", operands);
            operations.add(row);
        }
        out.put("operations", operations);
        return out;
    }

    private static TurnPolicyTrace policyTraceFrom(
            Object raw,
            String canonicalRequest
    ) {
        if (!(raw instanceof Map<?, ?> map)) return TurnPolicyTrace.empty();
        boolean hasSchema = map.containsKey("lexicalEvidenceSchemaVersion");
        boolean hasDecisions = map.containsKey("lexicalDecisions");
        boolean hasRegions = map.containsKey("lexicalRegions");
        int policySchema = hasSchema
                ? exactInt(
                        map.get("lexicalEvidenceSchemaVersion"),
                        "lexicalEvidenceSchemaVersion")
                : 0;
        if (!hasSchema) {
            if (hasDecisions
                    || hasRegions
                    || map.containsKey("workspaceOperationPlan")) {
                throw new IllegalArgumentException(
                        "unversioned policy trace cannot carry lexical evidence");
            }
        } else if ((policySchema
                                != LEGACY_LEXICAL_EVIDENCE_SCHEMA_VERSION
                        && policySchema != LEXICAL_EVIDENCE_SCHEMA_VERSION)
                || !hasDecisions
                || !hasRegions
                || (policySchema == LEXICAL_EVIDENCE_SCHEMA_VERSION
                        && !map.containsKey("workspaceOperationPlan"))) {
            throw new IllegalArgumentException(
                    "versioned policy trace requires its complete evidence shape");
        }
        if (hasSchema) {
            requireExactKeys(
                    map,
                    policySchema == LEXICAL_EVIDENCE_SCHEMA_VERSION
                            ? POLICY_TRACE_V2_KEYS
                            : POLICY_TRACE_V1_KEYS,
                    "policy trace v" + policySchema);
            List<?> rawDecisions = exactList(
                    map.get("lexicalDecisions"),
                    "lexicalDecisions");
            List<?> rawRegions = exactList(
                    map.get("lexicalRegions"),
                    "lexicalRegions");
            requireLexicalRowLimit(rawDecisions.size(), rawRegions.size());
        }
        List<TurnPolicyTrace.LexicalDecisionEvidence> decisions = hasSchema
                ? lexicalDecisionsFrom(map.get("lexicalDecisions"))
                : List.of();
        List<TurnPolicyTrace.LexicalRegionEvidence> regions = hasSchema
                ? lexicalRegionsFrom(map.get("lexicalRegions"))
                : List.of();
        List<TurnPolicyTrace.RolefulTarget> rolefulTargets =
                rolefulTargetsFrom(map.get("rolefulTargets"), hasSchema);
        String taskType = hasSchema
                ? exactText(map, "taskType")
                : stringVal(map, "taskType", "UNKNOWN");
        boolean mutationRequested = hasSchema
                ? exactBoolean(map, "mutationRequested")
                : map.containsKey("mutationRequested")
                        ? boolVal(map, "mutationRequested")
                : TurnPolicyTrace.inferMutationRequested(
                        taskType,
                        rolefulTargets,
                        decisions);
        String request = canonicalRequest == null
                ? ""
                : canonicalRequest.strip();
        WorkspaceOperationPlanEvidence workspacePlan =
                policySchema == LEXICAL_EVIDENCE_SCHEMA_VERSION
                        ? workspaceOperationPlanEvidenceFrom(
                                map.get("workspaceOperationPlan"))
                        : WorkspaceOperationPlanEvidence.from(
                                dev.talos.runtime.workspace
                                        .WorkspaceOperationAuthorityPlan
                                        .none(request));
        return new TurnPolicyTrace(
                taskType,
                hasSchema
                        ? exactBoolean(map, "mutationAllowed")
                        : boolVal(map, "mutationAllowed"),
                hasSchema
                        ? exactBoolean(map, "verificationRequired")
                        : boolVal(map, "verificationRequired"),
                hasSchema
                        ? exactStringList(map, "expectedTargets")
                        : stringList(map.get("expectedTargets")),
                hasSchema
                        ? exactStringList(map, "forbiddenTargets")
                        : stringList(map.get("forbiddenTargets")),
                hasSchema
                        ? exactText(map, "initialPhase")
                        : stringVal(map, "initialPhase", "unknown"),
                hasSchema
                        ? exactText(map, "finalPhase")
                        : stringVal(map, "finalPhase", "unknown"),
                hasSchema
                        ? exactStringList(map, "nativeTools")
                        : stringList(map.get("nativeTools")),
                hasSchema
                        ? exactStringList(map, "promptTools")
                        : stringList(map.get("promptTools")),
                hasSchema
                        ? exactStringList(map, "blocks")
                        : stringList(map.get("blocks")),
                hasSchema
                        ? exactText(map, "classificationReason")
                        : stringVal(map, "classificationReason", ""),
                rolefulTargets,
                decisions,
                regions,
                mutationRequested,
                workspacePlan);
    }

    private static WorkspaceOperationPlanEvidence
            workspaceOperationPlanEvidenceFrom(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(
                    "workspaceOperationPlan must be an object");
        }
        requireExactKeys(
                map,
                WORKSPACE_PLAN_EVIDENCE_KEYS,
                "workspace-operation plan evidence");
        List<?> rawOperations = exactList(
                map.get("operations"),
                "workspaceOperationPlan.operations");
        if (rawOperations.size() > 1) {
            throw new IllegalArgumentException(
                    "workspace-operation plan evidence contains too many operations");
        }
        List<WorkspaceOperationPlanEvidence.OperationEvidence> operations =
                new java.util.ArrayList<>();
        for (Object rawOperation : rawOperations) {
            if (!(rawOperation instanceof Map<?, ?> operation)) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence entry must be an object");
            }
            requireExactKeys(
                    operation,
                    WORKSPACE_OPERATION_EVIDENCE_KEYS,
                    "workspace-operation evidence");
            List<?> rawOperands = exactList(
                    operation.get("operands"),
                    "workspaceOperationPlan.operations.operands");
            if (rawOperands.size() > 2) {
                throw new IllegalArgumentException(
                        "workspace-operation evidence contains too many operands");
            }
            List<WorkspaceOperationPlanEvidence.OperandEvidence> operands =
                    new java.util.ArrayList<>();
            for (Object rawOperand : rawOperands) {
                if (!(rawOperand instanceof Map<?, ?> operand)) {
                    throw new IllegalArgumentException(
                            "workspace-operation operand evidence must be an object");
                }
                requireExactKeys(
                        operand,
                        WORKSPACE_OPERAND_EVIDENCE_KEYS,
                        "workspace-operation operand evidence");
                operands.add(new WorkspaceOperationPlanEvidence.OperandEvidence(
                        exactInt(operand.get("ordinal"), "ordinal"),
                        exactText(operand, "role"),
                        exactText(operand, "lexicalValueSha256"),
                        exactText(operand, "effectivePathSha256"),
                        exactInt(operand.get("valueStartOffset"),
                                "valueStartOffset"),
                        exactInt(operand.get("valueEndOffset"),
                                "valueEndOffset")));
            }
            operations.add(new WorkspaceOperationPlanEvidence.OperationEvidence(
                    exactInt(operation.get("ordinal"), "ordinal"),
                    exactText(operation, "operationId"),
                    exactText(operation, "owner"),
                    exactText(operation, "kind"),
                    exactText(operation, "toolName"),
                    exactText(operation, "overwritePolicy"),
                    exactText(operation, "recursivePolicy"),
                    exactText(operation, "parentPolicy"),
                    exactInt(operation.get("actionStartOffset"),
                            "actionStartOffset"),
                    exactInt(operation.get("actionEndOffset"),
                            "actionEndOffset"),
                    exactInt(operation.get("sourceStartOffset"),
                            "sourceStartOffset"),
                    exactInt(operation.get("sourceEndOffset"),
                            "sourceEndOffset"),
                    exactText(operation, "sourceSpanSha256"),
                    operands));
        }
        return new WorkspaceOperationPlanEvidence(
                exactInt(map.get("schemaVersion"), "schemaVersion"),
                exactText(map, "planId"),
                exactText(map, "state"),
                exactText(map, "reason"),
                exactText(map, "canonicalRequestSha256"),
                exactInt(map.get("canonicalRequestUtf8Bytes"),
                        "canonicalRequestUtf8Bytes"),
                operations);
    }

    private static List<TurnPolicyTrace.LexicalDecisionEvidence>
            lexicalDecisionsFrom(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "lexicalDecisions must be an array when present");
        }
        if (list.size() > MAX_LEXICAL_DECISIONS) {
            throw new IllegalArgumentException("too many lexical decisions");
        }
        List<TurnPolicyTrace.LexicalDecisionEvidence> out =
                new java.util.ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        "lexicalDecisions entries must be objects");
            }
            requireExactKeys(
                    map,
                    LEXICAL_DECISION_KEYS,
                    "lexical decision");
            out.add(new TurnPolicyTrace.LexicalDecisionEvidence(
                    exactInt(map.get("ordinal"), "ordinal"),
                    exactString(map, "owner"),
                    exactString(map, "path"),
                    exactString(map, "pathSha256"),
                    exactString(map, "storedPathSha256"),
                    exactString(map, "authority"),
                    exactString(map, "action"),
                    exactString(map, "actionFamily"),
                    exactString(map, "source"),
                    exactString(map, "evidenceState"),
                    exactString(map, "sourceRequestSha256"),
                    exactInt(map.get("sourceRequestChars"),
                            "sourceRequestChars"),
                    exactInt(map.get("sourceRequestBytes"),
                            "sourceRequestBytes"),
                    exactInt(
                            map.get("actionStartOffset"),
                            "actionStartOffset"),
                    exactInt(
                            map.get("actionEndOffset"),
                            "actionEndOffset"),
                    exactInt(
                            map.get("targetStartOffset"),
                            "targetStartOffset"),
                    exactInt(map.get("startOffset"), "startOffset"),
                    exactInt(
                            map.get("authorityEndOffset"),
                            "authorityEndOffset"),
                    exactInt(map.get("endOffset"), "endOffset"),
                    exactBoolean(map, "selectedForPath"),
                    exactBoolean(map, "activeInResolution"),
                    exactString(map, "sourceSpanSha256"),
                    exactString(map, "occurrenceSha256")));
        }
        return List.copyOf(out);
    }

    private static List<TurnPolicyTrace.LexicalRegionEvidence>
            lexicalRegionsFrom(Object raw) {
        if (raw == null) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    "lexicalRegions must be an array when present");
        }
        if (list.size() > MAX_LEXICAL_REGIONS) {
            throw new IllegalArgumentException("too many lexical regions");
        }
        List<TurnPolicyTrace.LexicalRegionEvidence> out =
                new java.util.ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException(
                        "lexicalRegions entries must be objects");
            }
            requireExactKeys(
                    map,
                    LEXICAL_REGION_KEYS,
                    "lexical region");
            out.add(new TurnPolicyTrace.LexicalRegionEvidence(
                    exactInt(map.get("ordinal"), "ordinal"),
                    exactString(map, "owner"),
                    exactString(map, "kind"),
                    exactString(map, "source"),
                    exactString(map, "evidenceState"),
                    exactString(map, "sourceRequestSha256"),
                    exactInt(map.get("sourceRequestChars"),
                            "sourceRequestChars"),
                    exactInt(map.get("sourceRequestBytes"),
                            "sourceRequestBytes"),
                    exactString(map, "action"),
                    exactString(map, "pathSha256"),
                    exactInt(map.get("startOffset"), "startOffset"),
                    exactInt(map.get("endOffset"), "endOffset"),
                    exactString(map, "sourceSpanSha256"),
                    exactString(map, "regionSha256")));
        }
        return List.copyOf(out);
    }

    private static void requireLexicalRowLimit(int decisions, int regions) {
        if (decisions < 0 || regions < 0
                || (long) decisions + regions
                        > SequentialMutationClausePlan.MAX_EVIDENCE_ROWS) {
            throw new IllegalArgumentException(
                    "combined lexical evidence exceeds its row bound");
        }
    }

    private static String exactString(Map<?, ?> map, String field) {
        Object value = map == null ? null : map.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON string");
        }
        return text;
    }

    private static boolean exactBoolean(Map<?, ?> map, String field) {
        Object value = map == null ? null : map.get(field);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON boolean");
        }
        return flag;
    }

    private static List<TurnPolicyTrace.RolefulTarget> rolefulTargetsFrom(
            Object raw,
            boolean strict
    ) {
        if (raw == null && !strict) return List.of();
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException("rolefulTargets must be an array when present");
        }
        if (list.size()
                > MutationPlanSelection.MAX_DURABLE_ROLEFUL_TARGETS) {
            throw new IllegalArgumentException("too many roleful targets");
        }
        List<TurnPolicyTrace.RolefulTarget> out = new java.util.ArrayList<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> map)) {
                throw new IllegalArgumentException("rolefulTargets entries must be objects");
            }
            if (strict) {
                requireExactKeys(
                        map,
                        ROLEFUL_TARGET_V1_KEYS,
                        "roleful target v1");
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
            String path = strict
                    ? exactText(map, "path")
                    : stringVal(map, "path", "");
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
                    strict
                            ? exactText(map, "reason")
                            : stringVal(map, "reason", ""),
                    sourceText,
                    confidence,
                    start,
                    authorityEnd,
                    end));
        }
        return out;
    }

    private static List<?> exactList(Object raw, String field) {
        if (!(raw instanceof List<?> list)) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON array");
        }
        return list;
    }

    private static String exactText(Map<?, ?> map, String field) {
        Object value = map == null ? null : map.get(field);
        if (!(value instanceof String text)) {
            throw new IllegalArgumentException(
                    field + " must be an exact JSON string");
        }
        return text;
    }

    private static List<String> exactStringList(
            Map<?, ?> map,
            String field
    ) {
        List<?> raw = exactList(map == null ? null : map.get(field), field);
        if (raw.size() > MAX_POLICY_STRING_LIST) {
            throw new IllegalArgumentException(
                    field + " exceeds its bounded entry count");
        }
        java.util.ArrayList<String> values = new java.util.ArrayList<>(
                raw.size());
        for (Object value : raw) {
            if (!(value instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException(
                        field + " entries must be nonblank JSON strings");
            }
            values.add(text);
        }
        return List.copyOf(values);
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
            byte[] expected = reservationMarker(sessionId);
            byte[] observed;
            try (InputStream input =
                         NoFollowPathPolicy.openUnlinkedRegularFile(reservation)) {
                observed = input.readNBytes(expected.length + 1);
            }
            if (!java.util.Arrays.equals(observed, expected)) {
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
    public TurnLogInventoryObservation observeTurnLogInventory(
            String workspaceId
    ) {
        if (!safeSessionId(workspaceId)) {
            return TurnLogInventoryObservation.unavailable();
        }
        java.util.LinkedHashSet<String> turnLogIds =
                new java.util.LinkedHashSet<>();
        try {
            NoFollowPathPolicy.GuardedRoot guard =
                    NoFollowPathPolicy.bindExistingDirectory(sessionsDir);
            int entries = 0;
            try (var stream = Files.list(guard.path())) {
                for (Path path : (Iterable<Path>) stream::iterator) {
                    entries++;
                    if (entries
                            > diagnosticObservationLimits.maxSessionEntries()) {
                        return TurnLogInventoryObservation.unavailable();
                    }
                    String name = path.getFileName().toString();
                    if (!hasTurnLogSuffix(name)) continue;
                    String id = name.substring(
                            0,
                            name.length() - TURNS_SUFFIX.length());
                    if (!belongsToInventoryNamespace(id, workspaceId)) {
                        continue;
                    }
                    if (!name.endsWith(TURNS_SUFFIX)) {
                        return TurnLogInventoryObservation.unavailable();
                    }
                    if (!safeSessionId(id)
                            || guard.inspect(path)
                                    != NoFollowPathPolicy.EntryType.REGULAR_FILE) {
                        return TurnLogInventoryObservation.unavailable();
                    }
                    if (!turnLogIds.add(id)) {
                        return TurnLogInventoryObservation.unavailable();
                    }
                    if (turnLogIds.size()
                            > diagnosticObservationLimits.maxSessionLogs()) {
                        return TurnLogInventoryObservation.unavailable();
                    }
                }
            }
        } catch (IOException | java.io.UncheckedIOException unavailable) {
            return TurnLogInventoryObservation.unavailable();
        }
        return TurnLogInventoryObservation.exact(
                turnLogIds.stream().sorted().toList());
    }

    private static boolean hasTurnLogSuffix(String name) {
        return name != null
                && name.length() >= TURNS_SUFFIX.length()
                && name.regionMatches(
                        true,
                        name.length() - TURNS_SUFFIX.length(),
                        TURNS_SUFFIX,
                        0,
                        TURNS_SUFFIX.length());
    }

    private static boolean belongsToInventoryNamespace(
            String sessionId,
            String workspaceId
    ) {
        if (sessionId == null || workspaceId == null
                || sessionId.length() < workspaceId.length()
                || !sessionId.regionMatches(
                        true,
                        0,
                        workspaceId,
                        0,
                        workspaceId.length())) {
            return false;
        }
        return sessionId.length() == workspaceId.length()
                || sessionId.length() > workspaceId.length()
                        && sessionId.charAt(workspaceId.length()) == '-';
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

