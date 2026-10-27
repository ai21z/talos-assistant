package dev.talos.spi.types;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.net.ConnectException;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Explicit, turn-scoped provenance ledger for local-model generations.
 *
 * <p>The calling turn discovers its scope through an ordinary {@link ThreadLocal};
 * asynchronous LLM workers never inherit or rediscover that state. The caller
 * captures the {@link Scope} once and passes its {@link Generation} explicitly
 * through the call budget and every physical provider invocation. A scope is
 * thread safe: watchdog abandonment and a late provider completion race under
 * one lock, and exactly one terminal event wins for each physical attempt.
 */
public final class ProviderResponseCapture {
    /** Hard bound on provider-authored proposal events persisted for one response. */
    public static final int MAX_TRACE_TOOL_PROPOSALS = 64;
    /** Hard bound on provider-authored argument-key hashes persisted for one proposal. */
    public static final int MAX_TRACE_ARGUMENT_NAME_HASHES = 32;
    /** Current independent transport-operation ledger revision. */
    public static final int TRANSPORT_LEDGER_VERSION = 2;
    /** Bounded cooperative-cancellation drain before conservative quarantine. */
    public static final long TRANSPORT_SETTLEMENT_GRACE_MS = 1_000L;

    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<Attempt> LEGACY_ATTEMPT = new ThreadLocal<>();

    private ProviderResponseCapture() {}

    public enum Source {
        FOREGROUND_PROVIDER,
        BACKGROUND_PROVIDER,
        PLACEHOLDER_RUNTIME,
        SCRIPTED_HARNESS
    }

    public enum Purpose {
        TURN_RESPONSE,
        BACKGROUND_MAINTENANCE,
        PLACEHOLDER_RESPONSE,
        HARNESS_FIXTURE
    }

    /** Runtime-owned provider tool protocol enabled for one generation. */
    public enum ToolAuthorityMode {
        NONE,
        PROVIDER_NATIVE,
        PROVIDER_TEXT_JSON
    }

    /**
     * Immutable runtime-selected tool authority for one provider generation.
     *
     * <p>The selected names come from the exact typed request surface, never
     * from provider prose or a broader turn registry. A bound native contract
     * still requires matching transport evidence for the exact serialized
     * native surface. A bound text-JSON contract requires a body-bound dispatch
     * with no native tools. Legacy mode-only openings remain explicitly
     * unbound and can never authorize a provider tool proposal.
     */
    public record ToolAuthorityContract(
            ToolAuthorityMode mode,
            List<String> selectedToolNames,
            ToolChoiceMode toolChoice,
            String namedTool,
            boolean bound
    ) {
        private static final int MAX_SELECTED_TOOLS = 256;

        public ToolAuthorityContract {
            mode = mode == null ? ToolAuthorityMode.NONE : mode;
            selectedToolNames = selectedToolNames == null
                    ? List.of()
                    : List.copyOf(selectedToolNames);
            toolChoice = toolChoice == null ? ToolChoiceMode.NONE : toolChoice;
            namedTool = Objects.toString(namedTool, "");

            if (selectedToolNames.size() > MAX_SELECTED_TOOLS) {
                throw new IllegalArgumentException(
                        "provider tool authority surface exceeds the bounded tool count");
            }
            java.util.LinkedHashSet<String> distinct = new java.util.LinkedHashSet<>();
            for (String name : selectedToolNames) {
                String exact = Objects.requireNonNull(name, "selected tool name");
                if (exact.isBlank() || !exact.equals(exact.strip()) || exact.length() > 256) {
                    throw new IllegalArgumentException(
                            "selected tool names must be exact, nonblank, and bounded");
                }
                if (!distinct.add(exact)) {
                    throw new IllegalArgumentException(
                            "selected provider tool names must be unique");
                }
            }
            if (!namedTool.equals(namedTool.strip()) || namedTool.length() > 256) {
                throw new IllegalArgumentException(
                        "named provider tool identity must be exact and bounded");
            }

            if (mode == ToolAuthorityMode.NONE || !bound) {
                if (bound || !selectedToolNames.isEmpty()
                        || toolChoice != ToolChoiceMode.NONE || !namedTool.isEmpty()) {
                    throw new IllegalArgumentException(
                            "unbound provider tool authority cannot carry a tool surface");
                }
            } else {
                if (selectedToolNames.isEmpty()) {
                    throw new IllegalArgumentException(
                            "bound provider tool authority requires a selected tool surface");
                }
                if (toolChoice == ToolChoiceMode.NONE) {
                    throw new IllegalArgumentException(
                            "tool choice NONE cannot grant provider tool authority");
                }
                if (toolChoice == ToolChoiceMode.NAMED) {
                    if (namedTool.isBlank() || !selectedToolNames.contains(namedTool)) {
                        throw new IllegalArgumentException(
                                "named provider tool must belong to the selected tool surface");
                    }
                } else if (!namedTool.isEmpty()) {
                    throw new IllegalArgumentException(
                            "named provider tool is valid only with NAMED tool choice");
                }
            }
        }

        public static ToolAuthorityContract none() {
            return new ToolAuthorityContract(
                    ToolAuthorityMode.NONE, List.of(), ToolChoiceMode.NONE, "", false);
        }

        public static ToolAuthorityContract unbound(ToolAuthorityMode mode) {
            ToolAuthorityMode typed = mode == null ? ToolAuthorityMode.NONE : mode;
            return typed == ToolAuthorityMode.NONE
                    ? none()
                    : new ToolAuthorityContract(
                            typed, List.of(), ToolChoiceMode.NONE, "", false);
        }

        public static ToolAuthorityContract bound(
                ToolAuthorityMode mode,
                List<String> selectedToolNames,
                ToolChoiceMode toolChoice,
                String namedTool
        ) {
            return new ToolAuthorityContract(
                    mode, selectedToolNames, toolChoice, namedTool, true);
        }

        public List<String> authorizedToolNames() {
            if (!bound) return List.of();
            return toolChoice == ToolChoiceMode.NAMED
                    ? List.of(namedTool)
                    : selectedToolNames;
        }

        public List<String> selectedToolNameHashes() {
            return selectedToolNames.stream()
                    .map(ProviderResponseCapture::toolNameHash)
                    .toList();
        }

        public List<String> authorizedToolNameHashes() {
            return authorizedToolNames().stream()
                    .map(ProviderResponseCapture::toolNameHash)
                    .toList();
        }

        public boolean authorizesExactToolName(String rawToolName) {
            if (!bound || rawToolName == null || !selectedToolNames.contains(rawToolName)) {
                return false;
            }
            return toolChoice != ToolChoiceMode.NAMED || namedTool.equals(rawToolName);
        }

        public ToolAuthorityBinding bindDispatch(
                ProviderTransportHandoff.DispatchEvidence evidence
        ) {
            if (mode == ToolAuthorityMode.NONE) return ToolAuthorityBinding.NOT_APPLICABLE;
            if (!bound) return ToolAuthorityBinding.SURFACE_UNBOUND;
            ProviderTransportHandoff.DispatchEvidence typed = evidence == null
                    ? ProviderTransportHandoff.DispatchEvidence.unbound()
                    : evidence;
            if (typed.providerBodyHash().isBlank()) {
                return ToolAuthorityBinding.DISPATCH_UNBOUND;
            }
            if (mode == ToolAuthorityMode.PROVIDER_NATIVE
                    && !selectedToolNames.equals(typed.nativeTools())) {
                return ToolAuthorityBinding.DISPATCH_SURFACE_MISMATCH;
            }
            if (mode == ToolAuthorityMode.PROVIDER_TEXT_JSON
                    && !typed.nativeTools().isEmpty()) {
                return ToolAuthorityBinding.DISPATCH_SURFACE_MISMATCH;
            }
            return ToolAuthorityBinding.BOUND;
        }
    }

    /** Exact relationship between the selected surface and transport evidence. */
    public enum ToolAuthorityBinding {
        NOT_APPLICABLE,
        SURFACE_UNBOUND,
        DISPATCH_UNBOUND,
        DISPATCH_SURFACE_MISMATCH,
        BOUND
    }

    public enum AttemptKind {
        PRIMARY,
        TRANSIENT_RETRY,
        OUTPUT_CAP_LIFT_RETRY,
        COMPAT_NON_STREAM_RETRY
    }

    public enum RetryReason {
        NONE,
        TRANSIENT_ENGINE_FAILURE,
        OUTPUT_CAP_TRUNCATION,
        MALFORMED_TOOL_ARGUMENTS
    }

    /** Closed completion vocabulary; provider-controlled finish prose is excluded. */
    public enum Completion {
        PROVIDER_EOS,
        PROVIDER_LENGTH,
        UNRECOGNIZED_PROVIDER_STOP,
        RUNTIME_SAFE_CAP,
        RUNTIME_LEGACY_TEXT_STOP,
        STREAM_WITHOUT_EOS,
        RUNTIME_ABORT,
        SYNTHETIC_RETURN
    }

    /** Closed failure vocabulary for attempts or generations returning no response. */
    public enum Failure {
        PROVIDER_TRANSIENT,
        PROVIDER_MALFORMED_RESPONSE,
        PROVIDER_EXCEPTION,
        PROVIDER_RESOURCE_EXHAUSTED,
        HARNESS_EXCEPTION,
        TRANSPORT_UNOBSERVED,
        INTERRUPTION_CAUSE_UNPROVEN,
        RUNTIME_PRE_DISPATCH,
        RUNTIME_EXCEPTION
    }

    public enum Abandonment {
        OPERATOR_CANCELLED,
        WALL_CLOCK_TIMEOUT,
        IDLE_TIMEOUT,
        REPETITION_LOOP,
        INTERRUPTION_CAUSE_UNPROVEN,
        RUNTIME_STREAM_CLOSED,
        SCOPE_CLOSED
    }

    /** Mechanically known initiator of a Talos abort result. */
    public enum AbortOrigin {
        NONE,
        OPERATOR_EXTERNAL,
        RUNTIME_WATCHDOG,
        RUNTIME_THREAD_INTERRUPT,
        RUNTIME_STREAM_CLOSE,
        PROVIDER_TRANSPORT
    }

    public enum AnswerOrigin {
        MODEL_PROVIDER,
        RUNTIME_DETERMINISTIC,
        TOOL_DETERMINISTIC,
        OPERATOR,
        NONE
    }

    public record GenerationEvent(
            long ledgerSequence,
            String generationId,
            Source source,
            Purpose purpose,
            ToolAuthorityMode toolAuthorityMode,
            ToolAuthorityContract toolAuthorityContract,
            String transportOperationId,
            String processReceiptId) {
        public GenerationEvent {
            toolAuthorityMode = requireToolAuthorityMode(
                    source, purpose, toolAuthorityMode);
            toolAuthorityContract = requireToolAuthorityContract(
                    source, purpose, toolAuthorityMode, toolAuthorityContract);
            transportOperationId = optionalSafeIdentifier(
                    transportOperationId, "transportOperationId");
            processReceiptId = optionalSafeIdentifier(
                    processReceiptId, "processReceiptId");
            if (!processReceiptId.isBlank() && transportOperationId.isBlank()) {
                throw new IllegalArgumentException(
                        "generation process receipt requires a transport operation");
            }
        }

        public GenerationEvent(
                long ledgerSequence,
                String generationId,
                Source source,
                Purpose purpose,
                ToolAuthorityMode toolAuthorityMode
        ) {
            this(ledgerSequence, generationId, source, purpose, toolAuthorityMode,
                    ToolAuthorityContract.unbound(toolAuthorityMode), "", "");
        }

        public GenerationEvent(
                long ledgerSequence,
                String generationId,
                Source source,
                Purpose purpose
        ) {
            this(ledgerSequence, generationId, source, purpose, ToolAuthorityMode.NONE,
                    ToolAuthorityContract.none(), "", "");
        }
    }

    public record AttemptEvent(
            long ledgerSequence,
            String generationId,
            String attemptId,
            int attemptOrdinal,
            Source source,
            Purpose purpose,
            AttemptKind attemptKind,
            RetryReason retryReason,
            boolean requestDispatched,
            ToolAuthorityMode toolAuthorityMode,
            ToolAuthorityContract toolAuthorityContract,
            String transportOperationId,
            String processReceiptId) {
        public AttemptEvent {
            toolAuthorityMode = requireToolAuthorityMode(
                    source, purpose, toolAuthorityMode);
            toolAuthorityContract = requireToolAuthorityContract(
                    source, purpose, toolAuthorityMode, toolAuthorityContract);
            transportOperationId = optionalSafeIdentifier(
                    transportOperationId, "transportOperationId");
            processReceiptId = optionalSafeIdentifier(
                    processReceiptId, "processReceiptId");
            if (!processReceiptId.isBlank() && transportOperationId.isBlank()) {
                throw new IllegalArgumentException(
                        "attempt process receipt requires a transport operation");
            }
        }

        public AttemptEvent(
                long ledgerSequence,
                String generationId,
                String attemptId,
                int attemptOrdinal,
                Source source,
                Purpose purpose,
                AttemptKind attemptKind,
                RetryReason retryReason,
                boolean requestDispatched,
                ToolAuthorityMode toolAuthorityMode
        ) {
            this(ledgerSequence, generationId, attemptId, attemptOrdinal,
                    source, purpose, attemptKind, retryReason, requestDispatched,
                    toolAuthorityMode, ToolAuthorityContract.unbound(toolAuthorityMode), "", "");
        }

        public AttemptEvent(
                long ledgerSequence,
                String generationId,
                String attemptId,
                int attemptOrdinal,
                Source source,
                Purpose purpose,
                AttemptKind attemptKind,
                RetryReason retryReason,
                boolean requestDispatched
        ) {
            this(ledgerSequence, generationId, attemptId, attemptOrdinal,
                    source, purpose, attemptKind, retryReason, requestDispatched,
                    ToolAuthorityMode.NONE, ToolAuthorityContract.none(), "", "");
        }
    }

    public record RequestDispatch(
            AttemptEvent attempt,
            ProviderTransportHandoff.DispatchEvidence evidence,
            String transportOperationId,
            String processReceiptId) {
        public RequestDispatch {
            attempt = Objects.requireNonNull(attempt, "dispatch attempt");
            evidence = evidence == null
                    ? ProviderTransportHandoff.DispatchEvidence.unbound()
                    : evidence;
            transportOperationId = requireSafeIdentifier(
                    transportOperationId, "transportOperationId");
            processReceiptId = optionalSafeIdentifier(
                    processReceiptId, "processReceiptId");
            String evidenceReceipt = evidence.processReceipt()
                    .map(ProviderProcessLaunchReceipt::receiptId)
                    .orElse("");
            if (!processReceiptId.equals(evidenceReceipt)) {
                throw new IllegalArgumentException(
                        "dispatch process receipt ID differs from its typed evidence");
            }
        }
    }

    /** Safe, path-free fact from one independent provider transport owner. */
    public record TransportLifecycleEvent(
            int transportLedgerVersion,
            long transportSequence,
            String generationId,
            String attemptId,
            int attemptOrdinal,
            String transportOperationId,
            ProviderTransportAttempt.Transition transition,
            ProviderTransportAttempt.ObservationMode observationMode,
            ProviderTransportAttempt.Settlement settlement,
            ProviderTransportAttempt.Failure failure,
            ProviderTransportAttempt.CancellationState cancellationState,
            ProviderTransportAttempt.CancellationReason cancellationReason,
            ProviderTransportAttempt.WireDisposition wireDisposition,
            boolean quarantineRequired,
            boolean lateAfterGenerationTerminal,
            ProviderTransportAttempt.ProcessBinding processBinding,
            String processReceiptId) {
        public TransportLifecycleEvent {
            if (transportLedgerVersion != TRANSPORT_LEDGER_VERSION) {
                throw new IllegalArgumentException("unsupported transport ledger version");
            }
            if (transportSequence < 1) {
                throw new IllegalArgumentException("transport sequence must be positive");
            }
            generationId = requireSafeIdentifier(generationId, "generationId");
            attemptId = requireSafeIdentifier(attemptId, "attemptId");
            transportOperationId = requireSafeIdentifier(
                    transportOperationId, "transportOperationId");
            if (attemptOrdinal < 1) {
                throw new IllegalArgumentException("attempt ordinal must be positive");
            }
            transition = Objects.requireNonNull(transition, "transport transition");
            observationMode = Objects.requireNonNull(
                    observationMode, "transport observation mode");
            settlement = Objects.requireNonNull(settlement, "transport settlement");
            failure = Objects.requireNonNull(failure, "transport failure");
            cancellationState = Objects.requireNonNull(
                    cancellationState, "transport cancellation state");
            cancellationReason = Objects.requireNonNull(
                    cancellationReason, "transport cancellation reason");
            wireDisposition = Objects.requireNonNull(
                    wireDisposition, "transport wire disposition");
            processBinding = Objects.requireNonNull(
                    processBinding, "transport process binding");
            processReceiptId = optionalSafeIdentifier(
                    processReceiptId, "processReceiptId");
            if ((processBinding == ProviderTransportAttempt.ProcessBinding.UNAVAILABLE)
                    != processReceiptId.isBlank()) {
                throw new IllegalArgumentException(
                        "transport process binding and receipt ID disagree");
            }
        }
    }

    public record AttemptResponse(AttemptEvent attempt, Response response, Receipt receipt) {
        public AttemptResponse(AttemptEvent attempt, Response response) {
            this(attempt, response, Receipt.none());
        }
    }

    public record AttemptFailure(AttemptEvent attempt, Failure failure) {}

    public record AttemptAbandonment(AttemptEvent attempt, Abandonment abandonment) {}

    public record GenerationFailure(GenerationEvent generation, Failure failure) {}

    public record GenerationAbandonment(GenerationEvent generation, Abandonment abandonment) {}

    public record GenerationCompletion(GenerationEvent generation, Receipt selectedResponse) {}

    /**
     * Listener with compatibility shims for the original three-callback API.
     * New runtime listeners should implement the typed event overloads.
     */
    public interface Listener {
        default void generationStarted(GenerationEvent event) {}

        default void attemptStarted(AttemptEvent event) {
            attemptStarted(event.source());
        }

        default void requestDispatched(AttemptEvent event) {}

        default void requestDispatched(RequestDispatch event) {
            requestDispatched(event.attempt());
        }

        default void transportUnobserved(AttemptEvent event) {}

        default void transportLifecycle(TransportLifecycleEvent event) {}

        default Receipt responseReturned(AttemptResponse event) {
            return responseReturned(event.response());
        }

        default void attemptFailed(AttemptFailure event) {
            attemptFailed(event.attempt().source(), event.failure());
        }

        default void attemptAbandoned(AttemptAbandonment event) {}

        default void generationFailed(GenerationFailure event) {}

        default void generationAbandoned(GenerationAbandonment event) {}

        default void generationCompleted(GenerationCompletion event) {}

        @Deprecated(forRemoval = false)
        default void attemptStarted(Source source) {}

        @Deprecated(forRemoval = false)
        default Receipt responseReturned(Response response) {
            return null;
        }

        @Deprecated(forRemoval = false)
        default void attemptFailed(Source source, Failure failure) {}
    }

    /** In-memory source evidence, never a grant of execution authority. */
    public static final class TextProposalSource {
        private final String source;
        private final String projectedText;

        private TextProposalSource(String source, String projectedText) {
            this.source = Objects.requireNonNull(source, "provider text source");
            this.projectedText = Objects.requireNonNull(projectedText, "projected provider text");
        }

        public static TextProposalSource captured(String source, String projectedText) {
            return new TextProposalSource(source, projectedText);
        }

        public boolean matchesProjectedText(String text) {
            return projectedText.equals(text);
        }

        /** Only executable projection validation consumes the unsanitized source. */
        public String sourceTextForValidation() { return source; }

        @Override
        public String toString() {
            return "TextProposalSource[chars=" + source.length() + ", hash=" + sha256(source) + "]";
        }
    }

    public record Response(
            String assistantText,
            int nativeToolCallCount,
            List<ChatMessage.NativeToolCall> nativeToolCalls,
            Source source,
            Completion completion,
            boolean normalized,
            boolean truncated,
            boolean textualToolCallPresent,
            TextProposalSource textProposalSource) {
        public Response(
                String assistantText,
                int nativeToolCallCount,
                List<ChatMessage.NativeToolCall> nativeToolCalls,
                Source source,
                Completion completion,
                boolean normalized,
                boolean truncated,
                boolean textualToolCallPresent) {
            this(assistantText, nativeToolCallCount, nativeToolCalls, source, completion,
                    normalized, truncated, textualToolCallPresent, null);
        }
        public Response(
                String assistantText,
                int nativeToolCallCount,
                Source source,
                Completion completion,
                boolean normalized,
                boolean truncated) {
            this(assistantText, nativeToolCallCount, List.of(), source, completion,
                    normalized, truncated, false);
        }

        public Response(
                String assistantText,
                int nativeToolCallCount,
                Source source,
                Completion completion,
                boolean normalized,
                boolean truncated,
                boolean textualToolCallPresent) {
            this(assistantText, nativeToolCallCount, List.of(), source, completion,
                    normalized, truncated, textualToolCallPresent);
        }

        public Response(
                String assistantText,
                List<ChatMessage.NativeToolCall> nativeToolCalls,
                Source source,
                Completion completion,
                boolean normalized,
                boolean truncated,
                boolean textualToolCallPresent) {
            this(assistantText,
                    nativeToolCalls == null ? 0 : nativeToolCalls.size(),
                    nativeToolCalls,
                    source,
                    completion,
                    normalized,
                    truncated,
                    textualToolCallPresent);
        }

        public Response(
                String assistantText,
                List<ChatMessage.NativeToolCall> nativeToolCalls,
                Source source,
                Completion completion,
                boolean normalized,
                boolean truncated,
                boolean textualToolCallPresent,
                TextProposalSource textProposalSource) {
            this(assistantText, nativeToolCalls == null ? 0 : nativeToolCalls.size(),
                    nativeToolCalls, source, completion, normalized, truncated,
                    textualToolCallPresent, textProposalSource);
        }

        public Response {
            assistantText = assistantText == null ? "" : assistantText;
            if (nativeToolCallCount < 0) {
                throw new IllegalArgumentException("native tool-call count must not be negative");
            }
            nativeToolCalls = nativeToolCalls == null ? List.of() : List.copyOf(nativeToolCalls);
            if (!nativeToolCalls.isEmpty() && nativeToolCallCount != nativeToolCalls.size()) {
                throw new IllegalArgumentException("native tool-call count must match captured proposals");
            }
            source = Objects.requireNonNull(source, "response source");
            completion = Objects.requireNonNull(completion, "response completion");
            if (textProposalSource != null && !textProposalSource.matchesProjectedText(assistantText)) {
                throw new IllegalArgumentException("provider text source does not match projected response");
            }
        }

        public boolean providerBacked() {
            return source == Source.FOREGROUND_PROVIDER || source == Source.BACKGROUND_PROVIDER;
        }

        public boolean foreground() {
            return source == Source.FOREGROUND_PROVIDER;
        }

        public boolean toolCallPresent() {
            return nativeToolCallCount > 0 || textualToolCallPresent;
        }

        public boolean completedNormally() {
            return foreground()
                    && completion == Completion.PROVIDER_EOS
                    && !normalized
                    && !truncated;
        }
    }

    /**
     * Redacted identity of one provider-authored native tool proposal.
     *
     * <p>Only hashes and a bounded ordinal cross the trace boundary. The provider's
     * raw tool name, argument keys, and argument values are deliberately absent.
     */
    public record ToolProposalReceipt(
            int proposalOrdinal,
            String toolNameHash,
            String argumentsHash) {
        public ToolProposalReceipt {
            if (proposalOrdinal < 1 || proposalOrdinal > MAX_TRACE_TOOL_PROPOSALS) {
                throw new IllegalArgumentException("proposal ordinal is outside the trace bound");
            }
            toolNameHash = safeHash(toolNameHash);
            argumentsHash = safeHash(argumentsHash);
        }
    }

    /**
     * Capability-like response receipt.
     *
     * <p>Public constructors remain for source compatibility, but create detached,
     * unauthenticated values. Only {@link Attempt#respond(Response)} can mint an
     * owner-bound receipt, and only {@link Generation#complete(Receipt)} can select
     * it. Eligibility accessors stay false until that exact owner selects the exact
     * receipt object. Copying its visible fields therefore cannot forge provenance.
     */
    public static final class Receipt {
        private final String generationId;
        private final String attemptId;
        private final int attemptOrdinal;
        private final Source source;
        private final Purpose purpose;
        private final Completion completion;
        private final boolean terminalEligibilityClaim;
        private final boolean toolEligibilityClaim;
        private final ToolAuthorityMode toolAuthorityMode;
        private final ToolAuthorityContract toolAuthorityContract;
        private final ToolAuthorityBinding toolAuthorityBinding;
        private final String transportOperationId;
        private final String processReceiptId;
        private final String assistantHash;
        private final List<ToolProposalReceipt> toolProposals;
        private final Generation ownerGeneration;
        private final Attempt ownerAttempt;
        private final ProviderReadInputBinding readInputBinding;
        private volatile boolean selected;

        public Receipt(
                String generationId,
                Source source,
                Completion completion,
                boolean providerTerminalEligible) {
            this(generationId, "", 0, source, purposeFor(source), completion,
                    providerTerminalEligible, false, ToolAuthorityMode.NONE,
                    ToolAuthorityContract.none(), ToolAuthorityBinding.NOT_APPLICABLE,
                    "", "", "", List.of(), null, null, ProviderReadInputBinding.notRequested());
        }

        public Receipt(
                String generationId,
                Source source,
                Completion completion,
                boolean providerTerminalEligible,
                boolean providerToolCallEligible) {
            this(generationId, "", 0, source, purposeFor(source), completion,
                    providerTerminalEligible, providerToolCallEligible,
                    ToolAuthorityMode.NONE, ToolAuthorityContract.none(),
                    ToolAuthorityBinding.NOT_APPLICABLE, "", "", "",
                    List.of(), null, null, ProviderReadInputBinding.notRequested());
        }

        public Receipt(
                String generationId,
                Source source,
                Completion completion,
                boolean providerTerminalEligible,
                boolean providerToolCallEligible,
                String assistantHash) {
            this(generationId, "", 0, source, purposeFor(source), completion,
                    providerTerminalEligible, providerToolCallEligible,
                    ToolAuthorityMode.NONE, ToolAuthorityContract.none(),
                    ToolAuthorityBinding.NOT_APPLICABLE, "", "", assistantHash,
                    List.of(), null, null, ProviderReadInputBinding.notRequested());
        }

        public Receipt(
                String generationId,
                String attemptId,
                int attemptOrdinal,
                Source source,
                Purpose purpose,
                Completion completion,
                boolean providerTerminalEligible,
                boolean providerToolCallEligible,
                String assistantHash) {
            this(generationId, attemptId, attemptOrdinal, source, purpose, completion,
                    providerTerminalEligible, providerToolCallEligible,
                    ToolAuthorityMode.NONE, ToolAuthorityContract.none(),
                    ToolAuthorityBinding.NOT_APPLICABLE, "", "", assistantHash,
                    List.of(), null, null, ProviderReadInputBinding.notRequested());
        }

        private Receipt(
                String generationId,
                String attemptId,
                int attemptOrdinal,
                Source source,
                Purpose purpose,
                Completion completion,
                boolean providerTerminalEligible,
                boolean providerToolCallEligible,
                ToolAuthorityMode toolAuthorityMode,
                ToolAuthorityContract toolAuthorityContract,
                ToolAuthorityBinding toolAuthorityBinding,
                String transportOperationId,
                String processReceiptId,
                String assistantHash,
                List<ToolProposalReceipt> toolProposals,
                Generation ownerGeneration,
                Attempt ownerAttempt,
                ProviderReadInputBinding readInputBinding) {
            this.generationId = safe(generationId);
            this.attemptId = safe(attemptId);
            this.attemptOrdinal = Math.max(0, attemptOrdinal);
            this.source = Objects.requireNonNull(source, "receipt source");
            this.purpose = purpose == null ? purposeFor(source) : purpose;
            this.completion = Objects.requireNonNull(completion, "receipt completion");
            this.toolAuthorityMode = requireToolAuthorityMode(
                    source, this.purpose, toolAuthorityMode);
            this.toolAuthorityContract = requireToolAuthorityContract(
                    source, this.purpose, this.toolAuthorityMode, toolAuthorityContract);
            this.toolAuthorityBinding = toolAuthorityBinding == null
                    ? ToolAuthorityBinding.NOT_APPLICABLE
                    : toolAuthorityBinding;
            this.transportOperationId = safe(transportOperationId);
            this.processReceiptId = optionalSafeIdentifier(
                    processReceiptId, "processReceiptId");
            this.terminalEligibilityClaim = providerTerminalEligible
                    && !this.generationId.isBlank()
                    && !this.attemptId.isBlank()
                    && source == Source.FOREGROUND_PROVIDER
                    && completion == Completion.PROVIDER_EOS;
            this.toolEligibilityClaim = providerToolCallEligible
                    && !this.generationId.isBlank()
                    && !this.attemptId.isBlank()
                    && source == Source.FOREGROUND_PROVIDER
                    && completion == Completion.PROVIDER_EOS
                    && this.toolAuthorityMode != ToolAuthorityMode.NONE
                    && this.toolAuthorityBinding == ToolAuthorityBinding.BOUND;
            this.assistantHash = safe(assistantHash);
            this.toolProposals = toolProposals == null ? List.of() : List.copyOf(toolProposals);
            this.ownerGeneration = ownerGeneration;
            this.ownerAttempt = ownerAttempt;
            this.readInputBinding = readInputBinding;
        }

        public String generationId() { return generationId; }
        public String attemptId() { return attemptId; }
        public int attemptOrdinal() { return attemptOrdinal; }
        public Source source() { return source; }
        public Purpose purpose() { return purpose; }
        public Completion completion() { return completion; }
        public ToolAuthorityMode toolAuthorityMode() { return toolAuthorityMode; }
        public ToolAuthorityContract toolAuthorityContract() { return toolAuthorityContract; }
        public ToolAuthorityBinding toolAuthorityBinding() { return toolAuthorityBinding; }
        public String transportOperationId() { return transportOperationId; }
        public String processReceiptId() { return processReceiptId; }
        public String assistantHash() { return assistantHash; }

        public ProviderReadInputBinding readInputBinding() {
            boolean deliveryObserved = ownerAttempt != null && ownerAttempt.transport != null
                    && ownerAttempt.transport.provesProviderTerminal();
            return readInputBinding.forSelectedResponse(selected, providerTerminalEligible(), deliveryObserved);
        }

        /** Ownership is checked against the exact active scope, not its display identity. */
        public boolean belongsToCurrentScope() {
            Scope current = CURRENT.get();
            if (current == null || ownerGeneration == null || ownerAttempt == null
                    || ownerGeneration.owner != current) return false;
            synchronized (current) {
                return !current.closed;
            }
        }

        /** True only after the owning generation selected this exact receipt. */
        public boolean selected() { return selected; }

        public boolean providerTerminalEligible() {
            return selected && terminalEligibilityClaim;
        }

        public boolean providerToolCallEligible() {
            return selected && toolEligibilityClaim;
        }

        /** Exact raw-name membership in this selected provider request authority. */
        public boolean authorizesExactToolName(String rawToolName) {
            return selected && toolEligibilityClaim
                    && toolAuthorityContract.authorizesExactToolName(rawToolName);
        }

        public int capturedToolProposalCount() {
            return selected ? toolProposals.size() : 0;
        }

        public Optional<ToolProposalReceipt> selectedToolProposal(int ordinal) {
            if (!selected || !toolEligibilityClaim || ordinal < 1) return Optional.empty();
            return toolProposals.stream()
                    .filter(proposal -> proposal.proposalOrdinal() == ordinal)
                    .findFirst();
        }

        private boolean ownedBy(Generation generation, Attempt attempt) {
            return ownerGeneration == generation && ownerAttempt == attempt;
        }

        private void markSelected() {
            selected = true;
        }

        public static Receipt detached(Source source, Completion completion) {
            return new Receipt("", "", 0, source, purposeFor(source), completion,
                    false, false, ToolAuthorityMode.NONE, ToolAuthorityContract.none(),
                    ToolAuthorityBinding.NOT_APPLICABLE,
                    "", "", "", List.of(), null, null, ProviderReadInputBinding.notRequested());
        }

        public static Receipt none() {
            return detached(Source.PLACEHOLDER_RUNTIME, Completion.SYNTHETIC_RETURN);
        }

        @Override
        public String toString() {
            return "Receipt[generationId=" + generationId
                    + ", attemptId=" + attemptId
                    + ", attemptOrdinal=" + attemptOrdinal
                    + ", source=" + source
                    + ", purpose=" + purpose
                    + ", completion=" + completion
                    + ", selected=" + selected + "]";
        }
    }

    public static final class Scope {
        private final Listener listener;
        private final String idPrefix;
        private long ledgerSequence;
        private int generationSequence;
        private boolean closed;
        private final List<Generation> openGenerations = new ArrayList<>();
        private final Set<OwnedProviderTransportAttempt> liveTransports =
                ConcurrentHashMap.newKeySet();
        private volatile boolean transportQuarantineRequired;

        private Scope(Listener listener, String scopeIdentity) {
            this.listener = Objects.requireNonNull(listener, "provider response listener");
            String normalized = safe(scopeIdentity).replaceAll("[^A-Za-z0-9._-]", "-");
            this.idPrefix = normalized.isBlank()
                    ? "provider-scope-" + UUID.randomUUID()
                    : normalized;
        }

        public Generation openGeneration(Source source, Purpose purpose) {
            return openGeneration(source, purpose, ToolAuthorityMode.NONE);
        }

        public Generation openGeneration(
                Source source,
                Purpose purpose,
                ToolAuthorityMode toolAuthorityMode
        ) {
            return openGeneration(
                    source, purpose, ToolAuthorityContract.unbound(toolAuthorityMode));
        }

        public Generation openGeneration(
                Source source,
                Purpose purpose,
                ToolAuthorityContract toolAuthorityContract
        ) {
            Objects.requireNonNull(source, "source");
            Purpose typedPurpose = purpose == null ? purposeFor(source) : purpose;
            if (typedPurpose != purposeFor(source)) {
                throw new IllegalArgumentException(
                        "generation source and purpose must use the canonical pairing");
            }
            ToolAuthorityContract typedToolAuthorityContract =
                    requireToolAuthorityContract(
                            source,
                            typedPurpose,
                            toolAuthorityContract == null
                                    ? ToolAuthorityMode.NONE
                                    : toolAuthorityContract.mode(),
                            toolAuthorityContract);
            synchronized (this) {
                if (closed) {
                    return Generation.detached(
                            source, typedPurpose, typedToolAuthorityContract);
                }
                if ((source == Source.FOREGROUND_PROVIDER
                        || source == Source.BACKGROUND_PROVIDER)
                        && (!liveTransports.isEmpty() || transportQuarantineRequired)) {
                    throw new IllegalStateException(
                            "provider transport predecessor is live or quarantine-required");
                }
                String generationId = idPrefix + "-generation-" + (++generationSequence);
                Generation generation = new Generation(
                        this,
                        generationId,
                        source,
                        typedPurpose,
                        typedToolAuthorityContract);
                openGenerations.add(generation);
                listener.generationStarted(generation.event(nextSequence()));
                return generation;
            }
        }

        public void close() {
            List<Generation> generations;
            synchronized (this) {
                closed = true;
                generations = List.copyOf(openGenerations);
            }
            for (Generation generation : generations) {
                generation.abandon(Abandonment.SCOPE_CLOSED);
                generation.awaitOrQuarantineTransport(TRANSPORT_SETTLEMENT_GRACE_MS);
            }
            // A generation can already be terminal while its independently
            // owned transport is still draining. Trace completion must retain
            // that late settlement or freeze an explicit quarantine receipt;
            // openGenerations alone cannot find this case.
            for (OwnedProviderTransportAttempt transport : List.copyOf(liveTransports)) {
                if (!transport.awaitSettlement(TRANSPORT_SETTLEMENT_GRACE_MS)) {
                    transport.quarantine();
                }
            }
        }

        private void transportOpened(OwnedProviderTransportAttempt transport) {
            liveTransports.add(transport);
        }

        private void transportSettled(
                OwnedProviderTransportAttempt transport,
                boolean quarantineRequired) {
            if (quarantineRequired) transportQuarantineRequired = true;
            // Publish quarantine before removing the live predecessor. An
            // admission racing this pair must observe at least one blocker.
            liveTransports.remove(transport);
        }

        private long nextSequence() {
            return ++ledgerSequence;
        }
    }

    public static final class Generation {
        private final Scope owner;
        private final String generationId;
        private final Source source;
        private final Purpose purpose;
        private final ToolAuthorityMode toolAuthorityMode;
        private final ToolAuthorityContract toolAuthorityContract;
        private int attemptSequence;
        private Attempt activeAttempt;
        private Receipt selectableReceipt;
        private boolean terminal;

        private Generation(
                Scope owner,
                String generationId,
                Source source,
                Purpose purpose,
                ToolAuthorityContract toolAuthorityContract
        ) {
            this.owner = owner;
            this.generationId = generationId;
            this.source = source;
            this.purpose = purpose;
            this.toolAuthorityContract = requireToolAuthorityContract(
                    source,
                    purpose,
                    toolAuthorityContract == null
                            ? ToolAuthorityMode.NONE
                            : toolAuthorityContract.mode(),
                    toolAuthorityContract);
            this.toolAuthorityMode = this.toolAuthorityContract.mode();
        }

        private static Generation detached(
                Source source,
                Purpose purpose,
                ToolAuthorityContract toolAuthorityContract
        ) {
            return new Generation(null, "", source, purpose, toolAuthorityContract);
        }

        public String generationId() {
            return generationId;
        }

        public Source source() {
            return source;
        }

        public Purpose purpose() {
            return purpose;
        }

        public ToolAuthorityMode toolAuthorityMode() {
            return toolAuthorityMode;
        }

        public ToolAuthorityContract toolAuthorityContract() {
            return toolAuthorityContract;
        }

        /**
         * Returns whether this generation is bound to an active capture scope.
         * Detached generations preserve the no-capture runtime path and must
         * never influence provider retry or timeout behavior.
         */
        public boolean attached() {
            return owner != null;
        }

        /** Whether the latest physical attempt crossed its transport handoff. */
        public boolean latestAttemptRequestDispatched() {
            if (owner == null) return false;
            synchronized (owner) {
                return activeAttempt != null && activeAttempt.requestDispatched;
            }
        }

        /** Exact terminal failure recorded by the latest attempt, when present. */
        public Failure latestAttemptFailure() {
            if (owner == null) return null;
            synchronized (owner) {
                return activeAttempt == null ? null : activeAttempt.failure;
            }
        }

        public Attempt startAttempt(AttemptKind kind, RetryReason retryReason) {
            if (owner == null) return Attempt.detached(this, kind, retryReason);
            synchronized (owner) {
                if (terminal || owner.closed) return Attempt.detached(this, kind, retryReason);
                if (activeAttempt != null && !activeAttempt.terminal) {
                    throw new IllegalStateException("physical provider attempt already active");
                }
                if (activeAttempt != null && activeAttempt.transportLive()) {
                    throw new IllegalStateException(
                            "provider retry cannot overlap a live transport operation");
                }
                if (owner.transportQuarantineRequired) {
                    throw new IllegalStateException(
                            "provider retry is blocked by a quarantine-required transport");
                }
                selectableReceipt = null;
                int ordinal = ++attemptSequence;
                Attempt attempt = new Attempt(
                        this,
                        generationId + "-attempt-" + ordinal,
                        ordinal,
                        kind == null ? AttemptKind.PRIMARY : kind,
                        retryReason == null ? RetryReason.NONE : retryReason);
                activeAttempt = attempt;
                owner.listener.attemptStarted(attempt.event(owner.nextSequence()));
                return attempt;
            }
        }

        public void complete(Receipt receipt) {
            if (owner == null || receipt == null) return;
            synchronized (owner) {
                if (terminal) return;
                if (activeAttempt != null && !activeAttempt.terminal) {
                    throw new IllegalStateException("cannot complete generation with an active attempt");
                }
                if (receipt != selectableReceipt
                        || activeAttempt == null
                        || !receipt.ownedBy(this, activeAttempt)
                        || !generationId.equals(receipt.generationId())
                        || !activeAttempt.attemptId.equals(receipt.attemptId())
                        || activeAttempt.ordinal != receipt.attemptOrdinal()
                        || receipt.source() != source
                        || receipt.purpose() != purpose) {
                    throw new IllegalArgumentException(
                            "generation completion receipt is fabricated, stale, or not selectable");
                }
                receipt.markSelected();
                terminal = true;
                activeAttempt.generationTerminalized();
                owner.openGenerations.remove(this);
                owner.listener.generationCompleted(new GenerationCompletion(
                        event(owner.nextSequence()), receipt));
            }
        }

        public void fail(Failure failure) {
            if (owner == null) return;
            synchronized (owner) {
                if (terminal) return;
                Failure typed = failure == null ? Failure.RUNTIME_EXCEPTION : failure;
                if (activeAttempt != null && activeAttempt.failure != null) {
                    typed = activeAttempt.failure;
                }
                if (activeAttempt != null && !activeAttempt.terminal) {
                    activeAttempt.failLocked(typed);
                }
                selectableReceipt = null;
                terminal = true;
                if (activeAttempt != null) activeAttempt.generationTerminalized();
                owner.openGenerations.remove(this);
                owner.listener.generationFailed(new GenerationFailure(
                        event(owner.nextSequence()), typed));
            }
        }

        public void abandon(Abandonment abandonment) {
            tryAbandon(abandonment);
        }

        /**
         * Atomically attempts to make abandonment the generation's terminal
         * fact. The return value lets a caller-visible outcome arbiter decline
         * an abort when a provider completion or failure already won.
         */
        public boolean tryAbandon(Abandonment abandonment) {
            if (owner == null) return false;
            Attempt cancellationOwner;
            synchronized (owner) {
                if (terminal) return false;
                Abandonment typed = abandonment == null ? Abandonment.SCOPE_CLOSED : abandonment;
                Attempt terminalAttempt = activeAttempt;
                Runnable terminalPublication = () -> {
                    if (terminalAttempt != null && !terminalAttempt.terminal) {
                        terminalAttempt.abandonLocked(typed, false);
                    }
                    selectableReceipt = null;
                    terminal = true;
                    owner.openGenerations.remove(this);
                    owner.listener.generationAbandoned(new GenerationAbandonment(
                            event(owner.nextSequence()), typed));
                };
                // The transport monitor is the publication gate: the late bit
                // and both abandonment records become visible atomically with
                // respect to every transport callback. No event marked late
                // can precede either terminal record.
                if (terminalAttempt == null) {
                    terminalPublication.run();
                } else {
                    terminalAttempt.publishGenerationTerminal(terminalPublication);
                }
                cancellationOwner = terminalAttempt;
            }
            // Persist the attempt and generation terminal facts before any
            // independently owned cancellation acknowledgement or settlement.
            // A transport that settles after this point is therefore both
            // mechanically late and ordered after the terminal trace event.
            if (cancellationOwner != null) {
                cancellationOwner.requestTransportCancellation(abandonment);
            }
            return true;
        }

        public boolean terminal() {
            if (owner == null) return true;
            synchronized (owner) {
                return terminal;
            }
        }

        /** Waits for the independent transport owner without changing its fact. */
        public boolean awaitTransportSettlement(long timeoutMs) {
            Attempt attempt;
            if (owner == null) return true;
            synchronized (owner) {
                attempt = activeAttempt;
            }
            return attempt == null || attempt.awaitTransportSettlement(timeoutMs);
        }

        /**
         * Bounds cancellation drain, then freezes one conservative quarantine
         * receipt before turn evidence can be made immutable.
         */
        public boolean awaitOrQuarantineTransport(long timeoutMs) {
            Attempt attempt;
            if (owner == null) return true;
            synchronized (owner) {
                attempt = activeAttempt;
            }
            if (attempt == null || attempt.awaitTransportSettlement(timeoutMs)) return true;
            attempt.quarantineTransport();
            return false;
        }

        private GenerationEvent event(long sequence) {
            String operationId = activeAttempt == null || activeAttempt.transport == null
                    ? ""
                    : activeAttempt.transport.operationId();
            String receiptId = activeAttempt == null || activeAttempt.transport == null
                    ? ""
                    : activeAttempt.transport.processReceiptId();
            return new GenerationEvent(
                    sequence, generationId, source, purpose, toolAuthorityMode,
                    toolAuthorityContract, operationId, receiptId);
        }
    }

    public static final class Attempt {
        private final Generation generation;
        private final String attemptId;
        private final int ordinal;
        private final AttemptKind kind;
        private final RetryReason retryReason;
        private boolean requestDispatched;
        private ProviderTransportHandoff.DispatchEvidence dispatchEvidence =
                ProviderTransportHandoff.DispatchEvidence.unbound();
        private OwnedProviderTransportAttempt transport;
        private boolean transportUnobserved;
        private boolean terminal;
        private Failure failure;
        private ProviderReadInputBinding.PreparedInputs preparedReadInputs =
                ProviderReadInputBinding.noPreparedInputs();
        private boolean preparedReadInputsRecorded;

        private Attempt(
                Generation generation,
                String attemptId,
                int ordinal,
                AttemptKind kind,
                RetryReason retryReason) {
            this.generation = generation;
            this.attemptId = attemptId;
            this.ordinal = ordinal;
            this.kind = kind;
            this.retryReason = retryReason;
        }

        private static Attempt detached(Generation generation, AttemptKind kind, RetryReason retryReason) {
            return new Attempt(generation, "", 0,
                    kind == null ? AttemptKind.PRIMARY : kind,
                    retryReason == null ? RetryReason.NONE : retryReason);
        }

        public void requestDispatched() {
            requestDispatched(ProviderTransportHandoff.DispatchEvidence.unbound());
        }

        /** The prepared request is captured once, before its local transport admission. */
        public void bindPreparedReadInputs(ChatRequest request, String sanitizerVersion) {
            Scope owner = generation.owner;
            if (owner == null) return;
            synchronized (owner) {
                if (terminal || generation.terminal || requestDispatched || transportUnobserved
                        || preparedReadInputsRecorded)
                    throw new IllegalStateException("prepared read inputs cannot be rebound");
                preparedReadInputs = ProviderReadInputBinding.prepare(request, sanitizerVersion);
                preparedReadInputsRecorded = true;
            }
        }

        public void requestDispatched(ProviderTransportHandoff.DispatchEvidence evidence) {
            if (!tryRequestDispatched(evidence)) {
                throw new ProviderTransportHandoff.DispatchRejected();
            }
        }

        /** Admits the exact cooperative transport owner used by production engines. */
        public ProviderTransportAttempt admitTransport(
                ProviderTransportHandoff.DispatchEvidence evidence) {
            Scope owner = generation.owner;
            if (owner == null) return ProviderTransportAttempt.unobservedAttempt();
            synchronized (owner) {
                if (terminal || generation.terminal || requestDispatched
                        || transportUnobserved) {
                    throw new ProviderTransportHandoff.DispatchRejected();
                }
                return admitTransportLocked(
                        evidence, ProviderTransportAttempt.ObservationMode.OBSERVED);
            }
        }

        /**
         * Atomically admits the local transport handoff. Detached attempts
         * remain admitted so the no-capture runtime path is behavior-neutral.
         * A terminal or duplicate captured handoff is rejected before an
         * engine can execute its immediately following send operation. Once
         * admitted, this owner does not control the bounded gap before that
         * physical send begins.
         */
        public boolean tryRequestDispatched() {
            return tryRequestDispatched(ProviderTransportHandoff.DispatchEvidence.unbound());
        }

        public boolean tryRequestDispatched(
                ProviderTransportHandoff.DispatchEvidence evidence) {
            Scope owner = generation.owner;
            if (owner == null) return true;
            synchronized (owner) {
                if (terminal || generation.terminal || requestDispatched
                        || transportUnobserved) return false;
                admitTransportLocked(
                        evidence, ProviderTransportAttempt.ObservationMode.LEGACY_UNOBSERVED);
                return true;
            }
        }

        private OwnedProviderTransportAttempt admitTransportLocked(
                ProviderTransportHandoff.DispatchEvidence evidence,
                ProviderTransportAttempt.ObservationMode observationMode) {
            Scope owner = generation.owner;
            if ((generation.source == Source.FOREGROUND_PROVIDER
                    || generation.source == Source.BACKGROUND_PROVIDER)
                    && (!owner.liveTransports.isEmpty()
                        || owner.transportQuarantineRequired)) {
                throw new IllegalStateException(
                        "provider transport predecessor is live or quarantine-required");
            }
            dispatchEvidence = evidence == null
                    ? ProviderTransportHandoff.DispatchEvidence.unbound()
                    : evidence;
            requestDispatched = true;
            transport = new OwnedProviderTransportAttempt(this, observationMode);
            owner.transportOpened(transport);
            owner.listener.requestDispatched(new RequestDispatch(
                    event(owner.nextSequence()),
                    dispatchEvidence,
                    transport.operationId(),
                    transport.processReceiptId()));
            transport.handoffAdmitted();
            return transport;
        }

        /** Records a legacy path whose physical provider handoff cannot be observed. */
        public void transportUnobserved() {
            Scope owner = generation.owner;
            if (owner == null) return;
            synchronized (owner) {
                if (terminal || generation.terminal || requestDispatched
                        || transportUnobserved) {
                    throw new IllegalStateException(
                            "provider transport observation state is already terminal");
                }
                transportUnobserved = true;
                owner.listener.transportUnobserved(event(owner.nextSequence()));
            }
        }

        public Receipt respond(Response response) {
            Objects.requireNonNull(response, "response");
            Scope owner = generation.owner;
            if (owner == null) return Receipt.detached(response.source(), response.completion());
            synchronized (owner) {
                if (terminal || generation.terminal) return Receipt.none();
                if (response.source() != generation.source) {
                    throw new IllegalArgumentException(
                            "response source differs from its generation source");
                }
                boolean syntheticUndispatched = !requestDispatched
                        && response.completion() == Completion.SYNTHETIC_RETURN
                        && (generation.source == Source.PLACEHOLDER_RUNTIME
                            || generation.source == Source.SCRIPTED_HARNESS);
                boolean legacyUnobservedProvider = !requestDispatched
                        && transportUnobserved
                        && (generation.source == Source.FOREGROUND_PROVIDER
                            || generation.source == Source.BACKGROUND_PROVIDER);
                if (!requestDispatched && !syntheticUndispatched
                        && !legacyUnobservedProvider) {
                    throw new IllegalStateException(
                            "response cannot close an undispatched provider attempt");
                }
                if (requestDispatched && transport != null) {
                    transport.settleLegacyIfNeeded();
                    if (!transport.settled()) {
                        transport.quarantine();
                        throw new IllegalStateException(
                                "provider response arrived before transport settlement");
                    }
                    if (!transport.responseCompatible(response)) {
                        throw new IllegalStateException(
                                "provider response completion " + response.completion()
                                        + " contradicts transport "
                                        + transport.settlementSummary()
                                        + " for " + response.assistantText().length()
                                        + " assistant chars");
                    }
                }
                terminal = true;
                AttemptEvent event = event(owner.nextSequence());
                boolean responseMatchesToolAuthorityMode = switch (
                        generation.toolAuthorityMode) {
                    case NONE -> false;
                    case PROVIDER_NATIVE -> response.nativeToolCallCount() > 0;
                    case PROVIDER_TEXT_JSON -> response.nativeToolCallCount() == 0
                            && response.textualToolCallPresent();
                };
                ToolAuthorityBinding toolAuthorityBinding =
                        generation.toolAuthorityContract.bindDispatch(dispatchEvidence);
                boolean providerTerminalTransport = transport != null
                        && transport.provesProviderTerminal();
                Receipt receipt = new Receipt(
                        generation.generationId,
                        attemptId,
                        ordinal,
                        response.source(),
                        generation.purpose,
                        response.completion(),
                        requestDispatched && providerTerminalTransport
                                && response.completedNormally(),
                        requestDispatched && response.foreground()
                                && providerTerminalTransport
                                && responseMatchesToolAuthorityMode,
                        generation.toolAuthorityMode,
                        generation.toolAuthorityContract,
                        toolAuthorityBinding,
                        transport == null ? "" : transport.operationId(),
                        transport == null ? "" : transport.processReceiptId(),
                        sha256(response.assistantText()),
                        proposalReceipts(response.nativeToolCalls()),
                        generation,
                        this,
                        ProviderReadInputBinding.bind(preparedReadInputs, dispatchEvidence));
                generation.selectableReceipt = receipt;
                AttemptResponse typed = new AttemptResponse(event, response, receipt);
                owner.listener.responseReturned(typed);
                return receipt;
            }
        }

        public void fail(Failure failure) {
            Scope owner = generation.owner;
            if (owner == null) return;
            synchronized (owner) {
                failLocked(failure == null ? Failure.RUNTIME_EXCEPTION : failure);
            }
        }

        private void failLocked(Failure failure) {
            Scope owner = generation.owner;
            if (terminal || generation.terminal || owner == null) return;
            if (transport != null) transport.settleFromAttemptFailure();
            terminal = true;
            this.failure = failure;
            owner.listener.attemptFailed(new AttemptFailure(
                    event(owner.nextSequence()), failure));
        }

        public void abandon(Abandonment abandonment) {
            Scope owner = generation.owner;
            if (owner == null) return;
            synchronized (owner) {
                abandonLocked(
                        abandonment == null ? Abandonment.SCOPE_CLOSED : abandonment,
                        true);
            }
        }

        private void abandonLocked(Abandonment abandonment, boolean requestCancellation) {
            Scope owner = generation.owner;
            if (terminal || generation.terminal || owner == null) return;
            if (requestCancellation && transport != null) {
                transport.requestCancellation(transportCancellationReason(abandonment));
            }
            terminal = true;
            owner.listener.attemptAbandoned(new AttemptAbandonment(
                    event(owner.nextSequence()), abandonment));
        }

        private void requestTransportCancellation(Abandonment abandonment) {
            if (transport != null) {
                transport.requestCancellation(transportCancellationReason(
                        abandonment == null ? Abandonment.SCOPE_CLOSED : abandonment));
            }
        }

        private AttemptEvent event(long sequence) {
            return new AttemptEvent(
                    sequence,
                    generation.generationId,
                    attemptId,
                    ordinal,
                    generation.source,
                    generation.purpose,
                    kind,
                    retryReason,
                    requestDispatched,
                    generation.toolAuthorityMode,
                    generation.toolAuthorityContract,
                    transport == null ? "" : transport.operationId(),
                    transport == null ? "" : transport.processReceiptId());
        }

        private boolean transportLive() {
            return transport != null && !transport.settled();
        }

        private boolean awaitTransportSettlement(long timeoutMs) {
            return transport == null || transport.awaitSettlement(timeoutMs);
        }

        private void quarantineTransport() {
            if (transport != null) transport.quarantine();
        }

        private void generationTerminalized() {
            if (transport != null) transport.generationTerminalized();
        }

        private void publishGenerationTerminal(Runnable terminalPublication) {
            Objects.requireNonNull(terminalPublication, "generation terminal publication");
            if (transport == null) {
                terminalPublication.run();
                return;
            }
            transport.publishGenerationTerminal(terminalPublication);
        }
    }

    /** Transport owner deliberately remains live after its generation terminalizes. */
    private static final class OwnedProviderTransportAttempt
            implements ProviderTransportAttempt {
        private final Attempt attempt;
        private final String operationId;
        private final ObservationMode observationMode;
        private final String processReceiptId;
        private ProcessBinding processBinding;
        private final CountDownLatch settledSignal = new CountDownLatch(1);
        private long transportSequence;
        private boolean sendEntered;
        private boolean headersObserved;
        private boolean bodyOpened;
        private boolean providerTerminalObserved;
        private boolean bodyCompleted;
        private boolean quarantineRequired;
        private boolean cancellationRequested;
        private boolean bodyCancellationInvoked;
        private boolean generationTerminal;
        private boolean settled;
        private Settlement settlement = Settlement.NONE;
        private Failure failure = Failure.NONE;
        private CancellationState cancellationState = CancellationState.NOT_REQUESTED;
        private CancellationReason cancellationReason = CancellationReason.NONE;
        private WireDisposition wireDisposition;
        private Thread sendThread;
        private AutoCloseable responseBody;

        private OwnedProviderTransportAttempt(
                Attempt attempt,
                ObservationMode observationMode) {
            this.attempt = Objects.requireNonNull(attempt, "provider response attempt");
            this.observationMode = Objects.requireNonNull(
                    observationMode, "transport observation mode");
            this.operationId = attempt.attemptId + "-transport-1";
            this.processReceiptId = attempt.dispatchEvidence.processReceipt()
                    .map(ProviderProcessLaunchReceipt::receiptId)
                    .orElse("");
            this.processBinding = processReceiptId.isBlank()
                    ? ProcessBinding.UNAVAILABLE
                    : ProcessBinding.MANAGED_QUALIFICATION_EXACT;
            this.wireDisposition = observationMode == ObservationMode.OBSERVED
                    ? WireDisposition.SEND_API_NOT_ENTERED
                    : WireDisposition.UNKNOWN;
        }

        private synchronized void handoffAdmitted() {
            emit(Transition.HANDOFF_ADMITTED);
        }

        private String operationId() {
            return operationId;
        }

        private String processReceiptId() {
            return processReceiptId;
        }

        @Override
        public synchronized void processBindingInvalidated() {
            if (!settled
                    && processBinding == ProcessBinding.MANAGED_QUALIFICATION_EXACT) {
                processBinding = ProcessBinding.MANAGED_QUALIFICATION_INVALIDATED;
                emit(Transition.PROCESS_BINDING_INVALIDATED);
            }
        }

        @Override
        public <T> T invokeSend(SendOperation<T> operation) throws Exception {
            Objects.requireNonNull(operation, "provider send operation");
            synchronized (this) {
                if (observationMode != ObservationMode.OBSERVED) {
                    throw new IllegalStateException(
                            "legacy transport lifecycle cannot claim send entry");
                }
                if (settled) {
                    if (settlement == Settlement.CANCELLED_BEFORE_SEND) {
                        throw new ProviderTransportAttempt.CancelledBeforeSend();
                    }
                    throw new IllegalStateException(
                            "settled provider transport cannot enter send again");
                }
                if (cancellationRequested) {
                    cancellationState = CancellationState.PRE_SEND_PREVENTED;
                    settle(Settlement.CANCELLED_BEFORE_SEND, Failure.NONE, false);
                    throw new ProviderTransportAttempt.CancelledBeforeSend();
                }
                if (sendEntered) {
                    throw new IllegalStateException("provider send operation entered twice");
                }
                sendEntered = true;
                wireDisposition = WireDisposition.UNKNOWN;
                sendThread = Thread.currentThread();
                emit(Transition.SEND_API_ENTERED);
            }

            try {
                return operation.invoke();
            } catch (Throwable sendFailure) {
                synchronized (this) {
                    if (!settled) {
                        Failure classified = classifyTransportFailure(
                                sendFailure, headersObserved);
                        if (cancellationRequested
                                && (classified == Failure.INTERRUPTION
                                    || classified == Failure.CANCELLATION
                                    || classified == Failure.BODY_IO)) {
                            cancellationState = CancellationState.SEND_ABORT_ACKNOWLEDGED;
                            emit(Transition.CANCELLATION_ACKNOWLEDGED);
                            settle(Settlement.CANCELLED_IN_FLIGHT, classified, true);
                        } else {
                            settle(headersObserved
                                            ? Settlement.FAILED_DURING_BODY
                                            : Settlement.FAILED_BEFORE_HEADERS,
                                    classified,
                                    true);
                        }
                    }
                }
                if (sendFailure instanceof Error error) throw error;
                if (sendFailure instanceof Exception exception) throw exception;
                throw new RuntimeException(sendFailure);
            } finally {
                synchronized (this) {
                    if (sendThread == Thread.currentThread()) sendThread = null;
                }
            }
        }

        @Override
        public synchronized void responseHeadersObserved() {
            if (settled) return;
            if (!sendEntered || headersObserved) {
                throw new IllegalStateException("invalid provider response-header transition");
            }
            headersObserved = true;
            emit(Transition.RESPONSE_HEADERS_OBSERVED);
        }

        @Override
        public void responseBodyOpened(AutoCloseable body) {
            boolean invokeCancellation = false;
            boolean rejectLateBody = false;
            AutoCloseable exactBody = Objects.requireNonNull(
                    body, "provider response body");
            synchronized (this) {
                if (settled) {
                    rejectLateBody = true;
                } else {
                    if (sendThread != null || !headersObserved || bodyOpened || bodyCompleted
                            || providerTerminalObserved) {
                        throw new IllegalStateException(
                                "invalid provider response-body open transition");
                    }
                    responseBody = exactBody;
                    bodyOpened = true;
                    emit(Transition.RESPONSE_BODY_OPENED);
                    if (cancellationRequested && !bodyCancellationInvoked) {
                        bodyCancellationInvoked = true;
                        invokeCancellation = true;
                    }
                }
            }
            if (rejectLateBody) {
                ProviderTransportAttempt.ResponseBodyRejectedAfterSettlement rejection =
                        new ProviderTransportAttempt.ResponseBodyRejectedAfterSettlement();
                try {
                    exactBody.close();
                } catch (Exception closeFailure) {
                    rejection.addSuppressed(closeFailure);
                }
                throw rejection;
            }
            if (invokeCancellation) invokeBodyCancellationHandle(responseBody);
        }

        @Override
        public synchronized void providerTerminalObserved() {
            if (settled) return;
            if (sendThread != null || !bodyOpened || providerTerminalObserved) {
                throw new IllegalStateException("invalid provider-terminal transition");
            }
            providerTerminalObserved = true;
            emit(Transition.PROVIDER_TERMINAL_OBSERVED);
        }

        @Override
        public synchronized void responseBodyCompleted() {
            if (settled) return;
            if (sendThread != null || !bodyOpened || bodyCompleted) {
                throw new IllegalStateException("invalid provider response-body completion");
            }
            bodyCompleted = true;
            emit(Transition.RESPONSE_BODY_COMPLETED);
            settle(providerTerminalObserved
                            ? Settlement.COMPLETED_PROVIDER_TERMINAL
                            : Settlement.COMPLETED_BODY_EOF,
                    Failure.NONE,
                    false);
        }

        @Override
        public synchronized void responseBodyFailed(Throwable bodyFailure) {
            if (settled) return;
            if (sendThread != null || !bodyOpened) {
                throw new IllegalStateException(
                        "provider response body cannot fail before it opens");
            }
            Failure classified = classifyTransportFailure(bodyFailure, true);
            if (cancellationRequested
                    && (classified == Failure.INTERRUPTION
                        || classified == Failure.CANCELLATION
                        || classified == Failure.BODY_IO)) {
                cancellationState = CancellationState.BODY_CLOSE_ACKNOWLEDGED;
                emit(Transition.CANCELLATION_ACKNOWLEDGED);
                settle(Settlement.CANCELLED_IN_FLIGHT, classified,
                        !providerTerminalObserved);
                return;
            }
            settle(Settlement.FAILED_DURING_BODY, classified,
                    !providerTerminalObserved);
        }

        @Override
        public synchronized void responseBodyClosed() {
            if (settled) return;
            if (sendThread != null || !bodyOpened) {
                throw new IllegalStateException(
                        "provider response body cannot close before it opens");
            }
            if (providerTerminalObserved) {
                if (cancellationRequested) {
                    cancellationState = CancellationState.BODY_CLOSE_ACKNOWLEDGED;
                    emit(Transition.CANCELLATION_ACKNOWLEDGED);
                }
                settle(Settlement.COMPLETED_PROVIDER_TERMINAL, Failure.NONE, false);
            } else if (cancellationRequested) {
                cancellationState = CancellationState.BODY_CLOSE_ACKNOWLEDGED;
                emit(Transition.CANCELLATION_ACKNOWLEDGED);
                settle(Settlement.CANCELLED_IN_FLIGHT, Failure.NONE, true);
            } else {
                settle(Settlement.CLOSED_BEFORE_PROVIDER_TERMINAL, Failure.NONE, true);
            }
        }

        private void requestCancellation(CancellationReason reason) {
            boolean invokeCancellation = false;
            synchronized (this) {
                if (settled || cancellationRequested) return;
                cancellationRequested = true;
                cancellationReason = reason == null ? CancellationReason.NONE : reason;
                cancellationState = CancellationState.REQUESTED;
                emit(Transition.CANCELLATION_REQUESTED);
                if (observationMode != ObservationMode.OBSERVED) {
                    cancellationState = CancellationState.UNACKNOWLEDGED;
                    settle(Settlement.QUARANTINE_REQUIRED, Failure.NONE, true);
                    return;
                }
                if (!sendEntered) {
                    cancellationState = CancellationState.PRE_SEND_PREVENTED;
                    settle(Settlement.CANCELLED_BEFORE_SEND, Failure.NONE, false);
                    return;
                }
                if (sendThread != null) sendThread.interrupt();
                if (responseBody != null && !bodyCancellationInvoked) {
                    bodyCancellationInvoked = true;
                    invokeCancellation = true;
                }
            }
            if (invokeCancellation) invokeBodyCancellationHandle(responseBody);
        }

        private void invokeBodyCancellationHandle(AutoCloseable cancellationHandle) {
            if (cancellationHandle == null) return;
            Thread.ofVirtual().name("talos-provider-transport-cancel").start(() -> {
                try {
                    cancellationHandle.close();
                } catch (Exception closeFailure) {
                    // A failed or merely returned local close does not prove
                    // transport settlement. The consumer callback must settle;
                    // otherwise the bounded owner freezes quarantine.
                }
            });
        }

        private synchronized void settleLegacyIfNeeded() {
            if (!settled && observationMode == ObservationMode.LEGACY_UNOBSERVED) {
                settle(Settlement.LIFECYCLE_UNOBSERVED, Failure.NONE, false);
            }
        }

        private synchronized void settleFromAttemptFailure() {
            if (settled) return;
            if (observationMode == ObservationMode.LEGACY_UNOBSERVED) {
                settle(Settlement.LIFECYCLE_UNOBSERVED, Failure.NONE, false);
            } else if (!sendEntered) {
                settle(Settlement.FAILED_BEFORE_HEADERS, Failure.RUNTIME, false);
            } else {
                settle(Settlement.QUARANTINE_REQUIRED, Failure.UNKNOWN, true);
            }
        }

        private synchronized void quarantine() {
            if (settled) return;
            if (cancellationRequested) cancellationState = CancellationState.UNACKNOWLEDGED;
            settle(Settlement.QUARANTINE_REQUIRED, Failure.UNKNOWN, true);
        }

        private synchronized boolean settled() {
            return settled;
        }

        private synchronized boolean provesProviderTerminal() {
            return observationMode == ObservationMode.OBSERVED
                    && processBinding
                            != ProcessBinding.MANAGED_QUALIFICATION_INVALIDATED
                    && settled
                    && settlement == Settlement.COMPLETED_PROVIDER_TERMINAL
                    && providerTerminalObserved
                    && (bodyCompleted || bodyOpened);
        }

        private synchronized boolean responseCompatible(Response response) {
            if (observationMode != ObservationMode.OBSERVED) {
                // Legacy engines remain readable, but cannot mint terminal or
                // tool eligibility because provesProviderTerminal() is false.
                return true;
            }
            if (settlement != Settlement.COMPLETED_PROVIDER_TERMINAL
                    && settlement != Settlement.COMPLETED_BODY_EOF
                    && settlement != Settlement.CLOSED_BEFORE_PROVIDER_TERMINAL) {
                return false;
            }
            return switch (response.completion()) {
                case PROVIDER_EOS, PROVIDER_LENGTH, UNRECOGNIZED_PROVIDER_STOP ->
                        provesProviderTerminal();
                case STREAM_WITHOUT_EOS ->
                        settlement != Settlement.COMPLETED_PROVIDER_TERMINAL
                                && !providerTerminalObserved;
                default -> true;
            };
        }

        private synchronized String settlementSummary() {
            return settlement.name();
        }

        private boolean awaitSettlement(long timeoutMs) {
            if (settledSignal.getCount() == 0) return true;
            boolean interrupted = false;
            try {
                long remaining = Math.max(0L, timeoutMs);
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(remaining);
                while (settledSignal.getCount() != 0) {
                    try {
                        return settledSignal.await(remaining, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException ignored) {
                        interrupted = true;
                        long nanos = deadline - System.nanoTime();
                        if (nanos <= 0) return settledSignal.getCount() == 0;
                        remaining = Math.max(1L, TimeUnit.NANOSECONDS.toMillis(nanos));
                    }
                }
                return true;
            } finally {
                if (interrupted) Thread.currentThread().interrupt();
            }
        }

        private synchronized void generationTerminalized() {
            generationTerminal = true;
        }

        private synchronized void publishGenerationTerminal(Runnable terminalPublication) {
            generationTerminal = true;
            terminalPublication.run();
        }

        private void settle(
                Settlement terminalSettlement,
                Failure terminalFailure,
                boolean quarantineRequired) {
            if (settled) return;
            settlement = Objects.requireNonNull(terminalSettlement, "transport settlement");
            failure = terminalFailure == null ? Failure.UNKNOWN : terminalFailure;
            this.quarantineRequired = quarantineRequired;
            settled = true;
            emit(Transition.SETTLED);
            attempt.generation.owner.transportSettled(this, quarantineRequired);
            settledSignal.countDown();
        }

        private void emit(Transition transition) {
            Scope owner = attempt.generation.owner;
            if (owner == null) return;
            owner.listener.transportLifecycle(new TransportLifecycleEvent(
                    TRANSPORT_LEDGER_VERSION,
                    ++transportSequence,
                    attempt.generation.generationId,
                    attempt.attemptId,
                    attempt.ordinal,
                    operationId,
                    transition,
                    observationMode,
                    settlement,
                    failure,
                    cancellationState,
                    cancellationReason,
                    wireDisposition,
                    quarantineRequired,
                    generationTerminal,
                    processBinding,
                    processReceiptId));
        }
    }

    public static Scope begin(Listener listener) {
        return begin(listener, "");
    }

    public static Scope begin(Listener listener, String scopeIdentity) {
        Scope previous = CURRENT.get();
        if (previous != null) previous.close();
        Scope scope = new Scope(listener, scopeIdentity);
        CURRENT.set(scope);
        LEGACY_ATTEMPT.remove();
        return scope;
    }

    /** Captures the current turn scope for explicit worker propagation. */
    public static Scope currentScope() {
        return CURRENT.get();
    }

    public static Generation openGeneration(Source source, Purpose purpose) {
        return openGeneration(source, purpose, ToolAuthorityMode.NONE);
    }

    public static Generation openGeneration(
            Source source,
            Purpose purpose,
            ToolAuthorityMode toolAuthorityMode
    ) {
        return openGeneration(
                source, purpose, ToolAuthorityContract.unbound(toolAuthorityMode));
    }

    public static Generation openGeneration(
            Source source,
            Purpose purpose,
            ToolAuthorityContract toolAuthorityContract
    ) {
        Objects.requireNonNull(source, "source");
        Purpose typedPurpose = purpose == null ? purposeFor(source) : purpose;
        ToolAuthorityContract typedToolAuthorityContract = requireToolAuthorityContract(
                source,
                typedPurpose,
                toolAuthorityContract == null
                        ? ToolAuthorityMode.NONE
                        : toolAuthorityContract.mode(),
                toolAuthorityContract);
        Scope scope = CURRENT.get();
        return scope == null
                ? Generation.detached(source, typedPurpose, typedToolAuthorityContract)
                : scope.openGeneration(source, typedPurpose, typedToolAuthorityContract);
    }

    /** Compatibility bridge for direct tests and legacy non-worker callers. */
    public static void attemptStarted(Source source) {
        Generation generation = openGeneration(source, purposeFor(source));
        Attempt attempt = generation.startAttempt(AttemptKind.PRIMARY, RetryReason.NONE);
        attempt.requestDispatched();
        LEGACY_ATTEMPT.set(attempt);
    }

    public static Receipt record(Response response) {
        Objects.requireNonNull(response, "response");
        Attempt attempt = LEGACY_ATTEMPT.get();
        if (attempt == null) return Receipt.detached(response.source(), response.completion());
        LEGACY_ATTEMPT.remove();
        Receipt receipt = attempt.respond(response);
        attempt.generation.complete(receipt);
        return receipt;
    }

    public static void attemptFailed(Source source, Failure failure) {
        Attempt attempt = LEGACY_ATTEMPT.get();
        LEGACY_ATTEMPT.remove();
        if (attempt == null) return;
        attempt.fail(failure);
        attempt.generation.fail(failure);
    }

    public static void clear() {
        Scope scope = CURRENT.get();
        if (scope != null) scope.close();
        CURRENT.remove();
        LEGACY_ATTEMPT.remove();
    }

    public static Purpose purposeFor(Source source) {
        return switch (Objects.requireNonNull(source, "source")) {
            case FOREGROUND_PROVIDER -> Purpose.TURN_RESPONSE;
            case BACKGROUND_PROVIDER -> Purpose.BACKGROUND_MAINTENANCE;
            case PLACEHOLDER_RUNTIME -> Purpose.PLACEHOLDER_RESPONSE;
            case SCRIPTED_HARNESS -> Purpose.HARNESS_FIXTURE;
        };
    }

    private static ToolAuthorityMode requireToolAuthorityMode(
            Source source,
            Purpose purpose,
            ToolAuthorityMode mode
    ) {
        Source typedSource = Objects.requireNonNull(source, "tool authority source");
        Purpose typedPurpose = Objects.requireNonNull(purpose, "tool authority purpose");
        ToolAuthorityMode typedMode = mode == null ? ToolAuthorityMode.NONE : mode;
        if (typedMode != ToolAuthorityMode.NONE
                && (typedSource != Source.FOREGROUND_PROVIDER
                    || typedPurpose != Purpose.TURN_RESPONSE)) {
            throw new IllegalArgumentException(
                    "provider tool authority is valid only for a foreground turn response");
        }
        return typedMode;
    }

    private static ToolAuthorityContract requireToolAuthorityContract(
            Source source,
            Purpose purpose,
            ToolAuthorityMode mode,
            ToolAuthorityContract contract
    ) {
        ToolAuthorityMode typedMode = requireToolAuthorityMode(source, purpose, mode);
        ToolAuthorityContract typed = contract == null
                ? ToolAuthorityContract.unbound(typedMode)
                : contract;
        if (typed.mode() != typedMode) {
            throw new IllegalArgumentException(
                    "provider tool authority contract mode differs from its generation mode");
        }
        if (typed.bound()
                && (source != Source.FOREGROUND_PROVIDER
                    || purpose != Purpose.TURN_RESPONSE)) {
            throw new IllegalArgumentException(
                    "bound provider tool authority is valid only for a foreground turn response");
        }
        return typed;
    }

    private static ProviderTransportAttempt.CancellationReason transportCancellationReason(
            Abandonment abandonment) {
        Abandonment typed = abandonment == null ? Abandonment.SCOPE_CLOSED : abandonment;
        return switch (typed) {
            case OPERATOR_CANCELLED ->
                    ProviderTransportAttempt.CancellationReason.OPERATOR_CANCELLED;
            case WALL_CLOCK_TIMEOUT ->
                    ProviderTransportAttempt.CancellationReason.WALL_CLOCK_TIMEOUT;
            case IDLE_TIMEOUT -> ProviderTransportAttempt.CancellationReason.IDLE_TIMEOUT;
            case REPETITION_LOOP -> ProviderTransportAttempt.CancellationReason.REPETITION_LOOP;
            case INTERRUPTION_CAUSE_UNPROVEN ->
                    ProviderTransportAttempt.CancellationReason.INTERRUPTION_CAUSE_UNPROVEN;
            case RUNTIME_STREAM_CLOSED ->
                    ProviderTransportAttempt.CancellationReason.RUNTIME_STREAM_CLOSED;
            case SCOPE_CLOSED -> ProviderTransportAttempt.CancellationReason.SCOPE_CLOSED;
        };
    }

    private static ProviderTransportAttempt.Failure classifyTransportFailure(
            Throwable throwable,
            boolean bodyPhase) {
        ControlFlowFailure.Kind control = ControlFlowFailure.classify(throwable);
        if (control == ControlFlowFailure.Kind.CANCELLATION) {
            return ProviderTransportAttempt.Failure.CANCELLATION;
        }
        if (control == ControlFlowFailure.Kind.INTERRUPTION) {
            return ProviderTransportAttempt.Failure.INTERRUPTION;
        }
        Throwable current = throwable;
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(
                new java.util.IdentityHashMap<>());
        for (int depth = 0; current != null && depth < 64 && seen.add(current); depth++) {
            if (current instanceof HttpTimeoutException) {
                return ProviderTransportAttempt.Failure.TIMEOUT;
            }
            if (current instanceof ConnectException) {
                return ProviderTransportAttempt.Failure.CONNECTION;
            }
            if (current instanceof CancellationException) {
                return ProviderTransportAttempt.Failure.CANCELLATION;
            }
            if (current instanceof InterruptedException) {
                return ProviderTransportAttempt.Failure.INTERRUPTION;
            }
            if (current instanceof IOException) {
                return bodyPhase
                        ? ProviderTransportAttempt.Failure.BODY_IO
                        : ProviderTransportAttempt.Failure.CONNECTION;
            }
            current = current.getCause();
        }
        if (throwable instanceof RuntimeException) {
            return ProviderTransportAttempt.Failure.RUNTIME;
        }
        return ProviderTransportAttempt.Failure.UNKNOWN;
    }

    private static String requireSafeIdentifier(String value, String label) {
        String exact = Objects.toString(value, "");
        if (!exact.equals(exact.strip())
                || !exact.matches("[A-Za-z0-9._-]{1,512}")) {
            throw new IllegalArgumentException(label + " is not a safe bounded identifier");
        }
        return exact;
    }

    private static String optionalSafeIdentifier(String value, String label) {
        String exact = Objects.toString(value, "");
        return exact.isBlank() ? "" : requireSafeIdentifier(exact, label);
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Objects.toString(value, "").getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** Stable, length-delimited hash for a provider argument object. */
    public static String canonicalArgumentsHash(Map<String, ?> arguments) {
        return sha256(canonicalValue(arguments == null ? Map.of() : arguments));
    }

    /** Stable hash for a provider-authored tool name; raw names never need persistence. */
    public static String toolNameHash(String toolName) {
        return sha256(Objects.toString(toolName, ""));
    }

    /** Stable hash for a provider-authored argument key; raw keys never need persistence. */
    public static String argumentNameHash(String argumentName) {
        return sha256(Objects.toString(argumentName, ""));
    }

    private static List<ToolProposalReceipt> proposalReceipts(
            List<ChatMessage.NativeToolCall> calls) {
        if (calls == null || calls.isEmpty()) return List.of();
        int captured = Math.min(calls.size(), MAX_TRACE_TOOL_PROPOSALS);
        List<ToolProposalReceipt> out = new ArrayList<>(captured);
        for (int index = 0; index < captured; index++) {
            ChatMessage.NativeToolCall call = calls.get(index);
            out.add(new ToolProposalReceipt(
                    index + 1,
                    toolNameHash(call == null ? "" : call.name()),
                    canonicalArgumentsHash(call == null ? Map.of() : call.arguments())));
        }
        return List.copyOf(out);
    }

    private static String canonicalValue(Object value) {
        if (value == null) return "n";
        if (value instanceof CharSequence chars) return framed('s', chars.toString());
        if (value instanceof Boolean flag) return flag ? "b1" : "b0";
        if (value instanceof Number number) return framed('d', canonicalNumber(number));
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new LinkedHashMap<>();
            map.forEach((key, nested) -> sorted.put(Objects.toString(key, ""), nested));
            List<Map.Entry<String, Object>> entries = sorted.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .toList();
            StringBuilder out = new StringBuilder("m").append(entries.size()).append(':');
            for (Map.Entry<String, Object> entry : entries) {
                out.append(framed('k', entry.getKey()));
                out.append(canonicalValue(entry.getValue()));
            }
            return out.toString();
        }
        if (value instanceof Iterable<?> iterable) {
            List<Object> values = new ArrayList<>();
            iterable.forEach(values::add);
            StringBuilder out = new StringBuilder("l").append(values.size()).append(':');
            values.forEach(nested -> out.append(canonicalValue(nested)));
            return out.toString();
        }
        if (value.getClass().isArray()) {
            int length = Array.getLength(value);
            StringBuilder out = new StringBuilder("a").append(length).append(':');
            for (int index = 0; index < length; index++) {
                out.append(canonicalValue(Array.get(value, index)));
            }
            return out.toString();
        }
        return framed('u', value.getClass().getName());
    }

    private static String canonicalNumber(Number number) {
        try {
            return new BigDecimal(number.toString()).stripTrailingZeros().toPlainString();
        } catch (NumberFormatException nonFinite) {
            return number.getClass().getName() + ':' + number;
        }
    }

    private static String framed(char type, String value) {
        String safeValue = Objects.toString(value, "");
        return new StringBuilder().append(type).append(safeValue.length())
                .append(':').append(safeValue).toString();
    }

    private static String safeHash(String hash) {
        String value = safe(hash);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException("proposal hash is not a lowercase SHA-256 reference");
        }
        return value;
    }
}
