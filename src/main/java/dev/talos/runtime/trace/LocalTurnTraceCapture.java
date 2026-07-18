package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.runtime.MutationIntent;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.command.CommandPlan;
import dev.talos.runtime.command.CommandResult;
import dev.talos.runtime.verification.VerificationReport;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.context.ContextLedgerSnapshot;
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
        AnswerCandidate answerCandidate;
        OutcomeCandidate outcomeCandidate;
        ProviderResponseCapture.Receipt selectedResponseReceipt;
        ProviderResponseCapture.Response selectedProviderResponse;
        final Map<String, PendingProviderResponse> providerResponses = new LinkedHashMap<>();
        final Set<AnswerCandidateCheckpoint> answerCandidateCheckpoints =
                Collections.newSetFromMap(new IdentityHashMap<>());
        long nextToolCallOrdinal;
        ParsedToolCallPermit activeToolCall;

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
        private ToolCall currentCall;
        private String toolNameHash;
        private String argumentsHash;
        private PermitState state = PermitState.MINTED;
        private boolean approvalObserved;

        private ParsedToolCallPermit(Bag owner, String toolCallId, ToolCall call) {
            this.owner = owner;
            this.ownerThread = Thread.currentThread();
            this.toolCallId = toolCallId == null ? "" : toolCallId;
            this.currentCall = call;
            this.toolNameHash = toolNameHash(call);
            this.argumentsHash = argumentsHash(call);
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

    public static void recordPolicyTrace(TurnPolicyTrace trace) {
        Bag bag = HOLDER.get();
        if (bag == null || trace == null || !trace.hasPolicyData()) return;
        PolicyTraceRecorder.record(bag.builder, trace);
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
                bag.selectedResponseReceipt = null;
                bag.selectedProviderResponse = null;
            }
            ProviderResponseTraceRecorder.attemptStarted(bag.builder, event);
        }
    }

    private static void recordGenerationRequestDispatched(
            Bag bag,
            ProviderResponseCapture.RequestDispatch event) {
        if (bag == null || event == null) return;
        synchronized (bag) {
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
                bag.selectedResponseReceipt = null;
                bag.selectedProviderResponse = null;
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
                bag.selectedResponseReceipt = null;
                bag.selectedProviderResponse = null;
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
                    || !receipt.assistantHash().equals(
                            TraceRedactor.hash(pending.response().assistantText()))
                    || pending.response().source() != receipt.source()
                    || receipt.source() == ProviderResponseCapture.Source.BACKGROUND_PROVIDER) {
                return;
            }
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
                    && receipt.providerToolCallEligible()
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
                    now(), phase, call, generationId, attemptId, proposal, toolCallId));
        }
    }

    /** Atomically installs and records the exact parsed-call lineage required by execution. */
    public static ParsedToolCallPermit beginParsedToolCallForExecution(
            String phase,
            ToolCall call,
            ProviderResponseCapture.Receipt receipt,
            int proposalOrdinal) {
        Bag bag = HOLDER.get();
        if (bag == null) return null;
        String toolCallId = "call-" + bag.turnNumber + "-" + (++bag.nextToolCallOrdinal);
        ParsedToolCallPermit permit = new ParsedToolCallPermit(bag, toolCallId, call);
        synchronized (permit) {
            synchronized (bag) {
                if (bag.activeToolCall != null) {
                    throw new IllegalStateException(
                            "A parsed tool-call lineage is already active");
                }
                bag.activeToolCall = permit;
            }
            permit.state = PermitState.ACTIVE;
            try {
                recordToolCallParsed(phase, call, receipt, proposalOrdinal, toolCallId);
            } catch (RuntimeException appendFailure) {
                permit.state = PermitState.SETTLED;
                bag.activeToolCall = null;
                throw appendFailure;
            }
            return permit;
        }
    }

    /** Enters execution only for the current terminal identity in the lineage. */
    public static boolean consumeToolCallParsedPermit(
            ParsedToolCallPermit permit,
            ToolCall call) {
        if (permit == null) return false;
        synchronized (permit) {
            if (!permitScopeMatches(permit)
                    || permit.state != PermitState.ACTIVE
                    || !permitMatches(permit, call)) {
                if (permit.ownerThread == Thread.currentThread()) revokePermit(permit);
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
            if (!permitScopeMatches(permit)
                    || (permit.state != PermitState.ACTIVE
                        && permit.state != PermitState.EXECUTING)
                    || permit.approvalObserved) {
                if (permit.ownerThread == Thread.currentThread()) revokePermit(permit);
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

    private static void revokePermit(ParsedToolCallPermit permit) {
        permit.state = PermitState.SETTLED;
        if (permit.owner != null && permit.owner.activeToolCall == permit) {
            permit.owner.activeToolCall = null;
        }
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
