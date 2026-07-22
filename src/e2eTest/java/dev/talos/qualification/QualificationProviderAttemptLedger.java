package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolChoiceMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Strict reader for the trace-v3/v4 physical provider-attempt ledger. */
final class QualificationProviderAttemptLedger {
    static final int PRE_AUTHORITY_TRACE_SCHEMA_VERSION = 3;
    static final int TRACE_SCHEMA_VERSION = 4;
    static final int LEDGER_VERSION = 1;

    private static final Set<String> GENERATION_KEYS = Set.of(
            "providerLedgerVersion", "ledgerSequence", "generationId", "source", "purpose");
    private static final Set<String> ATTEMPT_KEYS = union(GENERATION_KEYS, Set.of(
            "attemptId", "attemptOrdinal", "attemptKind", "retryReason", "requestDispatched"));
    private static final Set<String> DISPATCH_KEYS = union(ATTEMPT_KEYS, Set.of(
            "providerBodyHash", "dispatchedNativeTools"));
    private static final Set<String> AUTHORITY_SURFACE_KEYS = Set.of(
            "schemaVersion", "bound", "toolChoiceMode", "namedToolNameHash",
            "selectedToolNameHashes", "authorizedToolNameHashes");
    private static final Set<String> RESPONSE_KEYS = union(ATTEMPT_KEYS, Set.of(
            "assistantHash", "assistantChars", "nativeToolCallCount",
            "capturedNativeToolProposalCount", "nativeToolProposalsTruncated",
            "textualToolCallPresent", "completion", "providerBacked", "foreground",
            "completedNormally", "normalized", "truncated"));
    private static final Set<String> TRANSPORT_BOUND_RESPONSE_KEYS =
            union(RESPONSE_KEYS, Set.of("transportOperationId"));
    private static final Set<String> ATTEMPT_FAILURE_KEYS = union(ATTEMPT_KEYS, Set.of("failure"));
    private static final Set<String> ATTEMPT_ABANDONMENT_KEYS =
            union(ATTEMPT_KEYS, Set.of("abandonment"));
    private static final Set<String> GENERATION_FAILURE_KEYS = union(GENERATION_KEYS, Set.of("failure"));
    private static final Set<String> GENERATION_ABANDONMENT_KEYS =
            union(GENERATION_KEYS, Set.of("abandonment"));
    private static final Set<String> GENERATION_COMPLETION_KEYS = union(GENERATION_KEYS, Set.of(
            "selectedAttemptId", "selectedAttemptOrdinal", "completion", "assistantHash",
            "providerTerminalEligible", "providerToolCallEligible"));
    private static final Set<String> TRANSPORT_BOUND_GENERATION_COMPLETION_KEYS =
            union(GENERATION_COMPLETION_KEYS, Set.of("transportOperationId"));
    private static final Set<String> TOOL_PROPOSAL_KEYS = union(ATTEMPT_KEYS, Set.of(
            "proposalOrdinal", "providerCallIdHash", "toolNameClassification",
            "toolNameHash", "toolNameChars", "argumentNameHashes",
            "argumentCount", "argumentNameHashesTruncated", "argumentsHash"));
    private static final Set<String> TOOL_NAME_CLASSIFICATIONS = Set.of(
            "BLANK", "CANONICAL", "ACCEPTED_ALIAS", "REJECTED_UNKNOWN_NAMESPACE", "UNKNOWN");
    private static final Set<String> PHYSICAL_STATE_EVENTS = Set.of(
            "GENERATION_STARTED",
            "GENERATION_ATTEMPT_STARTED",
            "GENERATION_REQUEST_DISPATCHED",
            "GENERATION_TRANSPORT_UNOBSERVED",
            "GENERATION_RESPONSE_CAPTURED",
            "GENERATION_ATTEMPT_FAILED",
            "GENERATION_ATTEMPT_ABANDONED",
            "GENERATION_FAILED",
            "GENERATION_ABANDONED",
            "GENERATION_COMPLETED");

    private QualificationProviderAttemptLedger() {}

    record Response(
            int eventIndex,
            long ledgerSequence,
            String generationId,
            String attemptId,
            int attemptOrdinal,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Purpose purpose,
            ProviderResponseCapture.AttemptKind attemptKind,
            ProviderResponseCapture.RetryReason retryReason,
            String assistantHash,
            int assistantChars,
            int nativeToolCallCount,
            int capturedNativeToolProposalCount,
            boolean nativeToolProposalsTruncated,
            boolean textualToolCallPresent,
            ProviderResponseCapture.Completion completion,
            boolean providerBacked,
            boolean foreground,
            boolean completedNormally,
            boolean normalized,
            boolean truncated,
            String transportOperationId) {
        Response {
            transportOperationId = transportOperationId == null
                    ? ""
                    : transportOperationId;
        }

        Response(
                int eventIndex,
                long ledgerSequence,
                String generationId,
                String attemptId,
                int attemptOrdinal,
                ProviderResponseCapture.Source source,
                ProviderResponseCapture.Purpose purpose,
                ProviderResponseCapture.AttemptKind attemptKind,
                ProviderResponseCapture.RetryReason retryReason,
                String assistantHash,
                int assistantChars,
                int nativeToolCallCount,
                int capturedNativeToolProposalCount,
                boolean nativeToolProposalsTruncated,
                boolean textualToolCallPresent,
                ProviderResponseCapture.Completion completion,
                boolean providerBacked,
                boolean foreground,
                boolean completedNormally,
                boolean normalized,
                boolean truncated) {
            this(eventIndex, ledgerSequence, generationId, attemptId, attemptOrdinal,
                    source, purpose, attemptKind, retryReason, assistantHash,
                    assistantChars, nativeToolCallCount, capturedNativeToolProposalCount,
                    nativeToolProposalsTruncated, textualToolCallPresent, completion,
                    providerBacked, foreground, completedNormally, normalized, truncated, "");
        }

        boolean toolCallPresent() {
            return nativeToolCallCount > 0 || textualToolCallPresent;
        }

        boolean outputCapHit() {
            return completion == ProviderResponseCapture.Completion.PROVIDER_LENGTH;
        }
    }

    record Proposal(
            int proposalOrdinal,
            String providerCallIdHash,
            String toolNameHash,
            String argumentsHash) {
        Proposal(int proposalOrdinal, String toolNameHash, String argumentsHash) {
            this(proposalOrdinal, "", toolNameHash, argumentsHash);
        }
    }

    record Attempt(
            String attemptId,
            int attemptOrdinal,
            ProviderResponseCapture.AttemptKind attemptKind,
            ProviderResponseCapture.RetryReason retryReason,
            boolean requestDispatched,
            boolean transportUnobserved,
            String providerBodyHash,
            List<String> dispatchedNativeTools,
            AuthoritySurface authoritySurface,
            Response response,
            List<Proposal> proposals,
            ProviderResponseCapture.Failure failure,
            ProviderResponseCapture.Abandonment abandonment,
            int startEventIndex,
            int dispatchEventIndex,
            int closeEventIndex) {
        Attempt {
            proposals = List.copyOf(proposals);
            providerBodyHash = providerBodyHash == null ? "" : providerBodyHash;
            dispatchedNativeTools = dispatchedNativeTools == null
                    ? List.of()
                    : List.copyOf(dispatchedNativeTools);
            authoritySurface = authoritySurface == null
                    ? AuthoritySurface.unbound()
                    : authoritySurface;
        }

        Attempt(
                String attemptId,
                int attemptOrdinal,
                ProviderResponseCapture.AttemptKind attemptKind,
                ProviderResponseCapture.RetryReason retryReason,
                boolean requestDispatched,
                boolean transportUnobserved,
                Response response,
                List<Proposal> proposals,
                ProviderResponseCapture.Failure failure,
                ProviderResponseCapture.Abandonment abandonment,
                int startEventIndex,
                int closeEventIndex) {
            this(attemptId, attemptOrdinal, attemptKind, retryReason,
                    requestDispatched, transportUnobserved, "", List.of(),
                    AuthoritySurface.unbound(),
                    response, proposals, failure, abandonment,
                    startEventIndex, -1, closeEventIndex);
        }

        Attempt(
                String attemptId,
                int attemptOrdinal,
                ProviderResponseCapture.AttemptKind attemptKind,
                ProviderResponseCapture.RetryReason retryReason,
                boolean requestDispatched,
                boolean transportUnobserved,
                String providerBodyHash,
                Set<String> dispatchedNativeTools,
                Response response,
                List<Proposal> proposals,
                ProviderResponseCapture.Failure failure,
                ProviderResponseCapture.Abandonment abandonment,
                int startEventIndex,
                int closeEventIndex
        ) {
            this(attemptId, attemptOrdinal, attemptKind, retryReason,
                    requestDispatched, transportUnobserved, providerBodyHash,
                    dispatchedNativeTools == null
                            ? List.of()
                            : List.copyOf(dispatchedNativeTools),
                     AuthoritySurface.fromNativeTools(dispatchedNativeTools),
                     response, proposals, failure, abandonment,
                    startEventIndex, -1, closeEventIndex);
        }
    }

    record AuthoritySurface(
            boolean bound,
            ToolChoiceMode toolChoiceMode,
            String namedToolNameHash,
            List<String> selectedToolNameHashes,
            List<String> authorizedToolNameHashes
    ) {
        AuthoritySurface {
            toolChoiceMode = toolChoiceMode == null ? ToolChoiceMode.NONE : toolChoiceMode;
            namedToolNameHash = namedToolNameHash == null ? "" : namedToolNameHash;
            selectedToolNameHashes = selectedToolNameHashes == null
                    ? List.of()
                    : List.copyOf(selectedToolNameHashes);
            authorizedToolNameHashes = authorizedToolNameHashes == null
                    ? List.of()
                    : List.copyOf(authorizedToolNameHashes);
        }

        static AuthoritySurface unbound() {
            return new AuthoritySurface(
                    false,
                    ToolChoiceMode.NONE,
                    ProviderResponseCapture.toolNameHash(""),
                    List.of(),
                    List.of());
        }

        static AuthoritySurface fromNativeTools(Set<String> nativeTools) {
            List<String> names = nativeTools == null
                    ? List.of()
                    : List.copyOf(nativeTools);
            if (names.isEmpty()) return unbound();
            List<String> hashes = names.stream()
                    .map(ProviderResponseCapture::toolNameHash)
                    .toList();
            return new AuthoritySurface(
                    true,
                    ToolChoiceMode.AUTO,
                    ProviderResponseCapture.toolNameHash(""),
                    hashes,
                    hashes);
        }

        boolean authorizes(String toolNameHash) {
            return bound && authorizedToolNameHashes.contains(toolNameHash);
        }
    }

    record Generation(
            String generationId,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Purpose purpose,
            ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode,
            List<Attempt> attempts,
            String terminalEvent,
            String selectedAttemptId,
            int selectedAttemptOrdinal,
            boolean providerTerminalEligible,
            boolean providerToolCallEligible,
            ProviderResponseCapture.Failure failure,
            ProviderResponseCapture.Abandonment abandonment,
            int startEventIndex,
            int terminalEventIndex) {
        Generation {
            attempts = List.copyOf(attempts);
        }

        Generation(
                String generationId,
                ProviderResponseCapture.Source source,
                ProviderResponseCapture.Purpose purpose,
                List<Attempt> attempts,
                String terminalEvent,
                String selectedAttemptId,
                int selectedAttemptOrdinal,
                boolean providerTerminalEligible,
                boolean providerToolCallEligible,
                ProviderResponseCapture.Failure failure,
                ProviderResponseCapture.Abandonment abandonment,
                int startEventIndex,
                int terminalEventIndex
        ) {
            this(generationId, source, purpose,
                    ProviderResponseCapture.ToolAuthorityMode.NONE,
                    attempts, terminalEvent, selectedAttemptId, selectedAttemptOrdinal,
                    providerTerminalEligible, providerToolCallEligible,
                    failure, abandonment, startEventIndex, terminalEventIndex);
        }

        Response selectedResponse() {
            if (!"GENERATION_COMPLETED".equals(terminalEvent)) return null;
            return attempts.stream()
                    .filter(attempt -> attempt.attemptId().equals(selectedAttemptId)
                            && attempt.attemptOrdinal() == selectedAttemptOrdinal)
                    .map(Attempt::response)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        Attempt selectedAttempt() {
            if (!"GENERATION_COMPLETED".equals(terminalEvent)) return null;
            return attempts.stream()
                    .filter(attempt -> attempt.attemptId().equals(selectedAttemptId)
                            && attempt.attemptOrdinal() == selectedAttemptOrdinal)
                    .findFirst()
                    .orElse(null);
        }

        Proposal selectedProposal(String attemptId, int ordinal) {
            return attempts.stream()
                    .filter(attempt -> attempt.attemptId().equals(attemptId))
                    .flatMap(attempt -> attempt.proposals().stream())
                    .filter(proposal -> proposal.proposalOrdinal() == ordinal)
                    .findFirst()
                    .orElse(null);
        }

        boolean foregroundProvider() {
            return source == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    && purpose == ProviderResponseCapture.Purpose.TURN_RESPONSE;
        }
    }

    record Ledger(
            List<Generation> generations,
            QualificationProviderTransportLedger.Ledger transports) {
        Ledger {
            generations = List.copyOf(generations);
            transports = transports == null
                    ? QualificationProviderTransportLedger.Ledger.empty()
                    : transports;
            requireSerializedNonBackgroundGenerations(generations);
        }

        Ledger(List<Generation> generations) {
            this(generations, QualificationProviderTransportLedger.Ledger.empty());
        }

        static Ledger empty() {
            return new Ledger(List.of(), QualificationProviderTransportLedger.Ledger.empty());
        }

        boolean present() {
            return !generations.isEmpty();
        }

        boolean transportLifecycleComplete() {
            return transports.completeForDispatchedAttempts(generations);
        }

        boolean dispatchedAttemptPresent() {
            return generations.stream().flatMap(generation -> generation.attempts().stream())
                    .anyMatch(Attempt::requestDispatched);
        }

        boolean transportQuarantinePresent() {
            return transports.quarantinePresent();
        }

        boolean transportUnobservedPresent() {
            return transports.unobservedPresent()
                    || generations.stream()
                            .flatMap(generation -> generation.attempts().stream())
                            .anyMatch(Attempt::transportUnobserved);
        }

        boolean activeProcessBindingComplete() {
            return transports.activeProcessBindingComplete();
        }

        Response selectedResponse(String generationId, String attemptId) {
            Generation generation = selectedGeneration(generationId, attemptId);
            return generation == null ? null : generation.selectedResponse();
        }

        Generation selectedGeneration(String generationId, String attemptId) {
            return generations.stream()
                    .filter(generation -> generation.generationId().equals(generationId)
                            && generation.selectedAttemptId().equals(attemptId))
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }

        Proposal selectedProposal(String generationId, String attemptId, int ordinal) {
            Generation generation = selectedGeneration(generationId, attemptId);
            return generation == null ? null : generation.selectedProposal(attemptId, ordinal);
        }

        int toolEligibleGenerations() {
            return (int) generations.stream()
                    .filter(generation -> "GENERATION_COMPLETED".equals(
                            generation.terminalEvent()))
                    .filter(Generation::providerToolCallEligible)
                    .filter(generation -> generation.selectedResponse() != null
                            && generation.selectedResponse().completion()
                                == ProviderResponseCapture.Completion.PROVIDER_EOS)
                    .count();
        }

        boolean outputCapHit() {
            return generations.stream().flatMap(generation -> generation.attempts().stream())
                    .map(Attempt::response).filter(Objects::nonNull)
                    .anyMatch(Response::outputCapHit);
        }

        boolean causalOutputCapHit() {
            Generation generation = latestForegroundGeneration();
            Response response = generation == null ? null : generation.selectedResponse();
            return response != null && response.outputCapHit();
        }

        boolean toolProposalTruncationPresent() {
            return generations.stream()
                    .flatMap(generation -> generation.attempts().stream())
                    .map(Attempt::response)
                    .filter(Objects::nonNull)
                    .anyMatch(Response::nativeToolProposalsTruncated);
        }

        boolean timedOut() {
            return generations.stream().anyMatch(generation ->
                    isTimeout(generation.abandonment())
                            || generation.attempts().stream()
                                    .anyMatch(attempt -> isTimeout(attempt.abandonment())));
        }

        boolean causalTimedOut() {
            Generation generation = latestForegroundGeneration();
            return generation != null && isTimeout(generation.abandonment());
        }

        boolean terminalFailurePresent() {
            return generations.stream()
                    .anyMatch(generation -> "GENERATION_FAILED".equals(generation.terminalEvent()));
        }

        boolean providerFailurePresent() {
            return generations.stream().anyMatch(generation ->
                    "GENERATION_FAILED".equals(generation.terminalEvent())
                            && generation.foregroundProvider()
                            && generation.failure() != null
                            && generation.failure().name().startsWith("PROVIDER_"));
        }

        boolean causalProviderFailurePresent() {
            Generation generation = latestForegroundGeneration();
            return generation != null
                    && "GENERATION_FAILED".equals(generation.terminalEvent())
                    && generation.failure() != null
                    && generation.failure().name().startsWith("PROVIDER_");
        }

        boolean causalRuntimeFailurePresent() {
            Generation generation = latestForegroundGeneration();
            if (generation == null) return false;
            if ("GENERATION_FAILED".equals(generation.terminalEvent())) {
                return generation.failure()
                                == ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH
                        || generation.failure()
                                == ProviderResponseCapture.Failure.RUNTIME_EXCEPTION;
            }
            return "GENERATION_ABANDONED".equals(generation.terminalEvent())
                    && isRuntimePolicyAbandonment(generation.abandonment());
        }

        boolean hardwareResourceExhaustionPresent() {
            return generations.stream().anyMatch(generation ->
                    "GENERATION_FAILED".equals(generation.terminalEvent())
                            && generation.foregroundProvider()
                            && generation.failure()
                                == ProviderResponseCapture.Failure.PROVIDER_RESOURCE_EXHAUSTED);
        }

        boolean causalHardwareResourceExhaustionPresent() {
            Generation generation = latestForegroundGeneration();
            return generation != null
                    && "GENERATION_FAILED".equals(generation.terminalEvent())
                    && generation.failure()
                            == ProviderResponseCapture.Failure.PROVIDER_RESOURCE_EXHAUSTED;
        }

        boolean recoveredAttemptPresent() {
            return generations.stream().anyMatch(generation ->
                    "GENERATION_COMPLETED".equals(generation.terminalEvent())
                            && generation.attempts().stream().anyMatch(attempt ->
                                    !attempt.attemptId().equals(generation.selectedAttemptId())));
        }

        boolean nonModelContributionPresent() {
            List<Generation> foreground = generations.stream()
                    .filter(Generation::foregroundProvider)
                    .toList();
            // Two separately completed foreground generations imply a
            // deterministic Talos retry/selection decision even when both
            // provider attempts are individually clean. Without a typed
            // selection receipt, the final answer cannot be comparative
            // model-only evidence.
            if (foreground.size() > 1) return true;
            return foreground.stream().anyMatch(generation ->
                    !"GENERATION_COMPLETED".equals(generation.terminalEvent())
                            || generation.attempts().size() > 1
                            || generation.attempts().stream().anyMatch(attempt ->
                                    attempt.failure() != null
                                            || attempt.abandonment() != null
                                            || (attempt.response() != null
                                                && attempt.response().completion()
                                                    != ProviderResponseCapture.Completion.PROVIDER_EOS)));
        }

        int latestNonBackgroundEventIndexBefore(int exclusiveIndex) {
            return generations.stream()
                    .filter(generation -> generation.source()
                            != ProviderResponseCapture.Source.BACKGROUND_PROVIDER)
                    .flatMapToInt(generation -> java.util.stream.IntStream.of(
                            generation.startEventIndex(), generation.terminalEventIndex()))
                    .filter(index -> index >= 0 && index < exclusiveIndex)
                    .max().orElse(-1);
        }

        Generation latestTerminalEligibleForegroundDecision() {
            Generation generation = latestForegroundGeneration();
            if (generation == null
                    || !"GENERATION_COMPLETED".equals(generation.terminalEvent())
                    || !generation.providerTerminalEligible()
                    || generation.selectedResponse() == null) {
                return null;
            }
            return generation;
        }

        private Generation latestForegroundGeneration() {
            return generations.stream()
                    .filter(Generation::foregroundProvider)
                    .max(java.util.Comparator.comparingInt(Generation::terminalEventIndex))
                    .orElse(null);
        }

        private static void requireSerializedNonBackgroundGenerations(
                List<Generation> generations
        ) {
            List<Generation> ordered = generations.stream()
                    .filter(generation -> generation.source()
                            != ProviderResponseCapture.Source.BACKGROUND_PROVIDER)
                    .sorted(java.util.Comparator.comparingInt(
                            Generation::startEventIndex))
                    .toList();
            int latestTerminalIndex = -1;
            String latestGenerationId = "";
            for (Generation generation : ordered) {
                if (latestTerminalIndex >= 0
                        && generation.startEventIndex() <= latestTerminalIndex) {
                    throw new IllegalArgumentException(
                            "non-background generations overlap: "
                                    + latestGenerationId + " and "
                                    + generation.generationId());
                }
                if (generation.terminalEventIndex() > latestTerminalIndex) {
                    latestTerminalIndex = generation.terminalEventIndex();
                    latestGenerationId = generation.generationId();
                }
            }
        }

        private static boolean isTimeout(ProviderResponseCapture.Abandonment abandonment) {
            return abandonment == ProviderResponseCapture.Abandonment.WALL_CLOCK_TIMEOUT
                    || abandonment == ProviderResponseCapture.Abandonment.IDLE_TIMEOUT;
        }

        private static boolean isRuntimePolicyAbandonment(
                ProviderResponseCapture.Abandonment abandonment) {
            // REPETITION_LOOP is raised only by Talos's deterministic budget
            // policy. RUNTIME_STREAM_CLOSED can also represent a provider-
            // transport abort, and SCOPE_CLOSED is generic cleanup; neither
            // mechanically proves a runtime-policy first cause.
            return abandonment == ProviderResponseCapture.Abandonment.REPETITION_LOOP;
        }
    }

    static Ledger parse(JsonNode traceRoot) {
        if (traceRoot == null || !traceRoot.isObject()) {
            throw new IllegalArgumentException("trace must be an object");
        }
        int schemaVersion = requiredInt(traceRoot, "schemaVersion");
        if (schemaVersion == 2) return Ledger.empty();
        if (schemaVersion != PRE_AUTHORITY_TRACE_SCHEMA_VERSION
                && schemaVersion != TRACE_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "physical attempt ledger requires trace schema v3 or v4");
        }
        JsonNode events = traceRoot.path("events");
        if (!events.isArray()) {
            throw new IllegalArgumentException("trace events must be an array");
        }

        Map<String, MutableGeneration> generations = new LinkedHashMap<>();
        Set<String> attemptIds = new HashSet<>();
        long expectedSequence = 1;
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            String type = requiredText(event, "type");
            if ("MODEL_RESPONSE_RECEIVED".equals(type)
                    || "PROVIDER_GENERATION_RECEIVED".equals(type)) {
                throw new IllegalArgumentException(
                        "trace schema v3/v4 contains obsolete response provenance event " + type);
            }
            if ("PROVIDER_TOOL_PROPOSAL_CAPTURED".equals(type)) {
                parseProposal(event.path("data"), generations, index, schemaVersion);
                continue;
            }
            if (!PHYSICAL_STATE_EVENTS.contains(type)) continue;
            JsonNode data = requiredObject(event.path("data"), type + " data");
            if (schemaVersion == PRE_AUTHORITY_TRACE_SCHEMA_VERSION
                    && "GENERATION_REQUEST_DISPATCHED".equals(type)
                    && fields(data).equals(ATTEMPT_KEYS)) {
                // Early uncommitted schema-v3 fixtures have no attempt-bound
                // body receipt. They remain readable but cannot prove a model
                // tool opportunity.
                exactKeys(data, ATTEMPT_KEYS, type);
            } else if ("GENERATION_RESPONSE_CAPTURED".equals(type)) {
                exactKeysOneOf(
                        data,
                        keys(RESPONSE_KEYS, schemaVersion),
                        keys(TRANSPORT_BOUND_RESPONSE_KEYS, schemaVersion),
                        type);
            } else if ("GENERATION_COMPLETED".equals(type)) {
                exactKeysOneOf(
                        data,
                        keys(GENERATION_COMPLETION_KEYS, schemaVersion),
                        keys(TRANSPORT_BOUND_GENERATION_COMPLETION_KEYS, schemaVersion),
                        type);
            } else {
                exactKeys(data, keys(type, schemaVersion), type);
            }
            requireLedgerHeader(data, expectedSequence++);
            switch (type) {
                case "GENERATION_STARTED" -> generationStarted(
                        data, generations, index);
                case "GENERATION_ATTEMPT_STARTED" -> attemptStarted(
                        data, generations, attemptIds, index);
                case "GENERATION_REQUEST_DISPATCHED" -> requestDispatched(
                        data, generations, schemaVersion, index);
                case "GENERATION_TRANSPORT_UNOBSERVED" -> transportUnobserved(
                        data, generations);
                case "GENERATION_RESPONSE_CAPTURED" -> responseCaptured(
                        data, generations, index);
                case "GENERATION_ATTEMPT_FAILED" -> attemptFailed(
                        data, generations, index);
                case "GENERATION_ATTEMPT_ABANDONED" -> attemptAbandoned(
                        data, generations, index);
                case "GENERATION_FAILED" -> generationFailed(
                        data, generations, index);
                case "GENERATION_ABANDONED" -> generationAbandoned(
                        data, generations, index);
                case "GENERATION_COMPLETED" -> generationCompleted(
                        data, generations, index);
                default -> throw new IllegalStateException("unreachable physical event " + type);
            }
        }

        List<Generation> frozen = new ArrayList<>();
        for (MutableGeneration generation : generations.values()) {
            if (generation.terminalEvent.isBlank()) {
                throw new IllegalArgumentException(
                        "physical generation is not closed: " + generation.generationId);
            }
            List<Attempt> attempts = new ArrayList<>();
            for (MutableAttempt attempt : generation.attempts.values()) {
                if (!attempt.closed()) {
                    throw new IllegalArgumentException(
                            "physical attempt is not closed: " + attempt.attemptId);
                }
                if (attempt.response != null
                        && attempt.response.capturedNativeToolProposalCount()
                                != attempt.proposals.size()) {
                    throw new IllegalArgumentException(
                            "captured native tool proposal count does not match response: "
                                    + attempt.attemptId);
                }
                attempts.add(attempt.freeze());
            }
            frozen.add(generation.freeze(attempts));
        }
        QualificationProviderTransportLedger.Ledger transports =
                QualificationProviderTransportLedger.parse(traceRoot);
        QualificationProviderTransportLedger.validateAgainst(transports, frozen);
        return new Ledger(frozen, transports);
    }

    private static void generationStarted(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        String generationId = requiredId(data, "generationId");
        ProviderResponseCapture.Source source = enumValue(
                ProviderResponseCapture.Source.class, data, "source");
        ProviderResponseCapture.Purpose purpose = enumValue(
                ProviderResponseCapture.Purpose.class, data, "purpose");
        requireCanonicalPurpose(source, purpose);
        ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode =
                toolAuthorityMode(data);
        requireCanonicalToolAuthorityMode(source, purpose, toolAuthorityMode);
        if (generations.putIfAbsent(generationId,
                new MutableGeneration(
                        generationId, source, purpose, toolAuthorityMode,
                        data.has("toolAuthorityMode"), eventIndex)) != null) {
            throw new IllegalArgumentException("duplicate physical generationId: " + generationId);
        }
    }

    private static void attemptStarted(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            Set<String> attemptIds,
            int eventIndex) {
        MutableGeneration generation = openGeneration(data, generations);
        AttemptFields fields = attemptFields(data, generation);
        if (!attemptIds.add(fields.attemptId())) {
            throw new IllegalArgumentException("duplicate physical attemptId: " + fields.attemptId());
        }
        if (!generation.attempts.isEmpty()
                && !new ArrayList<>(generation.attempts.values()).getLast().closed()) {
            throw new IllegalArgumentException(
                    "physical attempt started before its predecessor closed: "
                            + fields.attemptId());
        }
        int expectedOrdinal = generation.attempts.size() + 1;
        if (fields.attemptOrdinal() != expectedOrdinal
                || !fields.attemptId().equals(
                        generation.generationId + "-attempt-" + expectedOrdinal)) {
            throw new IllegalArgumentException(
                    "physical attempt ordinal/id is not contiguous: " + fields.attemptId());
        }
        if (fields.requestDispatched()) {
            throw new IllegalArgumentException("attempt start is already marked dispatched");
        }
        requireRetryContract(fields.attemptOrdinal(), fields.attemptKind(), fields.retryReason());
        generation.attempts.put(
                fields.attemptId(), new MutableAttempt(generation, fields, eventIndex));
    }

    private static void requestDispatched(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int traceSchemaVersion,
            int eventIndex) {
        MutableAttempt attempt = openAttempt(data, generations);
        AttemptFields fields = attemptFields(data, attempt.generation);
        attempt.requireSame(fields);
        if (!providerSource(fields.source())) {
            throw new IllegalArgumentException(
                    "request dispatch is attached to a non-provider source");
        }
        if (!fields.requestDispatched() || attempt.requestDispatched
                || attempt.transportUnobserved) {
            throw new IllegalArgumentException(
                    "request dispatch transition is duplicate or false: " + attempt.attemptId);
        }
        DispatchFields dispatch = dispatchFields(data, traceSchemaVersion);
        if (data.has("toolAuthorityMode")) {
            ProviderResponseCapture.ToolAuthorityMode mode =
                    attempt.generation.toolAuthorityMode;
            if (mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                    && !dispatch.providerBodyHash().isBlank()
                    && dispatch.nativeTools().isEmpty()) {
                throw new IllegalArgumentException(
                        "native tool authority dispatch lacks native tool names");
            }
            if (mode != ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE
                    && !dispatch.nativeTools().isEmpty()) {
                throw new IllegalArgumentException(
                    "non-native tool authority dispatch carries native tool names");
            }
            validateAuthoritySurface(mode, dispatch);
            if (traceSchemaVersion == TRACE_SCHEMA_VERSION) {
                AuthoritySurface generationSurface =
                        attempt.generation.authoritySurface;
                if (generationSurface == null) {
                    attempt.generation.authoritySurface = dispatch.authoritySurface();
                } else if (!generationSurface.equals(dispatch.authoritySurface())) {
                    throw new IllegalArgumentException(
                            "provider retry substituted the generation authority surface");
                }
            }
        }
        attempt.providerBodyHash = dispatch.providerBodyHash();
        attempt.dispatchedNativeTools = dispatch.nativeTools();
        attempt.authoritySurface = dispatch.authoritySurface();
        attempt.requestDispatched = true;
        attempt.dispatchEventIndex = eventIndex;
    }

    private static void validateAuthoritySurface(
            ProviderResponseCapture.ToolAuthorityMode mode,
            DispatchFields dispatch
    ) {
        AuthoritySurface surface = dispatch.authoritySurface();
        if (mode == ProviderResponseCapture.ToolAuthorityMode.NONE) {
            if (surface.bound()) {
                throw new IllegalArgumentException(
                        "tool-authority NONE dispatch carries a bound surface");
            }
            return;
        }
        if (!surface.bound()) {
            throw new IllegalArgumentException(
                    "provider tool-authority dispatch lacks a bound selected surface");
        }
        if (mode == ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE) {
            List<String> dispatchedHashes = dispatch.nativeTools().stream()
                    .map(ProviderResponseCapture::toolNameHash)
                    .toList();
            if (!dispatchedHashes.equals(surface.selectedToolNameHashes())) {
                throw new IllegalArgumentException(
                        "native dispatched tools differ from the selected authority surface");
            }
        } else if (!dispatch.nativeTools().isEmpty()) {
            throw new IllegalArgumentException(
                    "text tool authority dispatch carries native tools");
        }
    }

    private static void transportUnobserved(
            JsonNode data,
            Map<String, MutableGeneration> generations) {
        MutableAttempt attempt = openAttempt(data, generations);
        AttemptFields fields = attemptFields(data, attempt.generation);
        attempt.requireSame(fields);
        if (!providerSource(fields.source())) {
            throw new IllegalArgumentException(
                    "transport-unobserved transition is attached to a non-provider source");
        }
        if (fields.requestDispatched() || attempt.requestDispatched
                || attempt.transportUnobserved) {
            throw new IllegalArgumentException(
                    "transport-unobserved transition is duplicate or dispatched: "
                            + attempt.attemptId);
        }
        attempt.transportUnobserved = true;
    }

    private static void responseCaptured(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        MutableAttempt attempt = openAttempt(data, generations);
        AttemptFields fields = attemptFields(data, attempt.generation);
        attempt.requireSame(fields);
        ProviderResponseCapture.Completion completion = enumValue(
                ProviderResponseCapture.Completion.class, data, "completion");
        boolean syntheticUndispatched = !attempt.requestDispatched
                && !fields.requestDispatched()
                && completion == ProviderResponseCapture.Completion.SYNTHETIC_RETURN
                && (fields.source() == ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME
                    || fields.source() == ProviderResponseCapture.Source.SCRIPTED_HARNESS);
        boolean legacyUnobservedProvider = !attempt.requestDispatched
                && !fields.requestDispatched()
                && attempt.transportUnobserved
                && (fields.source() == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    || fields.source() == ProviderResponseCapture.Source.BACKGROUND_PROVIDER);
        if ((!attempt.requestDispatched || !fields.requestDispatched())
                && !syntheticUndispatched && !legacyUnobservedProvider) {
            throw new IllegalArgumentException(
                    "provider response lacks a dispatched physical request: " + attempt.attemptId);
        }
        String hash = requiredHash(data, "assistantHash");
        int chars = requiredNonNegativeInt(data, "assistantChars");
        int nativeCalls = requiredNonNegativeInt(data, "nativeToolCallCount");
        int capturedNativeCalls = requiredNonNegativeInt(
                data, "capturedNativeToolProposalCount");
        boolean nativeProposalsTruncated = requiredBoolean(
                data, "nativeToolProposalsTruncated");
        int expectedCapturedNativeCalls = Math.min(
                nativeCalls, ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS);
        if (capturedNativeCalls != expectedCapturedNativeCalls
                || nativeProposalsTruncated != (nativeCalls > expectedCapturedNativeCalls)) {
            throw new IllegalArgumentException(
                    "captured native tool proposal bound contradicts response count");
        }
        boolean textualCall = requiredBoolean(data, "textualToolCallPresent");
        boolean providerBacked = requiredBoolean(data, "providerBacked");
        boolean foreground = requiredBoolean(data, "foreground");
        boolean normalized = requiredBoolean(data, "normalized");
        boolean truncated = requiredBoolean(data, "truncated");
        boolean completedNormally = requiredBoolean(data, "completedNormally");
        boolean expectedProviderBacked = fields.requestDispatched()
                && (fields.source() == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    || fields.source() == ProviderResponseCapture.Source.BACKGROUND_PROVIDER);
        boolean expectedForeground = fields.source()
                == ProviderResponseCapture.Source.FOREGROUND_PROVIDER;
        boolean expectedNormal = fields.requestDispatched()
                && expectedForeground
                && completion == ProviderResponseCapture.Completion.PROVIDER_EOS
                && !normalized
                && !truncated;
        if (providerBacked != expectedProviderBacked
                || foreground != expectedForeground
                || completedNormally != expectedNormal) {
            throw new IllegalArgumentException(
                    "provider response derived flags contradict typed source/completion");
        }
        String transportOperationId = data.has("transportOperationId")
                ? requiredId(data, "transportOperationId")
                : "";
        if (!transportOperationId.isBlank()
                && !transportOperationId.equals(fields.attemptId() + "-transport-1")) {
            throw new IllegalArgumentException(
                    "provider response transport identity does not match its attempt");
        }
        attempt.response = new Response(
                eventIndex,
                requiredLong(data, "ledgerSequence"),
                fields.generationId(), fields.attemptId(), fields.attemptOrdinal(),
                fields.source(), fields.purpose(), fields.attemptKind(), fields.retryReason(),
                hash, chars, nativeCalls, capturedNativeCalls, nativeProposalsTruncated,
                textualCall, completion,
                providerBacked, foreground, completedNormally, normalized, truncated,
                transportOperationId);
        attempt.closeEventIndex = eventIndex;
    }

    private static void attemptFailed(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        MutableAttempt attempt = openAttempt(data, generations);
        AttemptFields fields = attemptFields(data, attempt.generation);
        attempt.requireSame(fields);
        if (fields.requestDispatched() != attempt.requestDispatched) {
            throw new IllegalArgumentException("attempt failure dispatch state is inconsistent");
        }
        ProviderResponseCapture.Failure failure = enumValue(
                ProviderResponseCapture.Failure.class, data, "failure");
        requireFailureSource(fields.source(), failure);
        requireAttemptFailureState(attempt, fields, failure);
        attempt.failure = failure;
        attempt.closeEventIndex = eventIndex;
    }

    private static void requireAttemptFailureState(
            MutableAttempt attempt,
            AttemptFields fields,
            ProviderResponseCapture.Failure failure) {
        if (providerFailure(failure)) {
            if (!attempt.requestDispatched || attempt.transportUnobserved) {
                throw new IllegalArgumentException(
                        "provider attempt failure lacks dispatch");
            }
            return;
        }
        if (failure == ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH) {
            if (attempt.requestDispatched || attempt.transportUnobserved) {
                throw new IllegalArgumentException(
                        "pre-dispatch failure follows dispatch or unobserved transport");
            }
            return;
        }
        if (failure == ProviderResponseCapture.Failure.TRANSPORT_UNOBSERVED) {
            if (!attempt.transportUnobserved || attempt.requestDispatched) {
                throw new IllegalArgumentException(
                        "transport-unobserved failure lacks its explicit transition");
            }
            return;
        }
        if (failure == ProviderResponseCapture.Failure.RUNTIME_EXCEPTION
                && (fields.source() == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    || fields.source() == ProviderResponseCapture.Source.BACKGROUND_PROVIDER)
                && !attempt.requestDispatched) {
            throw new IllegalArgumentException(
                    "post-dispatch runtime failure lacks dispatch");
        }
        if (failure == ProviderResponseCapture.Failure.HARNESS_EXCEPTION
                && fields.source() != ProviderResponseCapture.Source.SCRIPTED_HARNESS) {
            throw new IllegalArgumentException(
                    "harness failure is attached to a non-harness source");
        }
    }

    private static void attemptAbandoned(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        MutableAttempt attempt = openAttempt(data, generations);
        AttemptFields fields = attemptFields(data, attempt.generation);
        attempt.requireSame(fields);
        if (fields.requestDispatched() != attempt.requestDispatched) {
            throw new IllegalArgumentException("attempt abandonment dispatch state is inconsistent");
        }
        attempt.abandonment = enumValue(
                ProviderResponseCapture.Abandonment.class, data, "abandonment");
        attempt.closeEventIndex = eventIndex;
    }

    private static void generationFailed(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        MutableGeneration generation = openGeneration(data, generations);
        requireAllAttemptsClosed(generation);
        ProviderResponseCapture.Failure failure = enumValue(
                ProviderResponseCapture.Failure.class, data, "failure");
        requireFailureSource(generation.source, failure);
        MutableAttempt finalAttempt = generation.attempts.isEmpty()
                ? null
                : new ArrayList<>(generation.attempts.values()).getLast();
        if (failure == ProviderResponseCapture.Failure.TRANSPORT_UNOBSERVED
                && finalAttempt == null) {
            throw new IllegalArgumentException(
                    "transport-unobserved generation failure lacks an attempt");
        }
        if (finalAttempt != null
                && finalAttempt.failure != null
                && finalAttempt.failure != failure) {
            throw new IllegalArgumentException(
                    "generation and final attempt failure disagree");
        }
        if (providerFailure(failure)
                && (finalAttempt == null || !finalAttempt.requestDispatched)) {
            throw new IllegalArgumentException(
                    "provider generation failure lacks a dispatched final attempt");
        }
        generation.failure = failure;
        generation.close("GENERATION_FAILED", eventIndex);
    }

    private static boolean providerFailure(ProviderResponseCapture.Failure failure) {
        return failure == ProviderResponseCapture.Failure.PROVIDER_TRANSIENT
                || failure == ProviderResponseCapture.Failure.PROVIDER_MALFORMED_RESPONSE
                || failure == ProviderResponseCapture.Failure.PROVIDER_EXCEPTION
                || failure == ProviderResponseCapture.Failure.PROVIDER_RESOURCE_EXHAUSTED;
    }

    private static boolean providerSource(ProviderResponseCapture.Source source) {
        return source == ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                || source == ProviderResponseCapture.Source.BACKGROUND_PROVIDER;
    }

    private static void requireFailureSource(
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Failure failure) {
        if (providerFailure(failure) && !providerSource(source)) {
            throw new IllegalArgumentException(
                    "provider failure is attached to a non-provider source");
        }
        if (failure == ProviderResponseCapture.Failure.TRANSPORT_UNOBSERVED
                && !providerSource(source)) {
            throw new IllegalArgumentException(
                    "transport-unobserved failure is attached to a non-provider source");
        }
        if (failure == ProviderResponseCapture.Failure.HARNESS_EXCEPTION
                && source != ProviderResponseCapture.Source.SCRIPTED_HARNESS) {
            throw new IllegalArgumentException(
                    "harness failure is attached to a non-harness source");
        }
        if (source == ProviderResponseCapture.Source.SCRIPTED_HARNESS
                && failure != ProviderResponseCapture.Failure.HARNESS_EXCEPTION) {
            throw new IllegalArgumentException(
                    "non-harness failure is attached to a harness source");
        }
        if (source == ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME
                && failure != ProviderResponseCapture.Failure.RUNTIME_PRE_DISPATCH
                && failure != ProviderResponseCapture.Failure.RUNTIME_EXCEPTION) {
            throw new IllegalArgumentException(
                    "non-runtime failure is attached to a placeholder source");
        }
    }

    private static void generationAbandoned(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        MutableGeneration generation = openGeneration(data, generations);
        requireAllAttemptsClosed(generation);
        generation.abandonment = enumValue(
                ProviderResponseCapture.Abandonment.class, data, "abandonment");
        if (!generation.attempts.isEmpty()) {
            MutableAttempt last = new ArrayList<>(generation.attempts.values()).getLast();
            // The attempt can settle with a response or failure immediately
            // before the generation-level abort arbiter wins. In that race
            // there is no attempt abandonment to compare; the closed attempt
            // remains authoritative and only the generation is abandoned.
            if (last.abandonment != null
                    && last.abandonment != generation.abandonment) {
                throw new IllegalArgumentException(
                        "generation and final attempt abandonment disagree");
            }
        }
        generation.close("GENERATION_ABANDONED", eventIndex);
    }

    private static void generationCompleted(
            JsonNode data,
            Map<String, MutableGeneration> generations,
            int eventIndex) {
        MutableGeneration generation = openGeneration(data, generations);
        requireAllAttemptsClosed(generation);
        String selectedAttemptId = requiredId(data, "selectedAttemptId");
        int selectedOrdinal = requiredPositiveInt(data, "selectedAttemptOrdinal");
        MutableAttempt selected = generation.attempts.get(selectedAttemptId);
        if (selected == null || selected.response == null
                || selected.ordinal != selectedOrdinal
                || selected != new ArrayList<>(generation.attempts.values()).getLast()) {
            throw new IllegalArgumentException(
                    "generation completion does not select its final response attempt");
        }
        ProviderResponseCapture.Completion completion = enumValue(
                ProviderResponseCapture.Completion.class, data, "completion");
        String hash = requiredHash(data, "assistantHash");
        boolean terminalEligible = requiredBoolean(data, "providerTerminalEligible");
        boolean toolEligible = requiredBoolean(data, "providerToolCallEligible");
        String transportOperationId = data.has("transportOperationId")
                ? requiredId(data, "transportOperationId")
                : "";
        boolean structurallyTerminalEligible = selected.response.completedNormally();
        boolean structurallyToolEligible;
        if (!generation.authorityModeRecorded) {
            structurallyToolEligible = selected.requestDispatched
                    && selected.response.foreground()
                    && selected.response.toolCallPresent()
                    && (selected.response.completion()
                            == ProviderResponseCapture.Completion.PROVIDER_EOS
                        || selected.response.completion()
                            == ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP);
        } else {
            boolean expectedToolShape = switch (generation.toolAuthorityMode) {
                case NONE -> false;
                case PROVIDER_NATIVE -> selected.response.nativeToolCallCount() > 0;
                case PROVIDER_TEXT_JSON ->
                        selected.response.nativeToolCallCount() == 0
                                && selected.response.textualToolCallPresent();
            };
            structurallyToolEligible = selected.requestDispatched
                    && selected.response.foreground()
                    && selected.response.completion()
                            == ProviderResponseCapture.Completion.PROVIDER_EOS
                    && selected.authoritySurface.bound()
                    && expectedToolShape;
        }
        boolean transportProofDeferred = !transportOperationId.isBlank();
        boolean eligibilityConsistent = transportProofDeferred
                ? (!terminalEligible || structurallyTerminalEligible)
                        && (!toolEligible || structurallyToolEligible)
                : selected.transportUnobserved
                        ? !terminalEligible && !toolEligible
                        : terminalEligible == structurallyTerminalEligible
                                && toolEligible == structurallyToolEligible;
        if (completion != selected.response.completion()
                || !hash.equals(selected.response.assistantHash())
                || !eligibilityConsistent
                || !transportOperationId.equals(
                        selected.response.transportOperationId())) {
            throw new IllegalArgumentException(
                    "generation completion contradicts its selected response");
        }
        generation.selectedAttemptId = selectedAttemptId;
        generation.selectedAttemptOrdinal = selectedOrdinal;
        generation.providerTerminalEligible = terminalEligible;
        generation.providerToolCallEligible = toolEligible;
        generation.close("GENERATION_COMPLETED", eventIndex);
    }

    private static void parseProposal(
            JsonNode rawData,
            Map<String, MutableGeneration> generations,
            int eventIndex,
            int traceSchemaVersion) {
        JsonNode data = requiredObject(rawData, "provider tool proposal data");
        exactKeys(
                data,
                keys(TOOL_PROPOSAL_KEYS, traceSchemaVersion),
                "PROVIDER_TOOL_PROPOSAL_CAPTURED");
        if (requiredInt(data, "providerLedgerVersion") != LEDGER_VERSION) {
            throw new IllegalArgumentException("provider ledger version is not 1");
        }
        MutableGeneration generation = openGeneration(data, generations);
        MutableAttempt attempt = existingAttempt(data, generation);
        AttemptFields fields = attemptFields(data, generation);
        attempt.requireSame(fields);
        if (attempt.response == null
                || requiredLong(data, "ledgerSequence")
                        != attempt.response.ledgerSequence()
                || eventIndex <= attempt.response.eventIndex()) {
            throw new IllegalArgumentException(
                    "provider tool proposal is not bound to its captured response");
        }
        int ordinal = requiredPositiveInt(data, "proposalOrdinal");
        if (ordinal > ProviderResponseCapture.MAX_TRACE_TOOL_PROPOSALS
                || ordinal != attempt.proposals.size() + 1) {
            throw new IllegalArgumentException("provider tool proposal ordinals are not contiguous");
        }
        String providerCallIdHash = requiredHash(data, "providerCallIdHash");
        String classification = requiredText(data, "toolNameClassification");
        if (!TOOL_NAME_CLASSIFICATIONS.contains(classification)) {
            throw new IllegalArgumentException(
                    "provider tool-name classification is not canonical");
        }
        String toolNameHash = requiredHash(data, "toolNameHash");
        requiredNonNegativeInt(data, "toolNameChars");
        JsonNode names = data.path("argumentNameHashes");
        if (!names.isArray()
                || names.size() > ProviderResponseCapture.MAX_TRACE_ARGUMENT_NAME_HASHES) {
            throw new IllegalArgumentException(
                    "provider tool proposal argumentNameHashes is not a bounded array");
        }
        List<String> values = new ArrayList<>();
        names.forEach(name -> {
            String hash = requiredTextValue(name, "argument-name hash");
            if (!hash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(
                        "argument-name hash is not a lowercase SHA-256 reference");
            }
            values.add(hash);
        });
        int argumentCount = requiredNonNegativeInt(data, "argumentCount");
        boolean hashesTruncated = requiredBoolean(data, "argumentNameHashesTruncated");
        int expectedHashCount = Math.min(
                argumentCount, ProviderResponseCapture.MAX_TRACE_ARGUMENT_NAME_HASHES);
        if (!values.equals(values.stream().sorted().toList())
                || values.size() != expectedHashCount
                || hashesTruncated != (argumentCount > expectedHashCount)) {
            throw new IllegalArgumentException(
                    "provider tool proposal argument-name hashes/count are not canonical");
        }
        String argumentsHash = requiredHash(data, "argumentsHash");
        attempt.proposals.add(new Proposal(
                ordinal, providerCallIdHash, toolNameHash, argumentsHash));
    }

    private static MutableGeneration openGeneration(
            JsonNode data,
            Map<String, MutableGeneration> generations) {
        MutableGeneration generation = existingGeneration(data, generations);
        if (!generation.terminalEvent.isBlank()) {
            throw new IllegalArgumentException(
                    "physical event follows closed generation: " + generation.generationId);
        }
        return generation;
    }

    private static MutableGeneration existingGeneration(
            JsonNode data,
            Map<String, MutableGeneration> generations) {
        String generationId = requiredId(data, "generationId");
        MutableGeneration generation = generations.get(generationId);
        if (generation == null) {
            throw new IllegalArgumentException(
                    "physical event lacks generation start: " + generationId);
        }
        ProviderResponseCapture.Source source = enumValue(
                ProviderResponseCapture.Source.class, data, "source");
        ProviderResponseCapture.Purpose purpose = enumValue(
                ProviderResponseCapture.Purpose.class, data, "purpose");
        ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode =
                toolAuthorityMode(data);
        if (source != generation.source
                || purpose != generation.purpose
                || toolAuthorityMode != generation.toolAuthorityMode) {
            throw new IllegalArgumentException(
                    "physical event source/purpose/tool authority differs from generation start");
        }
        return generation;
    }

    private static MutableAttempt openAttempt(
            JsonNode data,
            Map<String, MutableGeneration> generations) {
        MutableGeneration generation = openGeneration(data, generations);
        MutableAttempt attempt = existingAttempt(data, generation);
        if (attempt.closed()) {
            throw new IllegalArgumentException(
                    "physical event follows closed attempt: " + attempt.attemptId);
        }
        return attempt;
    }

    private static MutableAttempt existingAttempt(
            JsonNode data,
            MutableGeneration generation) {
        String attemptId = requiredId(data, "attemptId");
        MutableAttempt attempt = generation.attempts.get(attemptId);
        if (attempt == null) {
            throw new IllegalArgumentException(
                    "physical event lacks attempt start: " + attemptId);
        }
        return attempt;
    }

    private static AttemptFields attemptFields(
            JsonNode data,
            MutableGeneration generation) {
        ProviderResponseCapture.Source source = enumValue(
                ProviderResponseCapture.Source.class, data, "source");
        ProviderResponseCapture.Purpose purpose = enumValue(
                ProviderResponseCapture.Purpose.class, data, "purpose");
        if (source != generation.source || purpose != generation.purpose) {
            throw new IllegalArgumentException("attempt source/purpose differs from generation");
        }
        return new AttemptFields(
                requiredId(data, "generationId"),
                requiredId(data, "attemptId"),
                requiredPositiveInt(data, "attemptOrdinal"),
                source,
                purpose,
                enumValue(ProviderResponseCapture.AttemptKind.class, data, "attemptKind"),
                enumValue(ProviderResponseCapture.RetryReason.class, data, "retryReason"),
                requiredBoolean(data, "requestDispatched"));
    }

    private static void requireAllAttemptsClosed(MutableGeneration generation) {
        if (generation.attempts.values().stream().anyMatch(attempt -> !attempt.closed())) {
            throw new IllegalArgumentException(
                    "generation closed while a physical attempt remained open");
        }
    }

    private static void requireRetryContract(
            int ordinal,
            ProviderResponseCapture.AttemptKind kind,
            ProviderResponseCapture.RetryReason reason) {
        if (ordinal == 1) {
            if (kind != ProviderResponseCapture.AttemptKind.PRIMARY
                    || reason != ProviderResponseCapture.RetryReason.NONE) {
                throw new IllegalArgumentException("first physical attempt is not PRIMARY/NONE");
            }
            return;
        }
        ProviderResponseCapture.RetryReason expected = switch (kind) {
            case TRANSIENT_RETRY -> ProviderResponseCapture.RetryReason.TRANSIENT_ENGINE_FAILURE;
            case OUTPUT_CAP_LIFT_RETRY -> ProviderResponseCapture.RetryReason.OUTPUT_CAP_TRUNCATION;
            case COMPAT_NON_STREAM_RETRY ->
                    ProviderResponseCapture.RetryReason.MALFORMED_TOOL_ARGUMENTS;
            case PRIMARY -> null;
        };
        if (expected == null || reason != expected) {
            throw new IllegalArgumentException("retry attempt kind/reason is inconsistent");
        }
    }

    private static void requireCanonicalPurpose(
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Purpose purpose) {
        ProviderResponseCapture.Purpose expected = switch (source) {
            case FOREGROUND_PROVIDER -> ProviderResponseCapture.Purpose.TURN_RESPONSE;
            case BACKGROUND_PROVIDER -> ProviderResponseCapture.Purpose.BACKGROUND_MAINTENANCE;
            case PLACEHOLDER_RUNTIME -> ProviderResponseCapture.Purpose.PLACEHOLDER_RESPONSE;
            case SCRIPTED_HARNESS -> ProviderResponseCapture.Purpose.HARNESS_FIXTURE;
        };
        if (purpose != expected) {
            throw new IllegalArgumentException("generation source/purpose is not canonical");
        }
    }

    private static ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode(
            JsonNode data
    ) {
        if (data == null || !data.has("toolAuthorityMode")) {
            return ProviderResponseCapture.ToolAuthorityMode.NONE;
        }
        return enumValue(
                ProviderResponseCapture.ToolAuthorityMode.class,
                data,
                "toolAuthorityMode");
    }

    private static void requireCanonicalToolAuthorityMode(
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Purpose purpose,
            ProviderResponseCapture.ToolAuthorityMode mode
    ) {
        if (mode != ProviderResponseCapture.ToolAuthorityMode.NONE
                && (source != ProviderResponseCapture.Source.FOREGROUND_PROVIDER
                    || purpose != ProviderResponseCapture.Purpose.TURN_RESPONSE)) {
            throw new IllegalArgumentException(
                    "provider tool authority mode is attached to a non-foreground turn");
        }
    }

    private static void requireLedgerHeader(JsonNode data, long expectedSequence) {
        if (requiredInt(data, "providerLedgerVersion") != LEDGER_VERSION) {
            throw new IllegalArgumentException("provider ledger version is not 1");
        }
        long sequence = requiredLong(data, "ledgerSequence");
        if (sequence != expectedSequence) {
            throw new IllegalArgumentException(
                    "provider ledger sequence is not contiguous: expected "
                            + expectedSequence + ", found " + sequence);
        }
    }

    private static Set<String> keys(String type) {
        return switch (type) {
            case "GENERATION_STARTED" -> GENERATION_KEYS;
            case "GENERATION_ATTEMPT_STARTED", "GENERATION_TRANSPORT_UNOBSERVED" -> ATTEMPT_KEYS;
            case "GENERATION_REQUEST_DISPATCHED" -> DISPATCH_KEYS;
            case "GENERATION_RESPONSE_CAPTURED" -> RESPONSE_KEYS;
            case "GENERATION_ATTEMPT_FAILED" -> ATTEMPT_FAILURE_KEYS;
            case "GENERATION_ATTEMPT_ABANDONED" -> ATTEMPT_ABANDONMENT_KEYS;
            case "GENERATION_FAILED" -> GENERATION_FAILURE_KEYS;
            case "GENERATION_ABANDONED" -> GENERATION_ABANDONMENT_KEYS;
            case "GENERATION_COMPLETED" -> GENERATION_COMPLETION_KEYS;
            default -> throw new IllegalArgumentException("unknown physical event: " + type);
        };
    }

    private static Set<String> keys(String type, int traceSchemaVersion) {
        Set<String> expected = keys(keys(type), traceSchemaVersion);
        if (traceSchemaVersion == TRACE_SCHEMA_VERSION
                && "GENERATION_REQUEST_DISPATCHED".equals(type)) {
            expected = union(expected, Set.of("toolAuthoritySurface"));
        }
        return expected;
    }

    private static Set<String> keys(Set<String> base, int traceSchemaVersion) {
        return traceSchemaVersion == TRACE_SCHEMA_VERSION
                ? union(base, Set.of("toolAuthorityMode"))
                : base;
    }

    private static void exactKeys(JsonNode object, Set<String> expected, String label) {
        Set<String> actual = fields(object);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    label + " data keys differ; expected " + expected + ", found " + actual);
        }
    }

    private static void exactKeysOneOf(
            JsonNode object,
            Set<String> legacy,
            Set<String> transportBound,
            String label) {
        Set<String> actual = fields(object);
        if (!actual.equals(legacy) && !actual.equals(transportBound)) {
            throw new IllegalArgumentException(
                    label + " data keys differ; expected " + legacy + " or "
                            + transportBound + ", found " + actual);
        }
    }

    private static Set<String> fields(JsonNode object) {
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        return Set.copyOf(actual);
    }

    private static DispatchFields dispatchFields(JsonNode data, int traceSchemaVersion) {
        if (!data.has("providerBodyHash") && !data.has("dispatchedNativeTools")) {
            if (traceSchemaVersion == TRACE_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "trace-v4 request dispatch requires body-bound evidence");
            }
            return new DispatchFields("", List.of(), AuthoritySurface.unbound());
        }
        String bodyHash = requiredHash(data, "providerBodyHash");
        JsonNode values = data.path("dispatchedNativeTools");
        if (!values.isArray()) {
            throw new IllegalArgumentException("dispatchedNativeTools must be an array");
        }
        List<String> tools = new ArrayList<>();
        LinkedHashSet<String> distinctTools = new LinkedHashSet<>();
        for (JsonNode value : values) {
            String tool = requiredTextValue(value, "dispatchedNativeTools entry");
            if (tool.isBlank() || !tool.equals(tool.strip()) || tool.length() > 256
                    || !distinctTools.add(tool)) {
                throw new IllegalArgumentException(
                        "dispatchedNativeTools contains a blank, normalized, oversized, or duplicate tool");
            }
            tools.add(tool);
        }
        AuthoritySurface authoritySurface = traceSchemaVersion == TRACE_SCHEMA_VERSION
                ? authoritySurface(data.path("toolAuthoritySurface"))
                : AuthoritySurface.unbound();
        return new DispatchFields(bodyHash, List.copyOf(tools), authoritySurface);
    }

    private static AuthoritySurface authoritySurface(JsonNode raw) {
        JsonNode surface = requiredObject(raw, "toolAuthoritySurface");
        exactKeys(surface, AUTHORITY_SURFACE_KEYS, "toolAuthoritySurface");
        if (requiredInt(surface, "schemaVersion") != 1) {
            throw new IllegalArgumentException(
                    "toolAuthoritySurface schemaVersion must be 1");
        }
        boolean bound = requiredBoolean(surface, "bound");
        ToolChoiceMode choice = enumValue(
                ToolChoiceMode.class, surface, "toolChoiceMode");
        String namedHash = requiredHash(surface, "namedToolNameHash");
        List<String> selected = requiredHashList(
                surface.path("selectedToolNameHashes"),
                "selectedToolNameHashes");
        List<String> authorized = requiredHashList(
                surface.path("authorizedToolNameHashes"),
                "authorizedToolNameHashes");
        String emptyHash = ProviderResponseCapture.toolNameHash("");
        if (!bound) {
            if (choice != ToolChoiceMode.NONE
                    || !namedHash.equals(emptyHash)
                    || !selected.isEmpty()
                    || !authorized.isEmpty()) {
                throw new IllegalArgumentException(
                        "unbound toolAuthoritySurface carries authority");
            }
            return AuthoritySurface.unbound();
        }
        if (selected.isEmpty() || selected.size() > 256
                || new LinkedHashSet<>(selected).size() != selected.size()) {
            throw new IllegalArgumentException(
                    "bound toolAuthoritySurface selected names are empty, duplicate, or oversized");
        }
        if (choice == ToolChoiceMode.NONE) {
            throw new IllegalArgumentException(
                    "bound toolAuthoritySurface cannot use tool choice NONE");
        }
        if (choice == ToolChoiceMode.NAMED) {
            if (authorized.size() != 1
                    || !authorized.getFirst().equals(namedHash)
                    || !selected.contains(namedHash)) {
                throw new IllegalArgumentException(
                        "NAMED toolAuthoritySurface is not an exact selected singleton");
            }
        } else if (!namedHash.equals(emptyHash) || !authorized.equals(selected)) {
            throw new IllegalArgumentException(
                    "non-NAMED toolAuthoritySurface authority differs from selection");
        }
        return new AuthoritySurface(bound, choice, namedHash, selected, authorized);
    }

    private static List<String> requiredHashList(JsonNode node, String label) {
        if (node == null || !node.isArray()) {
            throw new IllegalArgumentException(label + " must be an array");
        }
        List<String> hashes = new ArrayList<>();
        for (JsonNode value : node) {
            String hash = requiredTextValue(value, label + " entry");
            if (!hash.matches("sha256:[0-9a-f]{64}")) {
                throw new IllegalArgumentException(label + " contains an invalid hash");
            }
            hashes.add(hash);
        }
        return List.copyOf(hashes);
    }

    private static JsonNode requiredObject(JsonNode node, String label) {
        if (node == null || !node.isObject()) {
            throw new IllegalArgumentException(label + " must be an object");
        }
        return node;
    }

    private static String requiredText(JsonNode object, String field) {
        return requiredTextValue(object.path(field), field);
    }

    private static String requiredTextValue(JsonNode node, String field) {
        if (node == null || !node.isTextual()) {
            throw new IllegalArgumentException(field + " must be text");
        }
        return node.textValue();
    }

    private static String requiredId(JsonNode object, String field) {
        String value = requiredText(object, field);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,255}")) {
            throw new IllegalArgumentException(field + " is not a safe nonblank identifier");
        }
        return value;
    }

    private static String requiredHash(JsonNode object, String field) {
        String value = requiredText(object, field);
        if (!value.matches("sha256:[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " is not a lowercase SHA-256 reference");
        }
        return value;
    }

    private static boolean requiredBoolean(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isBoolean()) throw new IllegalArgumentException(field + " must be boolean");
        return value.booleanValue();
    }

    private static int requiredInt(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw new IllegalArgumentException(field + " must be a 32-bit integer");
        }
        return value.intValue();
    }

    private static int requiredNonNegativeInt(JsonNode object, String field) {
        int value = requiredInt(object, field);
        if (value < 0) throw new IllegalArgumentException(field + " must not be negative");
        return value;
    }

    private static int requiredPositiveInt(JsonNode object, String field) {
        int value = requiredInt(object, field);
        if (value < 1) throw new IllegalArgumentException(field + " must be positive");
        return value;
    }

    private static long requiredLong(JsonNode object, String field) {
        JsonNode value = object.path(field);
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) {
            throw new IllegalArgumentException(field + " must be a positive 64-bit integer");
        }
        return value.longValue();
    }

    private static <E extends Enum<E>> E enumValue(
            Class<E> type,
            JsonNode object,
            String field) {
        String value = requiredText(object, field);
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(field + " has unknown value: " + value, invalid);
        }
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> out = new LinkedHashSet<>(first);
        out.addAll(second);
        return Set.copyOf(out);
    }

    private record AttemptFields(
            String generationId,
            String attemptId,
            int attemptOrdinal,
            ProviderResponseCapture.Source source,
            ProviderResponseCapture.Purpose purpose,
            ProviderResponseCapture.AttemptKind attemptKind,
            ProviderResponseCapture.RetryReason retryReason,
            boolean requestDispatched) {}

    private static final class MutableGeneration {
        private final String generationId;
        private final ProviderResponseCapture.Source source;
        private final ProviderResponseCapture.Purpose purpose;
        private final ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode;
        private final boolean authorityModeRecorded;
        private final int startEventIndex;
        private final Map<String, MutableAttempt> attempts = new LinkedHashMap<>();
        private String terminalEvent = "";
        private String selectedAttemptId = "";
        private int selectedAttemptOrdinal;
        private boolean providerTerminalEligible;
        private boolean providerToolCallEligible;
        private ProviderResponseCapture.Failure failure;
        private ProviderResponseCapture.Abandonment abandonment;
        private int terminalEventIndex = -1;
        private AuthoritySurface authoritySurface;

        private MutableGeneration(
                String generationId,
                ProviderResponseCapture.Source source,
                ProviderResponseCapture.Purpose purpose,
                ProviderResponseCapture.ToolAuthorityMode toolAuthorityMode,
                boolean authorityModeRecorded,
                int startEventIndex) {
            this.generationId = generationId;
            this.source = source;
            this.purpose = purpose;
            this.toolAuthorityMode = toolAuthorityMode;
            this.authorityModeRecorded = authorityModeRecorded;
            this.startEventIndex = startEventIndex;
        }

        private void close(String event, int eventIndex) {
            if (!terminalEvent.isBlank()) {
                throw new IllegalArgumentException(
                        "physical generation has multiple terminal events: " + generationId);
            }
            terminalEvent = event;
            terminalEventIndex = eventIndex;
        }

        private Generation freeze(List<Attempt> frozenAttempts) {
            return new Generation(
                    generationId, source, purpose, toolAuthorityMode,
                    frozenAttempts, terminalEvent,
                    selectedAttemptId, selectedAttemptOrdinal,
                    providerTerminalEligible, providerToolCallEligible,
                    failure, abandonment,
                    startEventIndex, terminalEventIndex);
        }
    }

    private static final class MutableAttempt {
        private final MutableGeneration generation;
        private final String attemptId;
        private final int ordinal;
        private final ProviderResponseCapture.AttemptKind kind;
        private final ProviderResponseCapture.RetryReason retryReason;
        private final int startEventIndex;
        private boolean requestDispatched;
        private boolean transportUnobserved;
        private String providerBodyHash = "";
        private List<String> dispatchedNativeTools = List.of();
        private AuthoritySurface authoritySurface = AuthoritySurface.unbound();
        private Response response;
        private ProviderResponseCapture.Failure failure;
        private ProviderResponseCapture.Abandonment abandonment;
        private int dispatchEventIndex = -1;
        private int closeEventIndex = -1;
        private final List<Proposal> proposals = new ArrayList<>();

        private MutableAttempt(
                MutableGeneration generation,
                AttemptFields fields,
                int startEventIndex) {
            this.generation = generation;
            this.attemptId = fields.attemptId();
            this.ordinal = fields.attemptOrdinal();
            this.kind = fields.attemptKind();
            this.retryReason = fields.retryReason();
            this.startEventIndex = startEventIndex;
        }

        private void requireSame(AttemptFields fields) {
            if (!attemptId.equals(fields.attemptId())
                    || ordinal != fields.attemptOrdinal()
                    || kind != fields.attemptKind()
                    || retryReason != fields.retryReason()) {
                throw new IllegalArgumentException(
                        "physical attempt identity changed after start: " + attemptId);
            }
        }

        private boolean closed() {
            int terminals = (response == null ? 0 : 1)
                    + (failure == null ? 0 : 1)
                    + (abandonment == null ? 0 : 1);
            return terminals == 1 && closeEventIndex >= startEventIndex;
        }

        private Attempt freeze() {
            return new Attempt(
                    attemptId, ordinal, kind, retryReason, requestDispatched,
                    transportUnobserved, providerBodyHash, dispatchedNativeTools,
                    authoritySurface,
                    response, List.copyOf(proposals), failure, abandonment,
                    startEventIndex, dispatchEventIndex, closeEventIndex);
        }
    }

    private record DispatchFields(
            String providerBodyHash,
            List<String> nativeTools,
            AuthoritySurface authoritySurface) {}
}
