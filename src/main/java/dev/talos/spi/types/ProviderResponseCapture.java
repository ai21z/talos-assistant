package dev.talos.spi.types;

import java.util.List;
import java.util.ArrayList;
import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

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
            ToolAuthorityContract toolAuthorityContract) {
        public GenerationEvent {
            toolAuthorityMode = requireToolAuthorityMode(
                    source, purpose, toolAuthorityMode);
            toolAuthorityContract = requireToolAuthorityContract(
                    source, purpose, toolAuthorityMode, toolAuthorityContract);
        }

        public GenerationEvent(
                long ledgerSequence,
                String generationId,
                Source source,
                Purpose purpose,
                ToolAuthorityMode toolAuthorityMode
        ) {
            this(ledgerSequence, generationId, source, purpose, toolAuthorityMode,
                    ToolAuthorityContract.unbound(toolAuthorityMode));
        }

        public GenerationEvent(
                long ledgerSequence,
                String generationId,
                Source source,
                Purpose purpose
        ) {
            this(ledgerSequence, generationId, source, purpose, ToolAuthorityMode.NONE,
                    ToolAuthorityContract.none());
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
            ToolAuthorityContract toolAuthorityContract) {
        public AttemptEvent {
            toolAuthorityMode = requireToolAuthorityMode(
                    source, purpose, toolAuthorityMode);
            toolAuthorityContract = requireToolAuthorityContract(
                    source, purpose, toolAuthorityMode, toolAuthorityContract);
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
                    toolAuthorityMode, ToolAuthorityContract.unbound(toolAuthorityMode));
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
                    ToolAuthorityMode.NONE, ToolAuthorityContract.none());
        }
    }

    public record RequestDispatch(
            AttemptEvent attempt,
            ProviderTransportHandoff.DispatchEvidence evidence) {
        public RequestDispatch {
            attempt = Objects.requireNonNull(attempt, "dispatch attempt");
            evidence = evidence == null
                    ? ProviderTransportHandoff.DispatchEvidence.unbound()
                    : evidence;
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

    public record Response(
            String assistantText,
            int nativeToolCallCount,
            List<ChatMessage.NativeToolCall> nativeToolCalls,
            Source source,
            Completion completion,
            boolean normalized,
            boolean truncated,
            boolean textualToolCallPresent) {
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
        private final String assistantHash;
        private final List<ToolProposalReceipt> toolProposals;
        private final Generation ownerGeneration;
        private final Attempt ownerAttempt;
        private volatile boolean selected;

        public Receipt(
                String generationId,
                Source source,
                Completion completion,
                boolean providerTerminalEligible) {
            this(generationId, "", 0, source, purposeFor(source), completion,
                    providerTerminalEligible, false, ToolAuthorityMode.NONE,
                    ToolAuthorityContract.none(), ToolAuthorityBinding.NOT_APPLICABLE,
                    "", List.of(), null, null);
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
                    ToolAuthorityBinding.NOT_APPLICABLE, "",
                    List.of(), null, null);
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
                    ToolAuthorityBinding.NOT_APPLICABLE, assistantHash,
                    List.of(), null, null);
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
                    ToolAuthorityBinding.NOT_APPLICABLE, assistantHash,
                    List.of(), null, null);
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
                String assistantHash,
                List<ToolProposalReceipt> toolProposals,
                Generation ownerGeneration,
                Attempt ownerAttempt) {
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
        public String assistantHash() { return assistantHash; }

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
                    "", List.of(), null, null);
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
            synchronized (this) {
                for (Generation generation : List.copyOf(openGenerations)) {
                    generation.abandon(Abandonment.SCOPE_CLOSED);
                }
                closed = true;
            }
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
            synchronized (owner) {
                if (terminal) return false;
                Abandonment typed = abandonment == null ? Abandonment.SCOPE_CLOSED : abandonment;
                if (activeAttempt != null && !activeAttempt.terminal) {
                    activeAttempt.abandonLocked(typed);
                }
                selectableReceipt = null;
                terminal = true;
                owner.openGenerations.remove(this);
                owner.listener.generationAbandoned(new GenerationAbandonment(
                        event(owner.nextSequence()), typed));
                return true;
            }
        }

        public boolean terminal() {
            if (owner == null) return true;
            synchronized (owner) {
                return terminal;
            }
        }

        private GenerationEvent event(long sequence) {
            return new GenerationEvent(
                    sequence, generationId, source, purpose, toolAuthorityMode,
                    toolAuthorityContract);
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
        private boolean transportUnobserved;
        private boolean terminal;
        private Failure failure;

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

        public void requestDispatched(ProviderTransportHandoff.DispatchEvidence evidence) {
            if (!tryRequestDispatched(evidence)) {
                throw new ProviderTransportHandoff.DispatchRejected();
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
                dispatchEvidence = evidence == null
                        ? ProviderTransportHandoff.DispatchEvidence.unbound()
                        : evidence;
                requestDispatched = true;
                owner.listener.requestDispatched(new RequestDispatch(
                        event(owner.nextSequence()), dispatchEvidence));
                return true;
            }
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
                Receipt receipt = new Receipt(
                        generation.generationId,
                        attemptId,
                        ordinal,
                        response.source(),
                        generation.purpose,
                        response.completion(),
                        requestDispatched && response.completedNormally(),
                        requestDispatched && response.foreground()
                                && responseMatchesToolAuthorityMode,
                        generation.toolAuthorityMode,
                        generation.toolAuthorityContract,
                        toolAuthorityBinding,
                        sha256(response.assistantText()),
                        proposalReceipts(response.nativeToolCalls()),
                        generation,
                        this);
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
            terminal = true;
            this.failure = failure;
            owner.listener.attemptFailed(new AttemptFailure(
                    event(owner.nextSequence()), failure));
        }

        public void abandon(Abandonment abandonment) {
            Scope owner = generation.owner;
            if (owner == null) return;
            synchronized (owner) {
                abandonLocked(abandonment == null ? Abandonment.SCOPE_CLOSED : abandonment);
            }
        }

        private void abandonLocked(Abandonment abandonment) {
            Scope owner = generation.owner;
            if (terminal || generation.terminal || owner == null) return;
            terminal = true;
            owner.listener.attemptAbandoned(new AttemptAbandonment(
                    event(owner.nextSequence()), abandonment));
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
                    generation.toolAuthorityContract);
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
