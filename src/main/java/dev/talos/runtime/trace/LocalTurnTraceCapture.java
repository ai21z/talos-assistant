package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.ToolCallParser;
import dev.talos.runtime.TurnExecutionStage;
import dev.talos.runtime.TurnFailureClassifier;
import dev.talos.runtime.TurnSettlement;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.obligation.RequestObligation;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.runtime.verification.VerificationReport;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextLedgerSnapshot;
import dev.talos.core.tool.ToolProtocolText;
import dev.talos.core.util.RuntimeArtifactIdentity;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolContentMetadata;
import dev.talos.tools.ToolCall;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderResponseCapture;

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/** Thread-local recorder for the current turn's local trace v1 artifact. */
public final class LocalTurnTraceCapture {
    private static final ObjectMapper NATIVE_ARGUMENT_MAPPER = new ObjectMapper();

    private LocalTurnTraceCapture() {}

    static final class Bag {
        final LocalTurnTrace.Builder builder;
        final String traceId;
        final int turnNumber;
        boolean outcomeRecorded;
        boolean outcomeRendered;
        boolean routedAnswerRecorded;
        boolean verificationRecorded;
        String requestObligationSha256 = "";
        AnswerCandidate answerCandidate;
        OutcomeCandidate outcomeCandidate;
        ProviderResponseCapture.Receipt selectedResponseReceipt;
        ProviderResponseCapture.Response selectedProviderResponse;
        final Map<String, PendingProviderResponse> providerResponses = new LinkedHashMap<>();
        final Set<String> emittedProcessReceipts = new java.util.LinkedHashSet<>();
        final Set<AnswerCandidateCheckpoint> answerCandidateCheckpoints =
                Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<ProviderResponseCapture.Receipt, Set<Integer>> consumedNativeProposals =
                new IdentityHashMap<>();
        final Map<ProviderResponseCapture.Receipt, Set<Integer>> consumedTextProposals =
                new IdentityHashMap<>();
        String latestSelectableGenerationId = "";
        long providerAuthorityEpoch;
        long nextToolCallOrdinal;
        ParsedToolCallPermit activeToolCall;
        TurnExecutionStage executionStage = TurnExecutionStage.TURN_INITIALIZATION;

        Bag(LocalTurnTrace.Builder builder, String traceId, int turnNumber) {
            this.builder = builder;
            this.traceId = traceId == null ? "" : traceId;
            this.turnNumber = turnNumber;
        }
    }

    private static final ThreadLocal<Bag> HOLDER = new ThreadLocal<>();

    public enum ToolCallTransformationReason {
        MISSING_PATH_REPAIR,
        PROTECTED_ALIAS_NORMALIZATION,
        SOURCE_EVIDENCE_EXACT_REPAIR,
        APPEND_WRITE_TO_EDIT,
        MARKDOWN_COMMENTARY_SANITIZATION,
        EXACT_LITERAL_WRITE_CORRECTION,
        PATH_ARGUMENT_NORMALIZATION,
        WRITE_FORMAT_PRESERVATION
    }

    public enum AnswerTransformationReason {
        EXECUTOR_POLICY_SHAPING,
        PROVIDER_RESPONSE_NORMALIZATION,
        TASK_CONTRACT_TOOL_CALL_SUPPRESSION,
        MODE_PRESENTATION_LAYOUT,
        RETRIEVAL_CITATION_SUFFIX,
        SESSION_COMMIT_NORMALIZATION,
        TOOL_LOOP_ITERATION_LIMIT,
        TOOL_LOOP_FINALIZATION
    }

    public enum ProviderToolSuppressionReason {
        TASK_CONTRACT_BLOCKED_TOOL_CALLS
    }

    public enum ToolCallSource {
        PROVIDER_RESPONSE,
        RUNTIME_DETERMINISTIC,
        UNBOUND
    }

    public enum ToolCallParseFormat {
        NATIVE,
        JSON_TEXT,
        XML_COMPAT,
        UNBOUND
    }

    public enum ToolCallAuthorityKind {
        PROVIDER_NATIVE,
        PROVIDER_TEXT_JSON,
        RUNTIME_DETERMINISTIC
    }

    public enum RuntimeToolAuthorityReason {
        NONE,
        DIRECT_RUNTIME_API,
        READ_EVIDENCE_HANDOFF,
        EXPECTED_TARGET_SCOPE_REPAIR
    }

    public enum ToolCallAuthorityRejectionReason {
        TRACE_INACTIVE,
        LINEAGE_MISSING,
        LINEAGE_SCOPE_MISMATCH,
        LINEAGE_CALL_MISMATCH,
        PROVIDER_RECEIPT_MISSING,
        PROVIDER_RECEIPT_NOT_CURRENT,
        PROVIDER_GENERATION_STALE,
        PROVIDER_SOURCE_INELIGIBLE,
        PROVIDER_PURPOSE_INELIGIBLE,
        PROVIDER_TOOL_MODE_MISMATCH,
        PROVIDER_TOOL_SURFACE_UNBOUND,
        PROVIDER_DISPATCH_SURFACE_MISMATCH,
        PROVIDER_TOOL_SURFACE_MISMATCH,
        PROVIDER_TOOL_INELIGIBLE,
        PROVIDER_TERMINAL_INELIGIBLE,
        PROVIDER_RESPONSE_MISSING,
        PROVIDER_RESPONSE_MISMATCH,
        PROVIDER_PROPOSAL_ORDINAL_INVALID,
        PROVIDER_PROPOSAL_EVIDENCE_MISSING,
        PROVIDER_PROPOSAL_MISMATCH,
        PROVIDER_PROPOSAL_ALREADY_USED,
        TEXT_PARSER_LINEAGE_REQUIRED,
        TEXT_PROTOCOL_INELIGIBLE,
        XML_COMPATIBILITY_NOT_AUTHORIZED,
        RUNTIME_AUTHORITY_REASON_MISSING,
        RUNTIME_AUTHORITY_SCOPE_INVALID,
        ACTIVE_CALL_EXISTS
    }

    public enum AnswerCandidateRestorationReason {
        SPECULATIVE_RETRY_REJECTED,
        SPECULATIVE_RETRY_FAILED,
        SPECULATIVE_RETRY_EMPTY
    }

    /**
     * Opaque, one-use proof of an exact pre-route answer candidate.
     *
     * <p>The raw candidate remains process-local so it can be restored exactly;
     * it is never exposed by this type or written to the trace artifact.
     */
    public static final class AnswerCandidateCheckpoint {
        private final Bag owner;
        private final Thread ownerThread;
        private final AnswerCandidate candidate;
        private final String answerHash;
        private final int answerChars;
        private final int answerBytes;

        private AnswerCandidateCheckpoint(Bag owner, AnswerCandidate candidate) {
            this.owner = owner;
            this.ownerThread = Thread.currentThread();
            this.candidate = candidate;
            this.answerHash = candidate == null ? "" : candidate.answerHash();
            this.answerChars = candidate == null ? 0 : candidate.rawAnswer().length();
            this.answerBytes = candidate == null ? 0 : TraceRedactor.bytes(candidate.rawAnswer());
        }
    }

    /** Opaque exact-call lineage proof minted with the parsed trace event. */
    public static final class ParsedToolCallPermit {
        private final Bag owner;
        private final Thread ownerThread;
        private final String toolCallId;
        private final ToolCallAuthorityKind authorityKind;
        private final RuntimeToolAuthorityReason runtimeReason;
        private final ProviderResponseCapture.Receipt providerReceipt;
        private final long providerAuthorityEpoch;
        private final int sourceOrdinal;
        private ToolCall currentCall;
        private String toolNameHash;
        private String argumentsHash;
        private PermitState state = PermitState.MINTED;
        private boolean approvalObserved;
        private boolean settlementRecorded;

        private ParsedToolCallPermit(
                Bag owner,
                String toolCallId,
                ToolCall call,
                ToolCallAuthorityKind authorityKind,
                RuntimeToolAuthorityReason runtimeReason,
                ProviderResponseCapture.Receipt providerReceipt,
                long providerAuthorityEpoch,
                int sourceOrdinal
        ) {
            this.owner = owner;
            this.ownerThread = Thread.currentThread();
            this.toolCallId = toolCallId == null ? "" : toolCallId;
            this.authorityKind = Objects.requireNonNull(
                    authorityKind, "tool-call authority kind");
            this.runtimeReason = runtimeReason == null
                    ? RuntimeToolAuthorityReason.NONE
                    : runtimeReason;
            this.providerReceipt = providerReceipt;
            this.providerAuthorityEpoch = providerAuthorityEpoch;
            this.sourceOrdinal = Math.max(0, sourceOrdinal);
            this.currentCall = call;
            this.toolNameHash = toolNameHash(call);
            this.argumentsHash = argumentsHash(call);
        }

        /**
         * Immutable evidence identity for the already-minted parsed call.
         *
         * <p>This value does not confer execution authority; the permit remains
         * opaque and is still consumed only by the trace-owned authority path.
         */
        public String toolCallId() {
            return toolCallId;
        }
    }

    /**
     * Opaque parser lineage. It is a deterministic parse fact, never execution
     * authority by itself.
     */
    public static final class ParsedToolCallLineage {
        private final Bag owner;
        private final Thread ownerThread;
        private final ToolCallSource source;
        private final ToolCallParseFormat format;
        private final RuntimeToolAuthorityReason runtimeReason;
        private final ProviderResponseCapture.Receipt providerReceipt;
        private final ToolCall parsedCall;
        private final ChatMessage.NativeToolCall rawNativeCall;
        private final String providerText;
        private final int sourceOrdinal;
        private final long providerAuthorityEpoch;

        private ParsedToolCallLineage(
                Bag owner,
                ToolCallSource source,
                ToolCallParseFormat format,
                RuntimeToolAuthorityReason runtimeReason,
                ProviderResponseCapture.Receipt providerReceipt,
                ToolCall parsedCall,
                ChatMessage.NativeToolCall rawNativeCall,
                String providerText,
                int sourceOrdinal
        ) {
            this.owner = owner;
            this.ownerThread = Thread.currentThread();
            this.source = source == null ? ToolCallSource.UNBOUND : source;
            this.format = format == null ? ToolCallParseFormat.UNBOUND : format;
            this.runtimeReason = runtimeReason == null
                    ? RuntimeToolAuthorityReason.NONE
                    : runtimeReason;
            this.providerReceipt = providerReceipt;
            this.parsedCall = parsedCall;
            this.rawNativeCall = rawNativeCall;
            this.providerText = Objects.toString(providerText, "");
            this.sourceOrdinal = Math.max(0, sourceOrdinal);
            this.providerAuthorityEpoch = owner == null ? -1 : owner.providerAuthorityEpoch;
        }
    }

    public record ParsedToolCallBatch(
            List<ToolCall> calls,
            List<ParsedToolCallLineage> lineages
    ) {
        public ParsedToolCallBatch {
            calls = calls == null ? List.of() : List.copyOf(calls);
            lineages = lineages == null ? List.of() : List.copyOf(lineages);
            if (calls.size() != lineages.size()) {
                throw new IllegalArgumentException(
                        "every parsed call requires one trace-owned lineage");
            }
        }
    }

    public record ToolCallAuthorityDecision(
            ParsedToolCallPermit permit,
            ToolCallAuthorityRejectionReason rejectionReason
    ) {
        public ToolCallAuthorityDecision {
            if ((permit == null) == (rejectionReason == null)) {
                throw new IllegalArgumentException(
                        "authority decision must contain exactly one outcome");
            }
        }

        public boolean authorized() {
            return permit != null;
        }

        private static ToolCallAuthorityDecision authorized(ParsedToolCallPermit permit) {
            return new ToolCallAuthorityDecision(
                    Objects.requireNonNull(permit, "authorized permit"), null);
        }

        private static ToolCallAuthorityDecision rejected(
                ToolCallAuthorityRejectionReason reason
        ) {
            return new ToolCallAuthorityDecision(
                    null, Objects.requireNonNull(reason, "authority rejection reason"));
        }
    }

    private enum PermitState {
        MINTED,
        ACTIVE,
        EXECUTING,
        SETTLED
    }

    public static String newTraceId() {
        return "trc-" + UUID.randomUUID();
    }

    public static void begin(
            String traceId,
            String sessionId,
            int turnNumber,
            String timestamp,
            String workspaceHash,
            String mode,
            String backend,
            String model,
            String userPrompt
    ) {
        RuntimeArtifactIdentity.Snapshot runtime = RuntimeArtifactIdentity.current();
        LocalTurnTrace.Builder builder = LocalTurnTrace.builder(traceId, sessionId, turnNumber, timestamp)
                .workspaceHash(workspaceHash)
                .mode(mode)
                .model(backend, model)
                .promptSummary(userPrompt)
                .event(TurnTraceEvent.simple("TRACE_STARTED", timestamp, Map.of(
                        "turnNumber", turnNumber,
                        "redactionMode", TraceRedactionMode.DEFAULT.name(),
                        "runtimeArtifactKind", runtime.kind(),
                        "runtimeArtifactSha256", runtime.sha256(),
                        "runtimeArtifactSizeBytes", runtime.sizeBytes(),
                        "runtimeClasspathSha256", runtime.classpathSha256(),
                        "runtimeClasspathSizeBytes", runtime.classpathSizeBytes(),
                        "runtimeClasspathEntryCount", runtime.classpathEntryCount(),
                        "runtimeVersion", runtime.version(),
                        "runtimeCommit", runtime.commitSha())));
        Bag providerBag = new Bag(builder, traceId, turnNumber);
        HOLDER.set(providerBag);
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {
            @Override
            public void generationStarted(ProviderResponseCapture.GenerationEvent event) {
                recordGenerationStarted(providerBag, event);
            }

            @Override
            public void attemptStarted(ProviderResponseCapture.AttemptEvent event) {
                recordGenerationAttemptStarted(providerBag, event);
            }

            @Override
            public void requestDispatched(ProviderResponseCapture.RequestDispatch event) {
                recordGenerationRequestDispatched(providerBag, event);
            }

            @Override
            public void transportUnobserved(ProviderResponseCapture.AttemptEvent event) {
                recordGenerationTransportUnobserved(providerBag, event);
            }

            @Override
            public void transportLifecycle(
                    ProviderResponseCapture.TransportLifecycleEvent event) {
                recordProviderTransportLifecycle(providerBag, event);
            }

            @Override
            public ProviderResponseCapture.Receipt responseReturned(
                    ProviderResponseCapture.AttemptResponse response) {
                return recordProviderResponse(providerBag, response);
            }

            @Override
            public void attemptFailed(ProviderResponseCapture.AttemptFailure failure) {
                recordGenerationAttemptFailed(providerBag, failure);
            }

            @Override
            public void attemptAbandoned(ProviderResponseCapture.AttemptAbandonment abandonment) {
                recordGenerationAttemptAbandoned(providerBag, abandonment);
            }

            @Override
            public void generationFailed(ProviderResponseCapture.GenerationFailure failure) {
                recordGenerationFailed(providerBag, failure);
            }

            @Override
            public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment abandonment) {
                recordGenerationAbandoned(providerBag, abandonment);
            }

            @Override
            public void generationCompleted(ProviderResponseCapture.GenerationCompletion completion) {
                recordGenerationCompleted(providerBag, completion);
            }
        }, traceId);
        ContextLedgerCapture.begin(traceId, turnNumber);
    }

    public static boolean isActive() {
        return HOLDER.get() != null;
    }

    public static String currentTraceId() {
        Bag bag = HOLDER.get();
        return bag == null ? "" : bag.traceId;
    }

    public static int currentTurnNumber() {
        Bag bag = HOLDER.get();
        return bag == null ? 0 : bag.turnNumber;
    }

    /** Updates metadata that can only be resolved after the trace has begun. */
    public static void updateRuntimeMetadata(String mode, String backend, String model) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.mode(mode).model(backend, model);
    }

    /** Trace-owned stage marker; it cannot outlive the active trace bag. */
    public static void enterExecutionStage(TurnExecutionStage stage) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.executionStage = stage == null ? TurnExecutionStage.UNKNOWN : stage;
        }
    }

    public static TurnExecutionStage currentExecutionStage() {
        Bag bag = HOLDER.get();
        return bag == null ? TurnExecutionStage.UNKNOWN : bag.executionStage;
    }

    public static void recordPolicyTrace(TurnPolicyTrace trace) {
        Bag bag = HOLDER.get();
        if (bag == null || trace == null || !trace.hasPolicyData()) return;
        PolicyTraceRecorder.record(bag.builder, trace);
    }

    /** Records one redacted projection of the exact runtime-compiled request obligation. */
    public static void recordRequestObligation(RequestObligation obligation) {
        Bag bag = HOLDER.get();
        if (bag == null || obligation == null) return;
        String identity = obligation.canonicalSha256();
        if (!bag.requestObligationSha256.isBlank()) {
            if (!bag.requestObligationSha256.equals(identity)) {
                throw new IllegalStateException(
                        "Turn attempted to record two different request obligations");
            }
            return;
        }
        bag.requestObligationSha256 = identity;
        Map<String, Object> data = new LinkedHashMap<>(obligation.redactedProjection());
        bag.builder.event(TurnTraceEvent.simple(
                "REQUEST_OBLIGATION_COMPILED",
                now(),
                data));
    }

    /** Records the typed request decision without persisting matched prompt text. */
    public static void recordMutationRequestDecision(MutationIntent.RequestShape shape) {
        Bag bag = HOLDER.get();
        if (bag == null || shape == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("decision", shape.decision().name());
        data.put("reason", safe(shape.reason()));
        String evidence = safe(shape.evidenceSpan());
        data.put("evidencePresent", !evidence.isBlank());
        if (!evidence.isBlank()) {
            data.put("evidenceHash", TraceRedactor.hash(evidence));
            data.put("evidenceChars", evidence.length());
            data.put("evidenceBytes", TraceRedactor.bytes(evidence));
        }
        bag.builder.event(TurnTraceEvent.simple("MUTATION_REQUEST_DECIDED", now(), data));
    }

    public static void recordRoutedAnswerCaptured(String assistantText) {
        Bag bag = HOLDER.get();
        if (bag == null || bag.routedAnswerRecorded) return;
        String routedHash = TraceRedactor.hash(assistantText);
        AnswerCandidate candidate = bag.answerCandidate;
        boolean candidateMatches = candidate != null
                && candidate.answerHash().equals(routedHash)
                && Objects.equals(candidate.rawAnswer(), Objects.toString(assistantText, ""));
        ProviderResponseCapture.AnswerOrigin origin = candidateMatches
                ? candidate.origin()
                : ProviderResponseCapture.AnswerOrigin.NONE;
        String sourceGenerationId = candidateMatches
                ? candidate.sourceGenerationId()
                : "";
        String sourceAttemptId = candidateMatches
                ? candidate.sourceAttemptId()
                : "";
        TerminalAnswerTraceRecorder.record(
                bag.builder, assistantText, origin, sourceGenerationId, sourceAttemptId);
        RoutedAnswerTraceRecorder.record(bag.builder, assistantText);
        bag.routedAnswerRecorded = true;
        bag.answerCandidate = null;
        renderOutcomeIfReady(bag);
    }

    /**
     * Advances the exact terminal-answer candidate through a deterministic
     * runtime presentation or policy transformation. The source bytes are
     * derived from the active candidate; callers cannot nominate or forge
     * them. A no-op transformation emits no event.
     */
    public static void recordAnswerTransformedForRouting(
            String transformedAnswer,
            AnswerTransformationReason reason) {
        Bag bag = HOLDER.get();
        if (bag == null || reason == null) return;
        synchronized (bag) {
            if (bag.routedAnswerRecorded || bag.answerCandidate == null) return;
            AnswerCandidate current = bag.answerCandidate;
            String target = Objects.toString(transformedAnswer, "");
            String targetHash = TraceRedactor.hash(target);
            if (current.answerHash().equals(targetHash)
                    && current.rawAnswer().equals(target)) {
                return;
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("fromAnswerHash", current.answerHash());
            data.put("fromAnswerChars", current.rawAnswer().length());
            data.put("answerHash", targetHash);
            data.put("answerChars", target.length());
            data.put("reasonCode", reason.name());
            bag.builder.event(TurnTraceEvent.simple(
                    "ANSWER_TRANSFORMED", now(), data));
            bag.answerCandidate = new AnswerCandidate(
                    targetHash,
                    target,
                    current.origin(),
                    current.sourceGenerationId(),
                    current.sourceAttemptId());
        }
    }

    /**
     * Mints a one-use checkpoint only when the caller supplies the exact bytes
     * of the active, not-yet-routed answer candidate.
     */
    public static AnswerCandidateCheckpoint checkpointExactAnswerCandidate(
            String expectedFallbackBytes) {
        Bag bag = HOLDER.get();
        if (bag == null || expectedFallbackBytes == null) return null;
        synchronized (bag) {
            if (bag.routedAnswerRecorded || bag.answerCandidate == null) return null;
            AnswerCandidate candidate = bag.answerCandidate;
            if (!exactCandidateBytes(candidate, expectedFallbackBytes)) return null;
            AnswerCandidateCheckpoint checkpoint =
                    new AnswerCandidateCheckpoint(bag, candidate);
            bag.answerCandidateCheckpoints.add(checkpoint);
            return checkpoint;
        }
    }

    /**
     * Restores the exact checkpointed fallback before routing. The registered
     * checkpoint is consumed by every same-owner attempt, including a rejected
     * mismatch, so an invalid attempt cannot later be retried into provenance.
     */
    public static boolean restoreExactAnswerCandidate(
            AnswerCandidateCheckpoint checkpoint,
            String exactFallbackBytes,
            AnswerCandidateRestorationReason reason) {
        Bag bag = HOLDER.get();
        if (bag == null
                || checkpoint == null
                || checkpoint.owner != bag
                || checkpoint.ownerThread != Thread.currentThread()) {
            return false;
        }
        synchronized (bag) {
            if (!bag.answerCandidateCheckpoints.remove(checkpoint)) return false;
            if (bag.routedAnswerRecorded
                    || exactFallbackBytes == null
                    || reason == null
                    || !exactCheckpointBytes(checkpoint, exactFallbackBytes)) {
                return false;
            }
            AnswerCandidate restored = checkpoint.candidate;
            bag.answerCandidate = restored;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("answerHash", checkpoint.answerHash);
            data.put("answerChars", checkpoint.answerChars);
            data.put("origin", restored.origin().name());
            data.put("sourceGenerationId", restored.sourceGenerationId());
            data.put("sourceAttemptId", restored.sourceAttemptId());
            data.put("reasonCode", reason.name());
            bag.builder.event(TurnTraceEvent.simple(
                    "ANSWER_CANDIDATE_RESTORED", now(), data));
            return true;
        }
    }

    private static boolean exactCheckpointBytes(
            AnswerCandidateCheckpoint checkpoint,
            String exactFallbackBytes) {
        return checkpoint != null
                && checkpoint.candidate != null
                && checkpoint.answerHash.equals(TraceRedactor.hash(exactFallbackBytes))
                && checkpoint.answerChars == exactFallbackBytes.length()
                && checkpoint.answerBytes == TraceRedactor.bytes(exactFallbackBytes)
                && exactCandidateBytes(checkpoint.candidate, exactFallbackBytes);
    }

    private static boolean exactCandidateBytes(
            AnswerCandidate candidate,
            String exactBytes) {
        return candidate != null
                && exactBytes != null
                && candidate.answerHash().equals(TraceRedactor.hash(exactBytes))
                && candidate.rawAnswer().length() == exactBytes.length()
                && TraceRedactor.bytes(candidate.rawAnswer()) == TraceRedactor.bytes(exactBytes)
                && candidate.rawAnswer().equals(exactBytes);
    }

    private record AnswerCandidate(
            String answerHash,
            String rawAnswer,
            ProviderResponseCapture.AnswerOrigin origin,
            String sourceGenerationId,
            String sourceAttemptId) {}

    private record PendingProviderResponse(
            String generationId,
            String attemptId,
            ProviderResponseCapture.Response response) {}

    private record OutcomeCandidate(String status, String classification) {}

    private static void recordGenerationStarted(
            Bag bag,
            ProviderResponseCapture.GenerationEvent event) {
        if (bag == null || event == null) return;
        synchronized (bag) {
            if (event.source() != ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                bag.latestSelectableGenerationId = event.generationId();
                invalidateSelectedToolAuthority(bag);
            }
            ProviderResponseTraceRecorder.generationStarted(bag.builder, event);
        }
    }

    private static void recordGenerationAttemptStarted(
            Bag bag,
            ProviderResponseCapture.AttemptEvent event) {
        if (bag == null || event == null) return;
        synchronized (bag) {
            if (event.source() != ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                bag.answerCandidate = null;
                invalidateSelectedToolAuthority(bag);
            }
            ProviderResponseTraceRecorder.attemptStarted(bag.builder, event);
        }
    }

    private static void recordGenerationRequestDispatched(
            Bag bag,
            ProviderResponseCapture.RequestDispatch event) {
        if (bag == null || event == null) return;
        synchronized (bag) {
            event.evidence().processReceipt().ifPresent(receipt -> {
                if (bag.emittedProcessReceipts.add(receipt.receiptId())) {
                    ProviderResponseTraceRecorder.processReceiptBound(
                            bag.builder, receipt);
                }
            });
            ProviderResponseTraceRecorder.requestDispatched(bag.builder, event);
        }
    }

    private static void recordGenerationTransportUnobserved(
            Bag bag,
            ProviderResponseCapture.AttemptEvent event) {
        if (bag == null || event == null) return;
        synchronized (bag) {
            ProviderResponseTraceRecorder.transportUnobserved(bag.builder, event);
        }
    }

    private static void recordProviderTransportLifecycle(
            Bag bag,
            ProviderResponseCapture.TransportLifecycleEvent event) {
        if (bag == null || event == null) return;
        synchronized (bag) {
            ProviderResponseTraceRecorder.transportLifecycle(bag.builder, event);
        }
    }

    private static ProviderResponseCapture.Receipt recordProviderResponse(
            Bag bag,
            ProviderResponseCapture.AttemptResponse returned) {
        if (returned == null || returned.response() == null || returned.attempt() == null) {
            return ProviderResponseCapture.Receipt.none();
        }
        ProviderResponseCapture.Response response = returned.response();
        ProviderResponseCapture.AttemptEvent attempt = returned.attempt();
        if (bag == null) return ProviderResponseCapture.Receipt.none();
        synchronized (bag) {
            ProviderResponseTraceRecorder.responseReturned(bag.builder, returned);
            bag.providerResponses.put(
                    providerResponseKey(attempt.generationId(), attempt.attemptId()),
                    new PendingProviderResponse(
                            attempt.generationId(), attempt.attemptId(), response));
        }
        // Listener return values are a deprecated compatibility surface. Return
        // the owner-minted object, but Attempt.respond never trusts this channel.
        return returned.receipt();
    }

    private static void recordGenerationAttemptFailed(
            Bag bag,
            ProviderResponseCapture.AttemptFailure failure) {
        if (bag == null || failure == null) return;
        synchronized (bag) {
            if (failure.attempt().source() != ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                bag.answerCandidate = null;
                invalidateSelectedToolAuthority(bag);
            }
            ProviderResponseTraceRecorder.attemptFailed(bag.builder, failure);
        }
    }

    private static void recordGenerationAttemptAbandoned(
            Bag bag,
            ProviderResponseCapture.AttemptAbandonment abandonment) {
        if (bag == null || abandonment == null) return;
        synchronized (bag) {
            if (abandonment.attempt().source() != ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                bag.answerCandidate = null;
                invalidateSelectedToolAuthority(bag);
            }
            ProviderResponseTraceRecorder.attemptAbandoned(bag.builder, abandonment);
        }
    }

    private static void recordGenerationFailed(
            Bag bag,
            ProviderResponseCapture.GenerationFailure failure) {
        if (bag == null || failure == null) return;
        synchronized (bag) {
            if (failure.generation().source() != ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                bag.answerCandidate = null;
                invalidateSelectedToolAuthority(bag);
            }
            removePendingGeneration(bag, failure.generation().generationId());
            ProviderResponseTraceRecorder.generationFailed(bag.builder, failure);
        }
    }

    private static void recordGenerationAbandoned(
            Bag bag,
            ProviderResponseCapture.GenerationAbandonment abandonment) {
        if (bag == null || abandonment == null) return;
        synchronized (bag) {
            if (abandonment.generation().source() != ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                bag.answerCandidate = null;
                invalidateSelectedToolAuthority(bag);
            }
            removePendingGeneration(bag, abandonment.generation().generationId());
            ProviderResponseTraceRecorder.generationAbandoned(bag.builder, abandonment);
        }
    }

    private static void recordGenerationCompleted(
            Bag bag,
            ProviderResponseCapture.GenerationCompletion completion) {
        if (bag == null || completion == null) return;
        synchronized (bag) {
            ProviderResponseTraceRecorder.generationCompleted(bag.builder, completion);
            ProviderResponseCapture.Receipt receipt = completion.selectedResponse();
            PendingProviderResponse pending = bag.providerResponses.remove(providerResponseKey(
                    receipt.generationId(), receipt.attemptId()));
            removePendingGeneration(bag, receipt.generationId());
            if (pending == null
                    || !receipt.selected()
                    || !Objects.equals(
                            receipt.generationId(), bag.latestSelectableGenerationId)
                    || !receipt.assistantHash().equals(
                            TraceRedactor.hash(pending.response().assistantText()))
                    || pending.response().source() != receipt.source()
                    || receipt.source() == ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                return;
            }
            invalidateSelectedToolAuthority(bag);
            bag.selectedResponseReceipt = receipt;
            bag.selectedProviderResponse = pending.response();
            ProviderResponseCapture.AnswerOrigin origin = answerOrigin(
                    pending.response(), receipt);
            bag.answerCandidate = !pending.response().toolCallPresent()
                    ? new AnswerCandidate(
                            receipt.assistantHash(),
                            pending.response().assistantText(),
                            origin,
                            origin == ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER
                                    ? receipt.generationId()
                                    : "",
                            origin == ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER
                                    ? receipt.attemptId()
                                    : "")
                    : null;
        }
    }

    private static String providerResponseKey(String generationId, String attemptId) {
        return safe(generationId) + '\u0000' + safe(attemptId);
    }

    private static void removePendingGeneration(Bag bag, String generationId) {
        String prefix = safe(generationId) + '\u0000';
        bag.providerResponses.keySet().removeIf(key -> key.startsWith(prefix));
    }

    private static ProviderResponseCapture.AnswerOrigin answerOrigin(
            ProviderResponseCapture.Response response,
            ProviderResponseCapture.Receipt receipt) {
        return switch (response.source()) {
            case FOREGROUND_PROVIDER -> receipt.providerTerminalEligible()
                    ? ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER
                    : ProviderResponseCapture.AnswerOrigin.NONE;
            case PLACEHOLDER_RUNTIME -> receipt.selected()
                    ? ProviderResponseCapture.AnswerOrigin.RUNTIME_DETERMINISTIC
                    : ProviderResponseCapture.AnswerOrigin.NONE;
            case SCRIPTED_HARNESS -> ProviderResponseCapture.AnswerOrigin.NONE;
            case BACKGROUND_PROVIDER -> ProviderResponseCapture.AnswerOrigin.NONE;
        };
    }

    public static void recordDeterministicAnswerSelected(String answer) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        DeterministicAnswerTraceRecorder.record(
                bag.builder,
                answer,
                ProviderResponseCapture.AnswerOrigin.RUNTIME_DETERMINISTIC);
        bag.answerCandidate = new AnswerCandidate(
                TraceRedactor.hash(answer),
                Objects.toString(answer, ""),
                ProviderResponseCapture.AnswerOrigin.RUNTIME_DETERMINISTIC,
                "",
                "");
    }

    public static void recordToolAnswerSelected(String answer) {
        recordTypedDeterministicAnswerSelected(
                answer, ProviderResponseCapture.AnswerOrigin.TOOL_DETERMINISTIC);
    }

    public static void recordOperatorAnswerSelected(String answer) {
        recordTypedDeterministicAnswerSelected(
                answer, ProviderResponseCapture.AnswerOrigin.OPERATOR);
    }

    private static void recordTypedDeterministicAnswerSelected(
            String answer,
            ProviderResponseCapture.AnswerOrigin origin) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        DeterministicAnswerTraceRecorder.record(bag.builder, answer, origin);
        bag.answerCandidate = new AnswerCandidate(
                TraceRedactor.hash(answer), Objects.toString(answer, ""), origin, "", "");
    }

    /** Explicitly selects provider-returned prose as the terminal answer candidate. */
    public static void recordProviderAnswerSelected(
            String answer,
            ProviderResponseCapture.Receipt receipt) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        ProviderResponseCapture.Receipt safeReceipt = receipt == null
                ? ProviderResponseCapture.Receipt.none()
                : receipt;
        boolean exactProviderBytes = bag.selectedResponseReceipt == safeReceipt
                && safeReceipt.selected()
                && !safeReceipt.assistantHash().isBlank()
                && safeReceipt.assistantHash().equals(TraceRedactor.hash(answer));
        ProviderResponseCapture.AnswerOrigin origin = switch (safeReceipt.source()) {
            case FOREGROUND_PROVIDER -> safeReceipt.providerTerminalEligible() && exactProviderBytes
                    ? ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER
                    : ProviderResponseCapture.AnswerOrigin.NONE;
            case PLACEHOLDER_RUNTIME -> exactProviderBytes
                    ? ProviderResponseCapture.AnswerOrigin.RUNTIME_DETERMINISTIC
                    : ProviderResponseCapture.AnswerOrigin.NONE;
            case SCRIPTED_HARNESS -> ProviderResponseCapture.AnswerOrigin.NONE;
            case BACKGROUND_PROVIDER -> ProviderResponseCapture.AnswerOrigin.NONE;
        };
        bag.answerCandidate = new AnswerCandidate(
                TraceRedactor.hash(answer),
                Objects.toString(answer, ""),
                origin,
                origin == ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER
                        ? safeReceipt.generationId()
                        : "",
                origin == ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER
                        ? safeReceipt.attemptId()
                        : "");
    }

    /**
     * Seeds a missing answer candidate only from the exact selected provider
     * response that is about to undergo a deterministic runtime transform.
     * Existing candidates are never replaced, so a multi-step transform keeps
     * one contiguous source lineage.
     */
    public static void ensureExactProviderAnswerCandidate(
            String answer,
            ProviderResponseCapture.Receipt receipt) {
        Bag bag = HOLDER.get();
        if (bag == null || receipt == null) return;
        synchronized (bag) {
            if (bag.answerCandidate != null) return;
            boolean exactSelectedResponse = bag.selectedResponseReceipt == receipt
                    && bag.selectedProviderResponse != null
                    && bag.selectedProviderResponse.assistantText().equals(
                            Objects.toString(answer, ""));
            if (exactSelectedResponse) {
                recordProviderAnswerSelected(answer, receipt);
            }
        }
    }

    /**
     * Selects the owner-captured provider bytes, then advances that candidate
     * through one named deterministic transformation to the routed answer.
     * The source bytes are recovered from the active trace bag and the exact
     * selected receipt object; callers cannot substitute a different source.
     */
    public static void recordProviderAnswerSelectedAfterTransformation(
            String transformedAnswer,
            ProviderResponseCapture.Receipt receipt,
            AnswerTransformationReason reason) {
        Bag bag = HOLDER.get();
        if (bag == null || reason == null) return;
        synchronized (bag) {
            String capturedSource = bag.selectedResponseReceipt == receipt
                            && bag.selectedProviderResponse != null
                    ? bag.selectedProviderResponse.assistantText()
                    : "";
            recordProviderAnswerSelected(capturedSource, receipt);
            recordAnswerTransformedForRouting(transformedAnswer, reason);
        }
    }

    /**
     * Records that the task contract suppressed every tool proposal from the
     * exact selected provider response, then preserves the provider prose
     * through the separately recorded answer transformation chain.
     */
    public static void recordProviderAnswerSelectedAfterToolCallSuppression(
            String transformedAnswer,
            ProviderResponseCapture.Receipt receipt,
            ProviderToolSuppressionReason suppressionReason) {
        Bag bag = HOLDER.get();
        if (bag == null || suppressionReason == null) return;
        synchronized (bag) {
            ProviderResponseCapture.Response response =
                    bag.selectedResponseReceipt == receipt
                            ? bag.selectedProviderResponse
                            : null;
            boolean exactSelectedToolResponse = response != null
                    && receipt != null
                    && receipt.selected()
                    && receipt.providerTerminalEligible()
                    && response.source() == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    && response.toolCallPresent()
                    && receipt.assistantHash().equals(TraceRedactor.hash(
                            response.assistantText()));
            if (exactSelectedToolResponse) {
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("sourceGenerationId", receipt.generationId());
                data.put("sourceAttemptId", receipt.attemptId());
                data.put("reasonCode", suppressionReason.name());
                data.put("nativeToolCallCount", response.nativeToolCallCount());
                data.put("textualToolCallPresent", response.textualToolCallPresent());
                bag.builder.event(TurnTraceEvent.simple(
                        "PROVIDER_TOOL_CALLS_SUPPRESSED", now(), data));
                bag.answerCandidate = new AnswerCandidate(
                        receipt.assistantHash(),
                        response.assistantText(),
                        ProviderResponseCapture.AnswerOrigin.MODEL_PROVIDER,
                        receipt.generationId(),
                        receipt.attemptId());
                recordAnswerTransformedForRouting(
                        transformedAnswer,
                        AnswerTransformationReason.TASK_CONTRACT_TOOL_CALL_SUPPRESSION);
                return;
            }
            recordProviderAnswerSelectedAfterTransformation(
                    transformedAnswer,
                    receipt,
                    AnswerTransformationReason.TASK_CONTRACT_TOOL_CALL_SUPPRESSION);
        }
    }

    /** Records bounded abort metadata without persisting provider or model text. */
    public static void recordModelGenerationAborted(String abortReason, boolean partialOutput) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.answerCandidate = null;
        bag.builder.event(TurnTraceEvent.simple("MODEL_GENERATION_ABORTED", now(), Map.of(
                "reasonCode", canonicalAbortReason(abortReason),
                "partialOutput", partialOutput)));
    }

    public static void recordToolCallParsed(String phase, ToolCall call) {
        recordToolCallParsed(phase, call, null);
    }

    public static void recordToolCallParsed(
            String phase,
            ToolCall call,
            ProviderResponseCapture.Receipt receipt) {
        int unambiguousOrdinal = receipt != null && receipt.capturedToolProposalCount() == 1
                ? 1
                : 0;
        recordToolCallParsed(phase, call, receipt, unambiguousOrdinal);
    }

    public static void recordToolCallParsed(
            String phase,
            ToolCall call,
            ProviderResponseCapture.Receipt receipt,
            int proposalOrdinal) {
        recordToolCallParsed(phase, call, receipt, proposalOrdinal, "");
    }

    private static void recordToolCallParsed(
            String phase,
            ToolCall call,
            ProviderResponseCapture.Receipt receipt,
            int proposalOrdinal,
            String toolCallId) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            boolean currentSelectedReceipt = bag.selectedResponseReceipt == receipt;
            String generationId = currentSelectedReceipt && receipt != null
                    && receipt.selected()
                    && receipt.providerToolCallEligible()
                    ? receipt.generationId()
                    : "";
            String attemptId = currentSelectedReceipt && receipt != null
                    && receipt.selected()
                    && receipt.providerToolCallEligible()
                    ? receipt.attemptId()
                    : "";
            ProviderResponseCapture.ToolProposalReceipt proposal = !currentSelectedReceipt || receipt == null
                    ? null
                    : receipt.selectedToolProposal(proposalOrdinal).orElse(null);
            if (proposal != null && !exactNativeProposalMatch(
                    bag.selectedProviderResponse, call, proposalOrdinal, proposal)) {
                proposal = null;
            }
            if (proposalOrdinal > 0 && proposal == null) {
                // A native call without its bounded raw proposal evidence is not
                // allowed to inherit a provider link.
                generationId = "";
                attemptId = "";
            }
            bag.builder.event(TurnTraceEvent.toolCallParsed(
                    now(), phase, call, generationId, attemptId, proposal, toolCallId, Map.of()));
        }
    }

    private static void invalidateSelectedToolAuthority(Bag bag) {
        if (bag == null) return;
        bag.providerAuthorityEpoch++;
        bag.selectedResponseReceipt = null;
        bag.selectedProviderResponse = null;
        ParsedToolCallPermit active = bag.activeToolCall;
        if (active != null) {
            synchronized (active) {
                settlePermitAsAuthorityBlocked(
                        active, "TOOL_AUTHORITY_REVOKED_BY_NEW_GENERATION");
            }
        }
    }

    /**
     * Captures one deterministic parser result. The returned object is opaque
     * parser lineage only; {@link #authorizeParsedToolCallForExecution} owns
     * the separate executable-authority decision.
     */
    private static ParsedToolCallLineage captureParsedToolCallLineage(
            ToolCallSource source,
            ToolCallParseFormat format,
            RuntimeToolAuthorityReason runtimeReason,
            ProviderResponseCapture.Receipt receipt,
            ToolCall parsedCall,
            ChatMessage.NativeToolCall rawNativeCall,
            String providerText,
            int sourceOrdinal
    ) {
        return new ParsedToolCallLineage(
                HOLDER.get(),
                source,
                format,
                runtimeReason,
                receipt,
                parsedCall,
                rawNativeCall,
                providerText,
                sourceOrdinal);
    }

    public static ParsedToolCallLineage captureProviderNativeToolCallLineage(
            ProviderResponseCapture.Receipt receipt,
            ToolCall parsedCall,
            ChatMessage.NativeToolCall rawNativeCall,
            int sourceOrdinal
    ) {
        return captureParsedToolCallLineage(
                ToolCallSource.PROVIDER_RESPONSE,
                ToolCallParseFormat.NATIVE,
                RuntimeToolAuthorityReason.NONE,
                receipt,
                parsedCall,
                rawNativeCall,
                "",
                sourceOrdinal);
    }

    /**
     * Parses and classifies the exact selected provider text inside the trace
     * owner. Callers cannot nominate a different call for the same text.
     */
    public static ParsedToolCallBatch captureProviderTextToolCallBatch(
            ProviderResponseCapture.Receipt receipt,
            String providerText
    ) {
        String text = Objects.toString(providerText, "");
        List<ToolCall> calls = ToolCallParser.parse(text);
        ToolCallParseFormat format =
                ToolProtocolText.containsRecognizedXmlToolCall(text)
                        ? ToolCallParseFormat.XML_COMPAT
                        : ToolCallParseFormat.JSON_TEXT;
        List<ParsedToolCallLineage> lineages = new java.util.ArrayList<>(calls.size());
        for (int index = 0; index < calls.size(); index++) {
            lineages.add(captureParsedToolCallLineage(
                    ToolCallSource.PROVIDER_RESPONSE,
                    format,
                    RuntimeToolAuthorityReason.NONE,
                    receipt,
                    calls.get(index),
                    null,
                    text,
                    index + 1));
        }
        return new ParsedToolCallBatch(calls, lineages);
    }

    public static ParsedToolCallLineage captureRuntimeToolCallLineage(
            ToolCallParseFormat format,
            RuntimeToolAuthorityReason reason,
            ToolCall parsedCall,
            String sourceText,
            int sourceOrdinal
    ) {
        return captureParsedToolCallLineage(
                ToolCallSource.RUNTIME_DETERMINISTIC,
                format,
                reason,
                ProviderResponseCapture.Receipt.none(),
                parsedCall,
                null,
                sourceText,
                sourceOrdinal);
    }

    public static ParsedToolCallLineage captureUnboundToolCallLineage(
            ToolCall parsedCall,
            int sourceOrdinal
    ) {
        return captureParsedToolCallLineage(
                ToolCallSource.UNBOUND,
                ToolCallParseFormat.UNBOUND,
                RuntimeToolAuthorityReason.NONE,
                ProviderResponseCapture.Receipt.none(),
                parsedCall,
                null,
                "",
                sourceOrdinal);
    }

    /**
     * Atomically validates, reserves, and records the exact source authority
     * required before a parsed call may enter policy, approval, checkpoint, or
     * tool execution.
     */
    public static ToolCallAuthorityDecision authorizeParsedToolCallForExecution(
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) {
            ToolCallAuthorityDecision detachedRuntime =
                    authorizeDetachedRuntimeToolCall(call, lineage);
            if (detachedRuntime != null) {
                return detachedRuntime;
            }
            return ToolCallAuthorityDecision.rejected(
                    ToolCallAuthorityRejectionReason.TRACE_INACTIVE);
        }
        synchronized (bag) {
            ToolCallAuthorityRejectionReason common =
                    commonLineageRejection(bag, call, lineage);
            if (common != null) {
                return rejectToolCallAuthority(bag, phase, call, lineage, common);
            }
            if (bag.activeToolCall != null) {
                return rejectToolCallAuthority(
                        bag,
                        phase,
                        call,
                        lineage,
                        ToolCallAuthorityRejectionReason.ACTIVE_CALL_EXISTS);
            }
            return switch (lineage.source) {
                case PROVIDER_RESPONSE ->
                        authorizeProviderToolCall(bag, phase, call, lineage);
                case RUNTIME_DETERMINISTIC ->
                        authorizeRuntimeToolCall(bag, phase, call, lineage);
                case UNBOUND -> rejectToolCallAuthority(
                        bag,
                        phase,
                        call,
                        lineage,
                        ToolCallAuthorityRejectionReason.LINEAGE_MISSING);
            };
        }
    }

    /**
     * Preserves the explicit deterministic-runtime API outside a captured turn.
     * Provider-derived and unbound lineages remain trace-required and fail
     * closed. The detached permit still binds the current thread, exact call,
     * one-use lifecycle, and a nonempty typed runtime reason.
     */
    private static ToolCallAuthorityDecision authorizeDetachedRuntimeToolCall(
            ToolCall call,
            ParsedToolCallLineage lineage
    ) {
        if (lineage == null
                || lineage.owner != null
                || lineage.ownerThread != Thread.currentThread()
                || lineage.source != ToolCallSource.RUNTIME_DETERMINISTIC
                || lineage.runtimeReason == RuntimeToolAuthorityReason.NONE
                || call == null
                || !Objects.equals(lineage.parsedCall, call)
                || !toolNameHash(lineage.parsedCall).equals(toolNameHash(call))
                || !argumentsHash(lineage.parsedCall).equals(argumentsHash(call))
                || !runtimeReasonAllowsTool(lineage.runtimeReason, call)) {
            return null;
        }
        ParsedToolCallPermit permit = new ParsedToolCallPermit(
                null,
                "",
                call,
                ToolCallAuthorityKind.RUNTIME_DETERMINISTIC,
                lineage.runtimeReason,
                null,
                -1,
                lineage.sourceOrdinal);
        permit.state = PermitState.ACTIVE;
        return ToolCallAuthorityDecision.authorized(permit);
    }

    /** Explicit authority path for deterministic runtime-owned actions. */
    public static ToolCallAuthorityDecision beginRuntimeToolCallForExecution(
            String phase,
            ToolCall call,
            RuntimeToolAuthorityReason reason
    ) {
        ParsedToolCallLineage lineage = captureRuntimeToolCallLineage(
                ToolCallParseFormat.UNBOUND,
                reason,
                call,
                "",
                0);
        return authorizeParsedToolCallForExecution(phase, call, lineage);
    }

    private static ToolCallAuthorityRejectionReason commonLineageRejection(
            Bag bag,
            ToolCall call,
            ParsedToolCallLineage lineage
    ) {
        if (lineage == null) {
            return ToolCallAuthorityRejectionReason.LINEAGE_MISSING;
        }
        if (lineage.owner != bag || lineage.ownerThread != Thread.currentThread()) {
            return ToolCallAuthorityRejectionReason.LINEAGE_SCOPE_MISMATCH;
        }
        if (call == null
                || lineage.parsedCall == null
                || !Objects.equals(lineage.parsedCall, call)
                || !toolNameHash(lineage.parsedCall).equals(toolNameHash(call))
                || !argumentsHash(lineage.parsedCall).equals(argumentsHash(call))) {
            return ToolCallAuthorityRejectionReason.LINEAGE_CALL_MISMATCH;
        }
        return null;
    }

    private static ToolCallAuthorityDecision authorizeRuntimeToolCall(
            Bag bag,
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage
    ) {
        if (lineage.runtimeReason == RuntimeToolAuthorityReason.NONE) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.RUNTIME_AUTHORITY_REASON_MISSING);
        }
        if (!runtimeReasonAllowsTool(lineage.runtimeReason, call)) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.RUNTIME_AUTHORITY_SCOPE_INVALID);
        }
        return mintAuthorizedToolCall(
                bag,
                phase,
                call,
                lineage,
                ToolCallAuthorityKind.RUNTIME_DETERMINISTIC,
                null,
                null);
    }

    private static ToolCallAuthorityDecision authorizeProviderToolCall(
            Bag bag,
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage
    ) {
        ProviderResponseCapture.Receipt receipt = lineage.providerReceipt;
        if (receipt == null) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_RECEIPT_MISSING);
        }
        if (bag.selectedResponseReceipt != receipt) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_RECEIPT_NOT_CURRENT);
        }
        if (lineage.providerAuthorityEpoch != bag.providerAuthorityEpoch
                || !Objects.equals(
                        receipt.generationId(), bag.latestSelectableGenerationId)) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_GENERATION_STALE);
        }
        if (!receipt.selected()
                || receipt.source() != ProviderResponseCapture.Source.FOREGROUND_PROVIDER) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_SOURCE_INELIGIBLE);
        }
        if (receipt.purpose() != ProviderResponseCapture.Purpose.TURN_RESPONSE) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PURPOSE_INELIGIBLE);
        }
        ProviderResponseCapture.ToolAuthorityMode requiredMode = switch (lineage.format) {
            case NATIVE -> ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE;
            case JSON_TEXT -> ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON;
            case XML_COMPAT, UNBOUND -> ProviderResponseCapture.ToolAuthorityMode.NONE;
        };
        if (requiredMode != ProviderResponseCapture.ToolAuthorityMode.NONE
                && receipt.toolAuthorityMode() != requiredMode) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_TOOL_MODE_MISMATCH);
        }
        if (receipt.toolAuthorityBinding()
                != ProviderResponseCapture.ToolAuthorityBinding.BOUND) {
            ToolCallAuthorityRejectionReason bindingReason = switch (
                    receipt.toolAuthorityBinding()) {
                case SURFACE_UNBOUND, DISPATCH_UNBOUND ->
                        ToolCallAuthorityRejectionReason.PROVIDER_TOOL_SURFACE_UNBOUND;
                case DISPATCH_SURFACE_MISMATCH ->
                        ToolCallAuthorityRejectionReason.PROVIDER_DISPATCH_SURFACE_MISMATCH;
                case NOT_APPLICABLE, BOUND ->
                        ToolCallAuthorityRejectionReason.PROVIDER_TOOL_SURFACE_UNBOUND;
            };
            return rejectToolCallAuthority(
                    bag, phase, call, lineage, bindingReason);
        }
        if (!receipt.providerToolCallEligible()) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_TOOL_INELIGIBLE);
        }
        ProviderResponseCapture.Response response = bag.selectedProviderResponse;
        if (response == null) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_RESPONSE_MISSING);
        }
        if (response.source() != receipt.source()
                || !receipt.assistantHash().equals(
                        TraceRedactor.hash(response.assistantText()))) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_RESPONSE_MISMATCH);
        }
        return switch (lineage.format) {
            case NATIVE -> authorizeNativeProviderToolCall(
                    bag, phase, call, lineage, receipt, response);
            case JSON_TEXT -> authorizeJsonProviderToolCall(
                    bag, phase, call, lineage, receipt, response);
            case XML_COMPAT -> rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.XML_COMPATIBILITY_NOT_AUTHORIZED);
            case UNBOUND -> rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.TEXT_PARSER_LINEAGE_REQUIRED);
        };
    }

    private static ToolCallAuthorityDecision authorizeNativeProviderToolCall(
            Bag bag,
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage,
            ProviderResponseCapture.Receipt receipt,
            ProviderResponseCapture.Response response
    ) {
        if (!receipt.providerTerminalEligible()
                || receipt.completion() != ProviderResponseCapture.Completion.PROVIDER_EOS
                || response.normalized()
                || response.truncated()) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_TERMINAL_INELIGIBLE);
        }
        int ordinal = lineage.sourceOrdinal;
        if (ordinal < 1
                || ordinal > ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS
                || ordinal > response.nativeToolCalls().size()) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_ORDINAL_INVALID);
        }
        ProviderResponseCapture.ToolProposalReceipt proposal =
                receipt.selectedToolProposal(ordinal).orElse(null);
        if (proposal == null) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_EVIDENCE_MISSING);
        }
        ChatMessage.NativeToolCall raw = response.nativeToolCalls().get(ordinal - 1);
        if (lineage.rawNativeCall == null
                || !Objects.equals(raw, lineage.rawNativeCall)
                || !exactNativeProposalMatch(response, call, ordinal, proposal)) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_MISMATCH);
        }
        if (!receipt.authorizesExactToolName(raw.name())) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_TOOL_SURFACE_MISMATCH);
        }
        if (!reserveProposal(bag.consumedNativeProposals, receipt, ordinal)) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_ALREADY_USED);
        }
        return mintAuthorizedToolCall(
                bag,
                phase,
                call,
                lineage,
                ToolCallAuthorityKind.PROVIDER_NATIVE,
                proposal,
                raw);
    }

    private static ToolCallAuthorityDecision authorizeJsonProviderToolCall(
            Bag bag,
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage,
            ProviderResponseCapture.Receipt receipt,
            ProviderResponseCapture.Response response
    ) {
        if (receipt.completion() != ProviderResponseCapture.Completion.PROVIDER_EOS
                || !receipt.providerTerminalEligible()
                || response.normalized()
                || response.truncated()
                || !response.textualToolCallPresent()
                || response.nativeToolCallCount() != 0) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.TEXT_PROTOCOL_INELIGIBLE);
        }
        if (!Objects.equals(response.assistantText(), lineage.providerText)) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_RESPONSE_MISMATCH);
        }
        int ordinal = lineage.sourceOrdinal;
        if (ordinal < 1
                || ordinal > ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_ORDINAL_INVALID);
        }
        List<ToolCall> parsedCalls = ToolCallParser.parse(response.assistantText());
        if (ordinal > parsedCalls.size()
                || !Objects.equals(parsedCalls.get(ordinal - 1), call)
                || !toolNameHash(parsedCalls.get(ordinal - 1)).equals(toolNameHash(call))
                || !argumentsHash(parsedCalls.get(ordinal - 1)).equals(argumentsHash(call))) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_MISMATCH);
        }
        if (!receipt.authorizesExactToolName(call.toolName())) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_TOOL_SURFACE_MISMATCH);
        }
        if (!reserveProposal(bag.consumedTextProposals, receipt, ordinal)) {
            return rejectToolCallAuthority(
                    bag,
                    phase,
                    call,
                    lineage,
                    ToolCallAuthorityRejectionReason.PROVIDER_PROPOSAL_ALREADY_USED);
        }
        return mintAuthorizedToolCall(
                bag,
                phase,
                call,
                lineage,
                ToolCallAuthorityKind.PROVIDER_TEXT_JSON,
                null,
                null);
    }

    private static boolean reserveProposal(
            Map<ProviderResponseCapture.Receipt, Set<Integer>> consumed,
            ProviderResponseCapture.Receipt receipt,
            int ordinal
    ) {
        Set<Integer> ordinals = consumed.computeIfAbsent(
                receipt, ignored -> new java.util.HashSet<>());
        return ordinals.add(ordinal);
    }

    private static ToolCallAuthorityDecision mintAuthorizedToolCall(
            Bag bag,
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage,
            ToolCallAuthorityKind kind,
            ProviderResponseCapture.ToolProposalReceipt proposal,
            ChatMessage.NativeToolCall rawNativeCall
    ) {
        String toolCallId = "call-" + bag.turnNumber + "-" + (++bag.nextToolCallOrdinal);
        ProviderResponseCapture.Receipt receipt =
                kind == ToolCallAuthorityKind.RUNTIME_DETERMINISTIC
                        ? null
                        : lineage.providerReceipt;
        ParsedToolCallPermit permit = new ParsedToolCallPermit(
                bag,
                toolCallId,
                call,
                kind,
                lineage.runtimeReason,
                receipt,
                bag.providerAuthorityEpoch,
                lineage.sourceOrdinal);
        bag.activeToolCall = permit;
        permit.state = PermitState.ACTIVE;
        try {
            String generationId = receipt == null ? "" : receipt.generationId();
            String attemptId = receipt == null ? "" : receipt.attemptId();
            bag.builder.event(TurnTraceEvent.toolCallParsed(
                    now(),
                    phase,
                    call,
                    generationId,
                    attemptId,
                    proposal,
                    toolCallId,
                    authorityEvidence(
                            bag,
                            permit,
                            lineage,
                            proposal,
                            rawNativeCall)));
            return ToolCallAuthorityDecision.authorized(permit);
        } catch (RuntimeException appendFailure) {
            permit.state = PermitState.SETTLED;
            bag.activeToolCall = null;
            throw appendFailure;
        }
    }

    private static Map<String, Object> authorityEvidence(
            Bag bag,
            ParsedToolCallPermit permit,
            ParsedToolCallLineage lineage,
            ProviderResponseCapture.ToolProposalReceipt proposal,
            ChatMessage.NativeToolCall rawNativeCall
    ) {
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("schemaVersion", 1);
        authority.put("status", "AUTHORIZED");
        authority.put("kind", permit.authorityKind.name());
        authority.put("authorityId", TraceRedactor.hash(
                bag.traceId + '\u0000' + permit.toolCallId + '\u0000'
                        + permit.authorityKind.name()));
        authority.put("toolNameHash", permit.toolNameHash);
        authority.put("argumentsHash", permit.argumentsHash);
        if (permit.authorityKind == ToolCallAuthorityKind.RUNTIME_DETERMINISTIC) {
            authority.put("runtimeReason", permit.runtimeReason.name());
            return Map.copyOf(authority);
        }
        ProviderResponseCapture.Receipt receipt = permit.providerReceipt;
        authority.put("providerGenerationId", receipt.generationId());
        authority.put("providerAttemptId", receipt.attemptId());
        authority.put("sourceOrdinal", permit.sourceOrdinal);
        if (permit.authorityKind == ToolCallAuthorityKind.PROVIDER_NATIVE) {
            authority.put("parserFormat", ToolCallParseFormat.NATIVE.name());
            authority.put("providerCallIdHash", TraceRedactor.hash(
                    rawNativeCall == null ? "" : rawNativeCall.id()));
            authority.put("providerToolNameHash", proposal.toolNameHash());
            authority.put("providerArgumentsHash", proposal.argumentsHash());
        } else {
            authority.put("parserFormat", ToolCallParseFormat.JSON_TEXT.name());
            authority.put("providerAssistantHash", receipt.assistantHash());
        }
        return Map.copyOf(authority);
    }

    private static ToolCallAuthorityDecision rejectToolCallAuthority(
            Bag bag,
            String phase,
            ToolCall call,
            ParsedToolCallLineage lineage,
            ToolCallAuthorityRejectionReason reason
    ) {
        ToolCallAuthorityKind candidateKind = candidateAuthorityKind(lineage);
        bag.builder.event(TurnTraceEvent.toolCallAuthorityRejected(
                now(),
                phase,
                call,
                candidateKind == null ? "UNBOUND" : candidateKind.name(),
                reason.name()));
        return ToolCallAuthorityDecision.rejected(reason);
    }

    private static ToolCallAuthorityKind candidateAuthorityKind(
            ParsedToolCallLineage lineage
    ) {
        if (lineage == null) return null;
        if (lineage.source == ToolCallSource.RUNTIME_DETERMINISTIC) {
            return ToolCallAuthorityKind.RUNTIME_DETERMINISTIC;
        }
        if (lineage.source != ToolCallSource.PROVIDER_RESPONSE) return null;
        return switch (lineage.format) {
            case NATIVE -> ToolCallAuthorityKind.PROVIDER_NATIVE;
            case JSON_TEXT -> ToolCallAuthorityKind.PROVIDER_TEXT_JSON;
            case XML_COMPAT, UNBOUND -> null;
        };
    }

    /** Enters execution only for the current terminal identity in the lineage. */
    public static boolean consumeToolCallParsedPermit(
            ParsedToolCallPermit permit,
            ToolCall call) {
        if (permit == null) return false;
        synchronized (permit) {
            if (!permitScopeMatches(permit)
                    || permit.state != PermitState.ACTIVE
                    || !permitMatches(permit, call)
                    || !permitAuthorityStillCurrent(permit)) {
                if (permit.ownerThread == Thread.currentThread()) {
                    settlePermitAsAuthorityBlocked(permit, "TOOL_AUTHORITY_REVOKED");
                }
                return false;
            }
            permit.state = PermitState.EXECUTING;
            return true;
        }
    }

    /**
     * Advances one exact parsed-call lineage through a runtime-owned rewrite.
     * Rewrites after any approval observation fail closed.
     */
    public static boolean recordToolCallTransformedForExecution(
            ParsedToolCallPermit permit,
            String phase,
            ToolCall to,
            ToolCallTransformationReason reason) {
        if (permit == null || to == null || reason == null) return false;
        synchronized (permit) {
            boolean providerNameChanged = permit.authorityKind
                            != ToolCallAuthorityKind.RUNTIME_DETERMINISTIC
                    && !Objects.equals(
                            permit.currentCall.toolName(), to.toolName());
            boolean providerTargetOutsideSelectedSurface = providerNameChanged
                    && (permit.providerReceipt == null
                        || !permit.providerReceipt.authorizesExactToolName(
                                to.toolName()));
            if (!permitScopeMatches(permit)
                    || (permit.state != PermitState.ACTIVE
                        && permit.state != PermitState.EXECUTING)
                    || permit.approvalObserved
                    || providerTargetOutsideSelectedSurface
                    || (permit.authorityKind
                            == ToolCallAuthorityKind.RUNTIME_DETERMINISTIC
                        && !runtimeReasonAllowsTool(permit.runtimeReason, to))) {
                if (permit.ownerThread == Thread.currentThread()) {
                    if (providerTargetOutsideSelectedSurface && permit.owner != null) {
                        permit.owner.builder.event(
                                TurnTraceEvent.toolCallAuthorityRejected(
                                        now(),
                                        phase,
                                        to,
                                        permit.authorityKind.name(),
                                        ToolCallAuthorityRejectionReason
                                                .PROVIDER_TOOL_SURFACE_MISMATCH.name()));
                    }
                    settlePermitAsAuthorityBlocked(
                            permit,
                            providerTargetOutsideSelectedSurface
                                    ? ToolCallAuthorityRejectionReason
                                            .PROVIDER_TOOL_SURFACE_MISMATCH.name()
                                    : "TOOL_TRANSFORMATION_AUTHORITY_INVALID");
                }
                return false;
            }
            ToolCall from = permit.currentCall;
            String nextToolHash = toolNameHash(to);
            String nextArgumentsHash = argumentsHash(to);
            if (permit.toolNameHash.equals(nextToolHash)
                    && permit.argumentsHash.equals(nextArgumentsHash)) {
                return true;
            }
            if (permit.owner != null) {
                permit.owner.builder.event(TurnTraceEvent.toolCallTransformed(
                        now(), phase, permit.toolCallId, from, to, reason.name()));
            }
            permit.toolNameHash = nextToolHash;
            permit.argumentsHash = nextArgumentsHash;
            permit.currentCall = to;
            return true;
        }
    }

    private static boolean permitScopeMatches(ParsedToolCallPermit permit) {
        return permit.ownerThread == Thread.currentThread()
                && permit.owner == HOLDER.get();
    }

    private static boolean permitMatches(ParsedToolCallPermit permit, ToolCall call) {
        return call != null
                && Objects.equals(permit.currentCall, call)
                && permit.toolNameHash.equals(toolNameHash(call))
                && permit.argumentsHash.equals(argumentsHash(call));
    }

    private static boolean permitAuthorityStillCurrent(ParsedToolCallPermit permit) {
        if (permit.authorityKind == ToolCallAuthorityKind.RUNTIME_DETERMINISTIC) {
            return runtimeReasonAllowsTool(
                    permit.runtimeReason, permit.currentCall);
        }
        Bag bag = permit.owner;
        ProviderResponseCapture.Receipt receipt = permit.providerReceipt;
        return bag != null
                && receipt != null
                && bag.providerAuthorityEpoch == permit.providerAuthorityEpoch
                && bag.selectedResponseReceipt == receipt
                && Objects.equals(
                        bag.latestSelectableGenerationId, receipt.generationId())
                && receipt.selected()
                && receipt.source() == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                && receipt.purpose() == ProviderResponseCapture.Purpose.TURN_RESPONSE
                && receipt.providerToolCallEligible()
                && receipt.authorizesExactToolName(
                        permit.currentCall.toolName())
                && ((permit.authorityKind == ToolCallAuthorityKind.PROVIDER_NATIVE
                        && receipt.toolAuthorityMode()
                            == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE)
                    || (permit.authorityKind == ToolCallAuthorityKind.PROVIDER_TEXT_JSON
                        && receipt.toolAuthorityMode()
                            == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_TEXT_JSON));
    }

    private static boolean runtimeReasonAllowsTool(
            RuntimeToolAuthorityReason reason,
            ToolCall call
    ) {
        if (reason == null || reason == RuntimeToolAuthorityReason.NONE || call == null) {
            return false;
        }
        return switch (reason) {
            case NONE -> false;
            case DIRECT_RUNTIME_API -> true;
            case READ_EVIDENCE_HANDOFF ->
                    "talos.read_file".equals(call.toolName());
            case EXPECTED_TARGET_SCOPE_REPAIR ->
                    "talos.edit_file".equals(call.toolName());
        };
    }

    private static void revokePermit(ParsedToolCallPermit permit) {
        permit.state = PermitState.SETTLED;
        if (permit.owner != null && permit.owner.activeToolCall == permit) {
            permit.owner.activeToolCall = null;
        }
    }

    private static void settlePermitAsAuthorityBlocked(
            ParsedToolCallPermit permit,
            String reason
    ) {
        if (permit == null) return;
        Bag owner = permit.owner;
        if (!permit.settlementRecorded && owner != null) {
            owner.builder.event(TurnTraceEvent.toolCallBlocked(
                    now(),
                    "tool_authority",
                    permit.currentCall,
                    reason,
                    permit.toolCallId));
            permit.settlementRecorded = true;
        }
        revokePermit(permit);
    }

    private static String toolNameHash(ToolCall call) {
        return ProviderResponseCapture.toolNameHash(call == null ? "" : call.toolName());
    }

    private static String argumentsHash(ToolCall call) {
        return ProviderResponseCapture.canonicalArgumentsHash(
                call == null ? Map.of() : call.parameters());
    }

    /**
     * Binds a native parsed call to the exact selected provider proposal before
     * provider provenance is written to the trace.
     *
     * <p>The comparison deliberately mirrors {@code NativeToolCallConverter}:
     * top-level map/list values are serialized as JSON and every other value is
     * converted with {@link String#valueOf(Object)}. The resulting tool name and
     * complete string-parameter map must equal the original call presented to
     * the trace. Later runtime rewrites retain that immutable provider-decision
     * binding while advancing a separate effective-call lineage, so rewritten
     * bytes are never attributed to the provider.
     */
    private static boolean exactNativeProposalMatch(
            ProviderResponseCapture.Response response,
            ToolCall parsedCall,
            int proposalOrdinal,
            ProviderResponseCapture.ToolProposalReceipt receiptProposal) {
        if (response == null || parsedCall == null || receiptProposal == null
                || proposalOrdinal < 1
                || proposalOrdinal > response.nativeToolCalls().size()) {
            return false;
        }
        ChatMessage.NativeToolCall raw = response.nativeToolCalls().get(proposalOrdinal - 1);
        if (raw == null
                || !receiptProposal.toolNameHash().equals(
                        ProviderResponseCapture.toolNameHash(raw.name()))
                || !receiptProposal.argumentsHash().equals(
                        ProviderResponseCapture.canonicalArgumentsHash(raw.arguments()))) {
            return false;
        }
        try {
            Map<String, String> converted = new LinkedHashMap<>();
            if (raw.arguments() != null) {
                for (Map.Entry<String, Object> entry : raw.arguments().entrySet()) {
                    Object value = entry.getValue();
                    String text;
                    if (value instanceof Map || value instanceof List) {
                        try {
                            text = NATIVE_ARGUMENT_MAPPER.writeValueAsString(value);
                        } catch (Exception serializationFailure) {
                            text = String.valueOf(value);
                        }
                    } else {
                        text = String.valueOf(value);
                    }
                    converted.put(entry.getKey(), text);
                }
            }
            return Objects.equals(raw.name(), parsedCall.toolName())
                    && converted.equals(parsedCall.parameters());
        } catch (RuntimeException invalidProposal) {
            return false;
        }
    }

    public static void recordToolAliasDecision(ToolAliasPolicy.Decision decision) {
        Bag bag = HOLDER.get();
        if (bag == null || decision == null || !decision.traceWorthy()) return;
        bag.builder.event(ToolAliasDecisionTraceEventFactory.decision(decision));
    }

    public static void recordPathArgumentNormalized(
            String phase,
            ToolCall call,
            String key,
            String rawPath,
            String normalizedPath
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PathArgumentNormalizationTraceEventFactory.normalized(
                phase,
                call,
                key,
                rawPath,
                normalizedPath));
    }

    public static void recordContentSanitized(
            String phase,
            ToolCall call,
            String key,
            int strippedChars,
            String beforeValue,
            String afterValue
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ContentSanitizationTraceEventFactory.sanitized(
                phase,
                call,
                key,
                strippedChars,
                beforeValue,
                afterValue));
    }

    public static void recordApprovalDiffPreview(
            String phase,
            ToolCall call,
            String renderedDiff,
            int added,
            int removed,
            int diffLineCount,
            boolean truncated,
            String skippedReason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ApprovalDiffTraceEventFactory.preview(
                phase,
                call,
                renderedDiff,
                added,
                removed,
                diffLineCount,
                truncated,
                skippedReason));
    }

    public static void recordToolCallBlocked(String phase, ToolCall call, String reason) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            recordScoreBearingToolEvent(
                    bag,
                    call,
                    true,
                    false,
                    toolCallId -> TurnTraceEvent.toolCallBlocked(
                            now(), phase, call, reason, toolCallId));
        }
    }

    public static void recordToolExecuted(String phase, ToolCall call, boolean success, String reason) {
        recordToolExecuted(phase, call, success, reason, List.of());
    }

    public static void recordModelIdentity(ModelIdentitySnapshot identity) {
        Bag bag = HOLDER.get();
        if (bag == null || identity == null || !identity.available()) return;
        bag.builder.event(TurnTraceEvent.simple(
                "MODEL_IDENTITY_RESOLVED",
                now(),
                identity.traceData()));
    }

    public static void recordToolExecuted(
            String phase,
            ToolCall call,
            boolean success,
            String reason,
            List<String> sourcePathHints) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            recordScoreBearingToolEvent(
                    bag,
                    call,
                    true,
                    false,
                    toolCallId -> TurnTraceEvent.toolExecuted(
                            now(), phase, call, success, reason, sourcePathHints, toolCallId));
        }
    }

    public static void recordApprovalRequired(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            recordScoreBearingToolEvent(
                    bag,
                    call,
                    false,
                    true,
                    toolCallId -> TurnTraceEvent.approval(
                            "APPROVAL_REQUIRED", now(), phase, call, toolCallId));
        }
    }

    public static void recordApprovalGranted(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            recordScoreBearingToolEvent(
                    bag,
                    call,
                    false,
                    true,
                    toolCallId -> TurnTraceEvent.approval(
                            "APPROVAL_GRANTED", now(), phase, call, toolCallId));
        }
    }

    public static void recordApprovalDenied(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            recordScoreBearingToolEvent(
                    bag,
                    call,
                    false,
                    true,
                    toolCallId -> TurnTraceEvent.approval(
                            "APPROVAL_DENIED", now(), phase, call, toolCallId));
        }
    }

    private static void recordScoreBearingToolEvent(
            Bag bag,
            ToolCall call,
            boolean terminal,
            boolean approval,
            Function<String, TurnTraceEvent> eventFactory) {
        ParsedToolCallPermit permit = bag == null ? null : bag.activeToolCall;
        if (permit == null) {
            throw new IllegalStateException(
                    "score-bearing tool event lacks an active parsed-call lineage");
        }
        synchronized (permit) {
            if (!permitScopeMatches(permit)
                    || (permit.state != PermitState.ACTIVE
                        && permit.state != PermitState.EXECUTING)
                    || !permitMatches(permit, call)) {
                if (permit.ownerThread == Thread.currentThread()) revokePermit(permit);
                throw new IllegalStateException(
                        "score-bearing tool event does not match the active parsed-call lineage");
            }
            bag.builder.event(eventFactory.apply(permit.toolCallId));
            if (approval) permit.approvalObserved = true;
            if (terminal) {
                permit.settlementRecorded = true;
                permit.state = PermitState.SETTLED;
                bag.activeToolCall = null;
            }
        }
    }

    public static void recordPrivateDocumentModelHandoffApprovalRequired(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PrivateDocumentHandoffTraceEventFactory.approvalRequired(phase, call, metadata));
    }

    public static void recordPrivateDocumentModelHandoffApprovalGranted(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata,
            boolean rememberIgnored
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PrivateDocumentHandoffTraceEventFactory.approvalGranted(
                phase,
                call,
                metadata,
                rememberIgnored));
    }

    public static void recordPrivateDocumentModelHandoffApprovalDenied(
            String phase,
            ToolCall call,
            ToolContentMetadata metadata
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PrivateDocumentHandoffTraceEventFactory.approvalDenied(phase, call, metadata));
    }

    public static void recordCommandPlanCreated(String phase, ToolCall call, CommandPlan plan) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.planCreated(phase, call, plan));
    }

    public static void recordCommandPolicyDecision(
            String phase,
            ToolCall call,
            String action,
            String reason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.policyDecision(phase, call, action, reason));
    }

    public static void recordCommandApprovalRequired(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.approvalRequired(phase, call));
    }

    public static void recordCommandApprovalGranted(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.approvalGranted(phase, call));
    }

    public static void recordCommandApprovalDenied(String phase, ToolCall call) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.approvalDenied(phase, call));
    }

    public static void recordCommandDenied(String phase, ToolCall call, String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.denied(phase, call, reason));
    }

    public static void recordCommandStarted(String phase, ToolCall call, CommandPlan plan) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(CommandTraceEventFactory.started(phase, call, plan));
    }

    public static void recordCommandFinished(String phase, ToolCall call, CommandResult result) {
        Bag bag = HOLDER.get();
        if (bag == null || result == null) return;
        for (TurnTraceEvent event : CommandTraceEventFactory.finished(phase, call, result)) {
            bag.builder.event(event);
        }
    }

    public static void recordPermissionDecision(
            String phase,
            ToolCall call,
            String action,
            String reasonCode,
            String relativePath,
            boolean protectedPath,
            String protectedKind,
            boolean rememberEligible
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PermissionTraceEventFactory.decision(
                phase,
                call,
                action,
                reasonCode,
                relativePath,
                protectedPath,
                protectedKind,
                rememberEligible));
    }

    public static void recordCheckpoint(String status, String checkpointId, String reason, int capturedFiles) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        CheckpointTraceRecorder.record(bag.builder, status, checkpointId, reason, capturedFiles);
    }

    /**
     * T793: best-effort restore trace - emits CHECKPOINT_RESTORED or
     * CHECKPOINT_RESTORE_FAILED. A no-op without an active trace bag (the
     * slash-command path today), so callers never depend on it.
     */
    public static void recordCheckpointRestore(
            String checkpointId, boolean success, int restoredFiles, int deletedFiles, String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        CheckpointTraceRecorder.recordRestore(
                bag.builder, checkpointId, success, restoredFiles, deletedFiles, reason);
    }

    public static void recordWorkspaceProfileDeclarationConfigured(
            String profileId,
            String declarationSha256,
            String approval,
            String trustStateAfter,
            String checkpointStatus,
            String checkpointId,
            boolean replacedExisting
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(WorkspaceProfileTraceEventFactory.declarationConfigured(
                profileId,
                declarationSha256,
                approval,
                trustStateAfter,
                checkpointStatus,
                checkpointId,
                replacedExisting));
    }

    public static void recordProtocolSanitized(String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ProtocolSanitizationTraceEventFactory.sanitized(reason));
    }

    public static void recordBackendMalformedResponse(
            String context,
            String bodyHash,
            int bodyChars
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(BackendMalformedResponseTraceEventFactory.captured(context, bodyHash, bodyChars));
    }

    public static void recordExactLiteralWriteCorrected(
            String path,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedLines
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ExactLiteralWriteCorrectionTraceEventFactory.corrected(
                path,
                sourcePattern,
                expectedHash,
                expectedBytes,
                expectedLines,
                observedHash,
                observedBytes,
                observedLines));
    }

    public static void recordActionObligation(String obligation, String status, String reason) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ActionObligationTraceEventFactory.evaluated(obligation, status, reason));
    }

    public static void recordActionObligation(
            String obligation,
            String status,
            String reason,
            String failureKind
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ActionObligationTraceEventFactory.evaluated(
                obligation,
                status,
                reason,
                failureKind));
    }

    public static void recordPendingActionObligation(
            String status,
            String kind,
            List<String> targets,
            String reason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(PendingActionObligationTraceEventFactory.evaluated(
                status,
                kind,
                targets,
                reason));
    }

    public static void recordProtectedReadPostcondition(
            String status,
            List<String> paths,
            String reason
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ProtectedReadPostconditionTraceEventFactory.checked(status, paths, reason));
    }

    public static void recordPromptAudit(PromptAuditSnapshot snapshot) {
        Bag bag = HOLDER.get();
        if (bag == null || snapshot == null || !snapshot.hasPromptAuditData()) return;
        PromptAuditTraceRecorder.record(bag.builder, snapshot);
    }

    public static void recordRepair(String status, String summary) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        RepairTraceRecorder.record(bag.builder, status, summary);
    }

    public static void recordMutationRecoveryAssessment(
            MutationRecoveryAssessment assessment
    ) {
        Bag bag = HOLDER.get();
        if (bag == null || assessment == null) return;
        for (TurnTraceEvent event : MutationRecoveryTraceEventFactory.evaluated(assessment)) {
            bag.builder.event(event);
        }
    }

    /**
     * Records the typed runtime classification that becomes available after
     * the score-bearing execution event has settled.
     */
    public static void recordToolOutcomeClassified(
            dev.talos.runtime.ToolCallLoop.ToolOutcome outcome,
            int outcomeOrdinal
    ) {
        Bag bag = HOLDER.get();
        if (bag == null || outcome == null || outcome.toolCallId().isBlank()) return;
        bag.builder.event(ToolOutcomeClassificationTraceEventFactory.classified(
                outcome,
                outcomeOrdinal));
    }

    public static void recordVerification(String status, String summary, List<String> problems) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        VerificationTraceRecorder.record(bag.builder, status, summary, problems);
        bag.verificationRecorded = true;
    }

    public static void recordVerification(
            String status,
            String summary,
            List<String> problems,
            VerificationReport report
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        VerificationTraceRecorder.record(bag.builder, status, summary, problems, report);
        bag.verificationRecorded = true;
    }

    public static void recordExpectationVerified(
            String kind,
            String status,
            String pathHint,
            String sourcePattern,
            String expectedHash,
            int expectedBytes,
            int expectedChars,
            int expectedLines,
            String observedHash,
            int observedBytes,
            int observedChars,
            int observedLines
    ) {
        Bag bag = HOLDER.get();
        if (bag == null) return;
        bag.builder.event(ExpectationVerificationTraceEventFactory.verified(
                kind,
                status,
                pathHint,
                sourcePattern,
                expectedHash,
                expectedBytes,
                expectedChars,
                expectedLines,
                observedHash,
                observedBytes,
                observedChars,
                observedLines));
    }

    public static void recordOutcome(
            String status,
            String verificationStatus,
            String approvalStatus,
            String mutationStatus,
            String classification
    ) {
        Bag bag = HOLDER.get();
        if (bag == null || bag.outcomeRendered) return;
        recordVerificationFromOutcomeIfAbsent(bag, verificationStatus);
        if (bag.routedAnswerRecorded) {
            OutcomeTraceRecorder.recordSummary(
                    bag.builder,
                    status,
                    verificationStatus,
                    approvalStatus,
                    mutationStatus,
                    classification);
        } else {
            OutcomeTraceRecorder.recordEvaluation(
                    bag.builder,
                    status,
                    verificationStatus,
                    approvalStatus,
                    mutationStatus,
                    classification);
        }
        bag.outcomeRecorded = true;
        bag.outcomeCandidate = new OutcomeCandidate(status, classification);
        renderOutcomeIfReady(bag);
    }

    private static void renderOutcomeIfReady(Bag bag) {
        if (bag == null
                || bag.outcomeRendered
                || !bag.routedAnswerRecorded
                || bag.outcomeCandidate == null) {
            return;
        }
        OutcomeTraceRecorder.recordRendered(
                bag.builder,
                bag.outcomeCandidate.status(),
                bag.outcomeCandidate.classification());
        bag.outcomeRendered = true;
    }

    private static void renderOutcomeBeforeCompletion(Bag bag) {
        if (bag == null || bag.outcomeRendered) return;
        if (bag.routedAnswerRecorded && bag.outcomeCandidate == null) {
            recordOutcome(
                    "UNKNOWN",
                    "NOT_RUN",
                    "UNKNOWN",
                    "UNKNOWN",
                    "OUTCOME_NOT_EVALUATED");
            return;
        }
        if (bag.routedAnswerRecorded && bag.outcomeCandidate != null) {
            OutcomeTraceRecorder.recordRendered(
                    bag.builder,
                    bag.outcomeCandidate.status(),
                    bag.outcomeCandidate.classification());
            bag.outcomeRendered = true;
        }
    }

    private static void recordVerificationFromOutcomeIfAbsent(Bag bag, String verificationStatus) {
        if (bag.verificationRecorded) return;
        String status = safe(verificationStatus);
        if (status.isBlank()) return;
        String summary = "NOT_RUN".equalsIgnoreCase(status)
                ? "Post-apply verification was not applicable."
                : "";
        VerificationTraceRecorder.record(bag.builder, status, summary, List.of());
        bag.verificationRecorded = true;
    }

    public static void recordOutcomeIfAbsent(
            String status,
            String verificationStatus,
            String approvalStatus,
            String mutationStatus,
            String classification
    ) {
        Bag bag = HOLDER.get();
        if (bag == null || bag.outcomeRecorded) return;
        recordOutcome(status, verificationStatus, approvalStatus, mutationStatus, classification);
    }

    public static void warning(String code, String message) {
        Bag bag = HOLDER.get();
        if (bag != null) {
            bag.builder.warning(code, message);
        }
    }

    /**
     * Closes any open provider generation while the trace bag can still receive
     * its typed abandonment callbacks.
     */
    public static void closeProviderLifecycle() {
        ProviderResponseCapture.clear();
    }

    /** Flushes any pending outcome render before the terminal settlement event. */
    public static void prepareForTerminalSettlement() {
        renderOutcomeBeforeCompletion(HOLDER.get());
    }

    /** Records the bounded abnormal settlement immediately before trace completion. */
    public static void recordTurnSettlement(
            TurnSettlement.Kind kind,
            TurnExecutionStage stage,
            TurnFailureClassifier.Classification failure
    ) {
        Bag bag = HOLDER.get();
        if (bag == null || kind == null) return;
        bag.builder.event(TurnSettlementTraceEvent.create(now(), kind, stage, failure));
    }

    public static LocalTurnTrace complete() {
        Bag bag = HOLDER.get();
        if (bag == null) {
            ProviderResponseCapture.clear();
            return null;
        }
        renderOutcomeBeforeCompletion(bag);
        HOLDER.remove();
        ProviderResponseCapture.clear();
        ContextLedgerSnapshot ledger = ContextLedgerCapture.complete();
        bag.builder.contextLedgerSummary(ledger.summary());
        bag.builder.event(TurnTraceEvent.simple("TRACE_COMPLETED", now(), Map.of()));
        return bag.builder.build();
    }

    public static void clear() {
        HOLDER.remove();
        ProviderResponseCapture.clear();
        ContextLedgerCapture.clear();
    }

    private static String now() {
        return Instant.now().toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String canonicalAbortReason(String value) {
        String reason = safe(value).toLowerCase(Locale.ROOT);
        return switch (reason) {
            case "repetition loop" -> "REPETITION_LOOP";
            case "wall-clock budget exceeded" -> "WALL_CLOCK_BUDGET_EXCEEDED";
            case "idle timeout - no tokens" -> "IDLE_TIMEOUT";
            case "interrupted" -> "INTERRUPTED";
            default -> reason.startsWith("stream transport failed")
                    ? "TRANSPORT_FAILURE"
                    : "UNKNOWN";
        };
    }

}
