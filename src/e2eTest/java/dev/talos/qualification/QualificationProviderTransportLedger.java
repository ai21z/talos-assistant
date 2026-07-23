package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict, fail-closed reader for independent provider transport operations. */
final class QualificationProviderTransportLedger {
    static final int LEGACY_LEDGER_VERSION = 1;
    static final int LEDGER_VERSION = 2;
    static final String EVENT_TYPE = "PROVIDER_TRANSPORT_LIFECYCLE";

    private static final Set<String> EVENT_KEYS = Set.of(
            "transportLedgerVersion", "transportSequence", "generationId",
            "attemptId", "attemptOrdinal", "transportOperationId", "transition",
            "observationMode", "settlement", "transportFailure", "cancellationState",
            "cancellationReason", "wireDisposition", "quarantineRequired",
            "lateAfterGenerationTerminal", "processBinding");
    private static final Set<String> PROCESS_BOUND_EVENT_KEYS = union(
            EVENT_KEYS, Set.of("processReceiptId"));

    private QualificationProviderTransportLedger() {}

    record Operation(
            String generationId,
            String attemptId,
            int attemptOrdinal,
            String transportOperationId,
            ProviderTransportAttempt.ObservationMode observationMode,
            ProviderTransportAttempt.Settlement settlement,
            ProviderTransportAttempt.Failure failure,
            ProviderTransportAttempt.CancellationState cancellationState,
            ProviderTransportAttempt.CancellationReason cancellationReason,
            ProviderTransportAttempt.WireDisposition wireDisposition,
            ProviderTransportAttempt.ProcessBinding processBinding,
            String processReceiptId,
            boolean providerTerminalObserved,
            boolean bodyCompleted,
            boolean quarantineRequired,
            boolean lateSettlement,
            int firstEventIndex,
            int firstLateEventIndex,
            int lastNonLateEventIndex,
            int cancellationRequestEventIndex,
            int settlementEventIndex) {

        Operation {
            processReceiptId = processReceiptId == null ? "" : processReceiptId;
        }

        boolean observedLifecycle() {
            return observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                    && settlement != ProviderTransportAttempt.Settlement.LIFECYCLE_UNOBSERVED;
        }

        boolean processBound() {
            return processBinding
                            == ProviderTransportAttempt.ProcessBinding.MANAGED_QUALIFICATION_EXACT
                    && processReceiptId.matches("pr-[0-9a-f]{64}");
        }
    }

    record Ledger(Map<String, Operation> operations) {
        Ledger {
            operations = operations == null ? Map.of() : Map.copyOf(operations);
        }

        static Ledger empty() {
            return new Ledger(Map.of());
        }

        boolean present() {
            return !operations.isEmpty();
        }

        Operation operationForAttempt(String attemptId) {
            return operations.values().stream()
                    .filter(operation -> operation.attemptId().equals(attemptId))
                    .findFirst()
                    .orElse(null);
        }

        boolean completeForDispatchedAttempts(
                List<QualificationProviderAttemptLedger.Generation> generations) {
            List<QualificationProviderAttemptLedger.Attempt> dispatched = generations.stream()
                    .flatMap(generation -> generation.attempts().stream())
                    .filter(QualificationProviderAttemptLedger.Attempt::requestDispatched)
                    .toList();
            return !dispatched.isEmpty()
                    && dispatched.stream().allMatch(attempt -> {
                        Operation operation = operationForAttempt(attempt.attemptId());
                        return operation != null && operation.observedLifecycle();
                    });
        }

        boolean quarantinePresent() {
            return operations.values().stream().anyMatch(Operation::quarantineRequired);
        }

        boolean unobservedPresent() {
            return operations.values().stream().anyMatch(operation ->
                    !operation.observedLifecycle());
        }

        boolean activeProcessBindingComplete() {
            return present()
                    && operations.values().stream().allMatch(Operation::processBound)
                    && operations.values().stream()
                            .map(Operation::processReceiptId)
                            .distinct().count() == 1;
        }
    }

    static Ledger parse(JsonNode traceRoot) {
        if (traceRoot == null || !traceRoot.isObject()) {
            throw new IllegalArgumentException("trace must be an object");
        }
        int traceSchemaVersion = QualificationJson.requiredInt(
                traceRoot.path("schemaVersion"), "trace schemaVersion");
        JsonNode events = traceRoot.path("events");
        if (events == null || !events.isArray()) {
            throw new IllegalArgumentException("trace events must be an array");
        }
        Map<String, MutableOperation> mutable = new LinkedHashMap<>();
        for (int eventIndex = 0; eventIndex < events.size(); eventIndex++) {
            JsonNode event = events.get(eventIndex);
            if (!EVENT_TYPE.equals(QualificationJson.requiredText(
                    event.path("type"), "trace event type"))) {
                continue;
            }
            JsonNode data = event.path("data");
            Set<String> expectedKeys = traceSchemaVersion
                            == QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION
                    ? (data.has("processReceiptId") ? PROCESS_BOUND_EVENT_KEYS : EVENT_KEYS)
                    : EVENT_KEYS;
            requireExactKeys(data, expectedKeys, EVENT_TYPE);
            EventFields fields = fields(data, traceSchemaVersion);
            int exactEventIndex = eventIndex;
            MutableOperation operation = mutable.computeIfAbsent(
                    fields.transportOperationId(),
                    ignored -> new MutableOperation(fields, exactEventIndex));
            operation.accept(fields, eventIndex);
        }

        Map<String, Operation> frozen = new LinkedHashMap<>();
        Set<String> attempts = new LinkedHashSet<>();
        for (MutableOperation operation : mutable.values()) {
            Operation exact = operation.freeze();
            if (!attempts.add(exact.attemptId())) {
                throw new IllegalArgumentException(
                        "provider attempt owns multiple transport operations: "
                                + exact.attemptId());
            }
            frozen.put(exact.transportOperationId(), exact);
        }
        return new Ledger(frozen);
    }

    static void validateAgainst(
            Ledger transports,
            List<QualificationProviderAttemptLedger.Generation> generations) {
        Map<String, QualificationProviderAttemptLedger.Attempt> attempts = new LinkedHashMap<>();
        for (QualificationProviderAttemptLedger.Generation generation : generations) {
            QualificationProviderAttemptLedger.Attempt previous = null;
            for (QualificationProviderAttemptLedger.Attempt attempt : generation.attempts()) {
                attempts.put(attempt.attemptId(), attempt);
                Operation operation = transports.operationForAttempt(attempt.attemptId());
                if (operation != null) {
                    if (!attempt.requestDispatched()
                            || !operation.generationId().equals(generation.generationId())
                            || operation.attemptOrdinal() != attempt.attemptOrdinal()) {
                        throw new IllegalArgumentException(
                                "transport operation does not match its provider attempt");
                    }
                    if (attempt.dispatchEventIndex() < 0
                            || operation.firstEventIndex() <= attempt.dispatchEventIndex()
                            || operation.firstEventIndex() >= attempt.closeEventIndex()) {
                        throw new IllegalArgumentException(
                                "transport lifecycle is outside its dispatched attempt");
                    }
                    validateTerminalOrdering(operation, generation);
                    validateAttemptOwnership(operation, generation, attempt);
                    QualificationProviderAttemptLedger.Response response = attempt.response();
                    if (response != null) {
                        if (response.transportOperationId().isBlank()
                                || !response.transportOperationId().equals(
                                        operation.transportOperationId())
                                || operation.settlementEventIndex() >= response.eventIndex()) {
                            throw new IllegalArgumentException(
                                    "provider response is not bound to its settled transport");
                        }
                        validateResponseSettlement(operation, response);
                        if (generation.selectedAttemptId().equals(attempt.attemptId())) {
                            validateSelectedEligibility(operation, generation, attempt, response);
                        }
                    }
                }
                if (previous != null) {
                    Operation predecessor = transports.operationForAttempt(previous.attemptId());
                    if (predecessor != null
                            && predecessor.settlementEventIndex() >= attempt.startEventIndex()) {
                        throw new IllegalArgumentException(
                                "provider retry overlaps its predecessor transport");
                    }
                }
                previous = attempt;
            }
        }
        for (Operation operation : transports.operations().values()) {
            if (!attempts.containsKey(operation.attemptId())) {
                throw new IllegalArgumentException(
                        "transport operation has no provider attempt owner");
            }
        }

        List<Operation> ordered = transports.operations().values().stream()
                .sorted(java.util.Comparator.comparingInt(Operation::firstEventIndex))
                .toList();
        for (int index = 0; index + 1 < ordered.size(); index++) {
            Operation predecessor = ordered.get(index);
            Operation successor = ordered.get(index + 1);
            if (successor.firstEventIndex() <= predecessor.settlementEventIndex()) {
                throw new IllegalArgumentException(
                        "provider transport operations overlap");
            }
            if (predecessor.quarantineRequired()) {
                throw new IllegalArgumentException(
                        "provider transport successor follows quarantine without reset evidence");
            }
        }
    }

    private static void validateTerminalOrdering(
            Operation operation,
            QualificationProviderAttemptLedger.Generation generation) {
        int terminalIndex = generation.terminalEventIndex();
        if (operation.firstLateEventIndex() >= 0
                && operation.firstLateEventIndex() <= terminalIndex) {
            throw new IllegalArgumentException(
                    "late transport evidence precedes generation terminalization");
        }
        if (!"GENERATION_ABANDONED".equals(generation.terminalEvent())
                && operation.firstLateEventIndex() >= 0) {
            throw new IllegalArgumentException(
                    "non-abandoned generation carries late transport evidence");
        }
        if (operation.lastNonLateEventIndex() > terminalIndex) {
            throw new IllegalArgumentException(
                    "post-terminal transport event is not marked late");
        }
        if (operation.settlementEventIndex() > terminalIndex
                && !operation.lateSettlement()) {
            throw new IllegalArgumentException(
                    "transport late settlement contradicts generation ordering");
        }
    }

    private static void validateAttemptOwnership(
            Operation operation,
            QualificationProviderAttemptLedger.Generation generation,
            QualificationProviderAttemptLedger.Attempt attempt) {
        if (operation.cancellationReason()
                != ProviderTransportAttempt.CancellationReason.NONE) {
            ProviderResponseCapture.Abandonment abandonment = attempt.abandonment();
            if (abandonment == null
                    || transportCancellationReason(abandonment)
                            != operation.cancellationReason()) {
                throw new IllegalArgumentException(
                        "transport cancellation does not match attempt abandonment");
            }
            if (operation.cancellationRequestEventIndex() < 0
                    || (operation.cancellationRequestEventIndex() >= attempt.closeEventIndex()
                        && operation.cancellationRequestEventIndex()
                            <= generation.terminalEventIndex())) {
                throw new IllegalArgumentException(
                        "transport cancellation request has no valid terminal owner ordering");
            }
        }
        if (attempt.failure() != null
                && operation.settlementEventIndex() >= attempt.closeEventIndex()) {
            throw new IllegalArgumentException(
                    "failed provider attempt precedes transport settlement");
        }
        if (operation.settlement()
                        == ProviderTransportAttempt.Settlement.FAILED_BEFORE_HEADERS
                && operation.wireDisposition()
                        == ProviderTransportAttempt.WireDisposition.SEND_API_NOT_ENTERED) {
            require(attempt.failure() != null,
                    "no-send transport failure lacks a failed attempt owner");
        }
        if (operation.settlement()
                == ProviderTransportAttempt.Settlement.LIFECYCLE_UNOBSERVED) {
            require((attempt.response() != null || attempt.failure() != null)
                            && attempt.abandonment() == null,
                    "unobserved transport has no response/failure owner");
        }
    }

    private static ProviderTransportAttempt.CancellationReason transportCancellationReason(
            ProviderResponseCapture.Abandonment abandonment) {
        return switch (abandonment) {
            case OPERATOR_CANCELLED ->
                    ProviderTransportAttempt.CancellationReason.OPERATOR_CANCELLED;
            case WALL_CLOCK_TIMEOUT ->
                    ProviderTransportAttempt.CancellationReason.WALL_CLOCK_TIMEOUT;
            case IDLE_TIMEOUT -> ProviderTransportAttempt.CancellationReason.IDLE_TIMEOUT;
            case REPETITION_LOOP ->
                    ProviderTransportAttempt.CancellationReason.REPETITION_LOOP;
            case INTERRUPTION_CAUSE_UNPROVEN ->
                    ProviderTransportAttempt.CancellationReason.INTERRUPTION_CAUSE_UNPROVEN;
            case RUNTIME_STREAM_CLOSED ->
                    ProviderTransportAttempt.CancellationReason.RUNTIME_STREAM_CLOSED;
            case SCOPE_CLOSED -> ProviderTransportAttempt.CancellationReason.SCOPE_CLOSED;
        };
    }

    private static void validateResponseSettlement(
            Operation operation,
            QualificationProviderAttemptLedger.Response response) {
        if (operation.observationMode()
                != ProviderTransportAttempt.ObservationMode.OBSERVED) {
            return;
        }
        if (operation.settlement()
                        != ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL
                && operation.settlement()
                        != ProviderTransportAttempt.Settlement.COMPLETED_BODY_EOF
                && operation.settlement()
                        != ProviderTransportAttempt.Settlement.CLOSED_BEFORE_PROVIDER_TERMINAL) {
            throw new IllegalArgumentException(
                    "provider response is bound to a non-returning transport settlement");
        }
        boolean providerTerminal = operation.settlement()
                        == ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL
                && operation.providerTerminalObserved();
        switch (response.completion()) {
            case PROVIDER_EOS, PROVIDER_LENGTH, UNRECOGNIZED_PROVIDER_STOP -> {
                if (!providerTerminal) {
                    throw new IllegalArgumentException(
                            "provider terminal response lacks terminal transport settlement");
                }
            }
            case STREAM_WITHOUT_EOS -> {
                if (operation.settlement()
                                == ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL
                        || operation.providerTerminalObserved()) {
                    throw new IllegalArgumentException(
                            "stream-without-EOS response contradicts transport settlement");
                }
            }
            default -> {
                // Runtime-owned completions can conservatively accompany a
                // quarantined or otherwise abnormal provider transport.
            }
        }
    }

    private static void validateSelectedEligibility(
            Operation operation,
            QualificationProviderAttemptLedger.Generation generation,
            QualificationProviderAttemptLedger.Attempt attempt,
            QualificationProviderAttemptLedger.Response response) {
        boolean providerTerminalProof = operation.observationMode()
                        == ProviderTransportAttempt.ObservationMode.OBSERVED
                && operation.processBinding()
                        != ProviderTransportAttempt.ProcessBinding
                                .MANAGED_QUALIFICATION_INVALIDATED
                && operation.settlement()
                        == ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL
                && operation.providerTerminalObserved();
        boolean expectedTerminal = response.completedNormally() && providerTerminalProof;
        boolean expectedToolShape = switch (generation.toolAuthorityMode()) {
            case NONE -> false;
            case PROVIDER_NATIVE -> response.nativeToolCallCount() > 0;
            case PROVIDER_TEXT_JSON -> response.nativeToolCallCount() == 0
                    && response.textualToolCallPresent();
        };
        boolean expectedTool = providerTerminalProof
                && attempt.requestDispatched()
                && response.foreground()
                && response.completion() == dev.talos.spi.types.ProviderResponseCapture.Completion.PROVIDER_EOS
                && attempt.authoritySurface().bound()
                && expectedToolShape;
        if (generation.providerTerminalEligible() != expectedTerminal
                || generation.providerToolCallEligible() != expectedTool) {
            throw new IllegalArgumentException(
                    "generation eligibility contradicts transport proof");
        }
    }

    private record EventFields(
            int version,
            long sequence,
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
            boolean late,
            ProviderTransportAttempt.ProcessBinding processBinding,
            String processReceiptId) {}

    private static EventFields fields(JsonNode data, int traceSchemaVersion) {
        int version = QualificationJson.requiredInt(
                data.path("transportLedgerVersion"), "transport ledger version");
        int expectedVersion = traceSchemaVersion
                        == QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION
                ? LEDGER_VERSION
                : LEGACY_LEDGER_VERSION;
        if (version != expectedVersion) {
            throw new IllegalArgumentException(
                    "transport ledger version does not match the trace schema");
        }
        long sequence = QualificationJson.requiredLong(
                data.path("transportSequence"), "transport sequence");
        if (sequence < 1) throw new IllegalArgumentException("transport sequence must be positive");
        String generationId = id(data, "generationId");
        String attemptId = id(data, "attemptId");
        int attemptOrdinal = QualificationJson.requiredInt(
                data.path("attemptOrdinal"), "transport attempt ordinal");
        String operationId = id(data, "transportOperationId");
        if (attemptOrdinal < 1
                || !attemptId.equals(generationId + "-attempt-" + attemptOrdinal)
                || !operationId.equals(attemptId + "-transport-1")) {
            throw new IllegalArgumentException("transport identity correlation is invalid");
        }
        ProviderTransportAttempt.ProcessBinding processBinding = requiredEnum(
                data, "processBinding", ProviderTransportAttempt.ProcessBinding.class);
        String processReceiptId = data.has("processReceiptId")
                ? processReceiptId(data)
                : "";
        if ((processBinding == ProviderTransportAttempt.ProcessBinding.UNAVAILABLE)
                != processReceiptId.isBlank()) {
            throw new IllegalArgumentException(
                    "transport process binding and receipt ID disagree");
        }
        if (traceSchemaVersion
                        != QualificationProviderAttemptLedger.PROCESS_TRACE_SCHEMA_VERSION
                && processBinding != ProviderTransportAttempt.ProcessBinding.UNAVAILABLE) {
            throw new IllegalArgumentException(
                    "historical transport evidence cannot claim active-process binding");
        }
        return new EventFields(
                version,
                sequence,
                generationId,
                attemptId,
                attemptOrdinal,
                operationId,
                requiredEnum(data, "transition", ProviderTransportAttempt.Transition.class),
                requiredEnum(data, "observationMode", ProviderTransportAttempt.ObservationMode.class),
                requiredEnum(data, "settlement", ProviderTransportAttempt.Settlement.class),
                requiredEnum(data, "transportFailure", ProviderTransportAttempt.Failure.class),
                requiredEnum(data, "cancellationState", ProviderTransportAttempt.CancellationState.class),
                requiredEnum(data, "cancellationReason", ProviderTransportAttempt.CancellationReason.class),
                requiredEnum(data, "wireDisposition", ProviderTransportAttempt.WireDisposition.class),
                QualificationJson.requiredBoolean(
                        data.path("quarantineRequired"), "transport quarantine requirement"),
                QualificationJson.requiredBoolean(
                        data.path("lateAfterGenerationTerminal"),
                        "late-after-generation-terminal"),
                processBinding,
                processReceiptId);
    }

    private static final class MutableOperation {
        private final String generationId;
        private final String attemptId;
        private final int attemptOrdinal;
        private final String operationId;
        private final ProviderTransportAttempt.ObservationMode observationMode;
        private ProviderTransportAttempt.ProcessBinding processBinding;
        private boolean processBindingInvalidated;
        private final String processReceiptId;
        private final int firstEventIndex;
        private long expectedSequence = 1;
        private boolean sendEntered;
        private boolean headersObserved;
        private boolean bodyOpened;
        private boolean providerTerminalObserved;
        private boolean bodyCompleted;
        private boolean cancellationRequested;
        private boolean cancellationAcknowledged;
        private int cancellationRequestEventIndex = -1;
        private ProviderTransportAttempt.CancellationState observedCancellationState =
                ProviderTransportAttempt.CancellationState.NOT_REQUESTED;
        private ProviderTransportAttempt.CancellationReason observedCancellationReason =
                ProviderTransportAttempt.CancellationReason.NONE;
        private boolean late;
        private int firstLateEventIndex = -1;
        private int lastNonLateEventIndex = -1;
        private ProviderTransportAttempt.Settlement settlement =
                ProviderTransportAttempt.Settlement.NONE;
        private ProviderTransportAttempt.Failure failure = ProviderTransportAttempt.Failure.NONE;
        private ProviderTransportAttempt.CancellationState cancellationState =
                ProviderTransportAttempt.CancellationState.NOT_REQUESTED;
        private ProviderTransportAttempt.CancellationReason cancellationReason =
                ProviderTransportAttempt.CancellationReason.NONE;
        private ProviderTransportAttempt.WireDisposition wireDisposition;
        private boolean quarantineRequired;
        private int settlementEventIndex = -1;

        private MutableOperation(EventFields first, int firstEventIndex) {
            generationId = first.generationId();
            attemptId = first.attemptId();
            attemptOrdinal = first.attemptOrdinal();
            operationId = first.transportOperationId();
            observationMode = first.observationMode();
            processBinding = first.processBinding();
            if (processBinding
                    == ProviderTransportAttempt.ProcessBinding
                            .MANAGED_QUALIFICATION_INVALIDATED) {
                throw new IllegalArgumentException(
                        "transport handoff cannot begin with an invalidated process binding");
            }
            processReceiptId = first.processReceiptId();
            this.firstEventIndex = firstEventIndex;
            wireDisposition = observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                    ? ProviderTransportAttempt.WireDisposition.SEND_API_NOT_ENTERED
                    : ProviderTransportAttempt.WireDisposition.UNKNOWN;
        }

        private void accept(EventFields event, int eventIndex) {
            requireIdentity(event);
            if (settlementEventIndex >= 0) {
                throw new IllegalArgumentException("transport event follows terminal settlement");
            }
            if (bodyCompleted
                    && event.transition() != ProviderTransportAttempt.Transition.SETTLED) {
                throw new IllegalArgumentException(
                        "transport body completion is not followed by settlement");
            }
            if (cancellationAcknowledged
                    && event.transition() != ProviderTransportAttempt.Transition.SETTLED) {
                throw new IllegalArgumentException(
                        "transport cancellation acknowledgement is not followed by settlement");
            }
            if (event.sequence() != expectedSequence++) {
                throw new IllegalArgumentException("transport sequence is not contiguous");
            }
            if (event.sequence() == 1
                    && event.transition() != ProviderTransportAttempt.Transition.HANDOFF_ADMITTED) {
                throw new IllegalArgumentException("transport lifecycle does not begin with handoff");
            }
            if (event.sequence() == 1 && event.late()) {
                throw new IllegalArgumentException("transport handoff cannot be late");
            }
            if (event.late() != late && late) {
                throw new IllegalArgumentException("late transport state is not monotonic");
            }
            late = event.late();
            if (late && firstLateEventIndex < 0) firstLateEventIndex = eventIndex;
            if (!late) lastNonLateEventIndex = eventIndex;

            switch (event.transition()) {
                case HANDOFF_ADMITTED -> require(
                        event.sequence() == 1 && !sendEntered,
                        "duplicate transport handoff");
                case SEND_API_ENTERED -> {
                    require(observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                    && !sendEntered && !cancellationRequested,
                            "invalid transport send entry");
                    sendEntered = true;
                    wireDisposition = ProviderTransportAttempt.WireDisposition.UNKNOWN;
                }
                case RESPONSE_HEADERS_OBSERVED -> {
                    require(sendEntered && !headersObserved,
                            "invalid transport response headers");
                    headersObserved = true;
                }
                case RESPONSE_BODY_OPENED -> {
                    require(headersObserved && !bodyOpened && !bodyCompleted
                                    && !providerTerminalObserved,
                            "invalid transport body open");
                    bodyOpened = true;
                }
                case PROVIDER_TERMINAL_OBSERVED -> {
                    require(bodyOpened && !providerTerminalObserved && !bodyCompleted,
                            "invalid provider terminal observation");
                    providerTerminalObserved = true;
                }
                case RESPONSE_BODY_COMPLETED -> {
                    require(bodyOpened && !bodyCompleted,
                            "invalid transport body completion");
                    bodyCompleted = true;
                }
                case CANCELLATION_REQUESTED -> {
                    require(!cancellationRequested,
                            "duplicate transport cancellation request");
                    cancellationRequested = true;
                    cancellationRequestEventIndex = eventIndex;
                    observedCancellationState = event.cancellationState();
                    observedCancellationReason = event.cancellationReason();
                }
                case CANCELLATION_ACKNOWLEDGED -> {
                    require(cancellationRequested && !cancellationAcknowledged,
                            "invalid transport cancellation acknowledgement");
                    require((event.cancellationState()
                                        == ProviderTransportAttempt.CancellationState.SEND_ABORT_ACKNOWLEDGED
                                    && sendEntered && !bodyOpened)
                                    || (event.cancellationState()
                                        == ProviderTransportAttempt.CancellationState.BODY_CLOSE_ACKNOWLEDGED
                                    && bodyOpened),
                            "transport cancellation acknowledgement lacks its handle");
                    cancellationAcknowledged = true;
                    observedCancellationState = event.cancellationState();
                }
                case PROCESS_BINDING_INVALIDATED -> {
                    require(processBinding
                                    == ProviderTransportAttempt.ProcessBinding
                                            .MANAGED_QUALIFICATION_INVALIDATED
                                    && !processBindingInvalidated,
                            "transport process binding invalidation was not retained");
                    processBindingInvalidated = true;
                }
                case SETTLED -> {
                    require(event.settlement() != ProviderTransportAttempt.Settlement.NONE,
                            "transport settlement is NONE");
                    validateSettlement(event);
                    settlement = event.settlement();
                    failure = event.failure();
                    cancellationState = event.cancellationState();
                    cancellationReason = event.cancellationReason();
                    wireDisposition = event.wireDisposition();
                    quarantineRequired = event.quarantineRequired();
                    settlementEventIndex = eventIndex;
                }
            }
            validateSnapshot(event);
        }

        private void requireIdentity(EventFields event) {
            boolean exactInvalidation = event.transition()
                            == ProviderTransportAttempt.Transition.PROCESS_BINDING_INVALIDATED
                    && processBinding
                            == ProviderTransportAttempt.ProcessBinding.MANAGED_QUALIFICATION_EXACT
                    && event.processBinding()
                            == ProviderTransportAttempt.ProcessBinding
                                    .MANAGED_QUALIFICATION_INVALIDATED;
            if (!generationId.equals(event.generationId())
                    || !attemptId.equals(event.attemptId())
                    || attemptOrdinal != event.attemptOrdinal()
                    || !operationId.equals(event.transportOperationId())
                    || observationMode != event.observationMode()
                    || (processBinding != event.processBinding() && !exactInvalidation)
                    || !processReceiptId.equals(event.processReceiptId())) {
                throw new IllegalArgumentException(
                        "transport operation identity changes across events");
            }
            if (exactInvalidation) processBinding = event.processBinding();
        }

        private void validateSnapshot(EventFields event) {
            if (event.transition() != ProviderTransportAttempt.Transition.SETTLED) {
                require(event.settlement() == ProviderTransportAttempt.Settlement.NONE
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && !event.quarantineRequired(),
                        "nonterminal transport event carries terminal state");
            }
            ProviderTransportAttempt.WireDisposition expectedWire = sendEntered
                    ? ProviderTransportAttempt.WireDisposition.UNKNOWN
                    : observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                            ? ProviderTransportAttempt.WireDisposition.SEND_API_NOT_ENTERED
                            : ProviderTransportAttempt.WireDisposition.UNKNOWN;
            require(event.wireDisposition() == expectedWire,
                    "transport wire disposition contradicts send state");
            if (!cancellationRequested) {
                require(event.cancellationReason()
                                == ProviderTransportAttempt.CancellationReason.NONE
                                && event.cancellationState()
                                == ProviderTransportAttempt.CancellationState.NOT_REQUESTED,
                        "transport cancellation state appears before request");
            } else {
                require(observedCancellationReason
                                != ProviderTransportAttempt.CancellationReason.NONE
                                && event.cancellationReason() == observedCancellationReason,
                        "transport cancellation reason is absent or changes");
                if (event.transition() == ProviderTransportAttempt.Transition.CANCELLATION_REQUESTED) {
                    require(event.cancellationState()
                                    == ProviderTransportAttempt.CancellationState.REQUESTED,
                            "cancellation request state is not REQUESTED");
                }
                if (event.transition()
                        == ProviderTransportAttempt.Transition.CANCELLATION_ACKNOWLEDGED) {
                    require(event.cancellationState()
                                    == ProviderTransportAttempt.CancellationState.SEND_ABORT_ACKNOWLEDGED
                                    || event.cancellationState()
                                    == ProviderTransportAttempt.CancellationState.BODY_CLOSE_ACKNOWLEDGED,
                            "cancellation acknowledgement state is invalid");
                } else if (event.transition() == ProviderTransportAttempt.Transition.SETTLED) {
                    ProviderTransportAttempt.CancellationState expected = switch (
                            event.settlement()) {
                        case CANCELLED_BEFORE_SEND ->
                                ProviderTransportAttempt.CancellationState.PRE_SEND_PREVENTED;
                        case QUARANTINE_REQUIRED ->
                                ProviderTransportAttempt.CancellationState.UNACKNOWLEDGED;
                        default -> observedCancellationState;
                    };
                    require(event.cancellationState() == expected,
                            "transport settlement changes cancellation state");
                } else {
                    require(event.cancellationState() == observedCancellationState,
                            "transport event changes cancellation state");
                }
            }
        }

        private void validateSettlement(EventFields event) {
            if (bodyCompleted) {
                ProviderTransportAttempt.Settlement expected = providerTerminalObserved
                        ? ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL
                        : ProviderTransportAttempt.Settlement.COMPLETED_BODY_EOF;
                require(event.settlement() == expected,
                        "transport body completion selects a contradictory settlement");
            }
            if (cancellationAcknowledged) validateAcknowledgedSettlement(event);
            switch (event.settlement()) {
                case NONE -> throw new IllegalArgumentException("transport settlement is NONE");
                case COMPLETED_PROVIDER_TERMINAL -> require(
                        providerTerminalObserved
                                && (bodyCompleted || bodyOpened)
                                && (!cancellationRequested
                                    || bodyCompleted
                                    || cancellationAcknowledged)
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && !event.quarantineRequired(),
                        "provider-terminal settlement lacks terminal/body evidence");
                case COMPLETED_BODY_EOF -> require(
                        bodyCompleted && !providerTerminalObserved
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && !event.quarantineRequired(),
                        "body-EOF settlement contradicts terminal evidence");
                case CANCELLED_BEFORE_SEND -> require(
                        observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                && cancellationRequested && !sendEntered
                                && !headersObserved && !bodyOpened && !bodyCompleted
                                && event.cancellationState()
                                == ProviderTransportAttempt.CancellationState.PRE_SEND_PREVENTED
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && !event.quarantineRequired(),
                        "pre-send cancellation settlement is invalid");
                case CANCELLED_IN_FLIGHT -> require(
                        observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                && cancellationRequested && sendEntered
                                && cancellationAcknowledged && !bodyCompleted,
                        "in-flight cancellation settlement lacks acknowledgement");
                case FAILED_BEFORE_HEADERS -> {
                    require(observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                    && !headersObserved && !bodyOpened && !bodyCompleted
                                    && !cancellationAcknowledged,
                            "pre-header failure settlement is invalid");
                    if (!sendEntered) {
                        require(!cancellationRequested
                                        && !event.late()
                                        && event.failure()
                                            == ProviderTransportAttempt.Failure.RUNTIME
                                        && !event.quarantineRequired(),
                                "pre-send attempt failure is not exact");
                    } else {
                        require(event.failure() != ProviderTransportAttempt.Failure.NONE
                                        && event.failure()
                                            != ProviderTransportAttempt.Failure.BODY_IO
                                        && event.quarantineRequired()
                                        && (!cancellationRequested
                                            || !isCancellationFailure(event.failure())),
                                "post-send pre-header failure is not exact");
                    }
                }
                case FAILED_DURING_BODY -> require(
                        observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                && sendEntered && headersObserved
                                && !bodyCompleted && !cancellationAcknowledged
                                && event.failure() != ProviderTransportAttempt.Failure.NONE
                                && (!cancellationRequested
                                    || !isCancellationFailure(event.failure()))
                                && (bodyOpened
                                    ? event.quarantineRequired() == !providerTerminalObserved
                                    : event.quarantineRequired()
                                        && !providerTerminalObserved),
                        "body failure settlement is invalid");
                case CLOSED_BEFORE_PROVIDER_TERMINAL -> require(
                        observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                && bodyOpened && !bodyCompleted
                                && !providerTerminalObserved && !cancellationRequested
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && event.quarantineRequired(),
                        "early-close settlement is invalid");
                case LIFECYCLE_UNOBSERVED -> require(
                        observationMode == ProviderTransportAttempt.ObservationMode.LEGACY_UNOBSERVED
                                && !sendEntered && !cancellationRequested
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && !event.quarantineRequired(),
                        "unobserved lifecycle settlement is invalid");
                case QUARANTINE_REQUIRED -> {
                    // Quarantine is the explicit bounded terminal fact for an
                    // operation whose local owner could not observe closure.
                    require((observationMode == ProviderTransportAttempt.ObservationMode.OBSERVED
                                    && event.failure() == ProviderTransportAttempt.Failure.UNKNOWN
                                    && (!cancellationRequested || sendEntered))
                                    || (observationMode
                                            == ProviderTransportAttempt.ObservationMode.LEGACY_UNOBSERVED
                                        && cancellationRequested
                                        && event.failure() == ProviderTransportAttempt.Failure.NONE),
                            "transport quarantine carries impossible failure state");
                    require(!bodyCompleted && event.quarantineRequired(),
                            "transport quarantine omits its exact owner decision");
                }
            }
        }

        private void validateAcknowledgedSettlement(EventFields event) {
            if (event.cancellationState()
                    == ProviderTransportAttempt.CancellationState.SEND_ABORT_ACKNOWLEDGED) {
                require(event.settlement()
                                == ProviderTransportAttempt.Settlement.CANCELLED_IN_FLIGHT
                                && (event.failure()
                                        == ProviderTransportAttempt.Failure.INTERRUPTION
                                    || event.failure()
                                        == ProviderTransportAttempt.Failure.CANCELLATION
                                    || (event.failure()
                                            == ProviderTransportAttempt.Failure.BODY_IO
                                        && headersObserved))
                                && event.quarantineRequired(),
                        "send-abort acknowledgement selects an impossible settlement");
                return;
            }
            require(event.cancellationState()
                            == ProviderTransportAttempt.CancellationState.BODY_CLOSE_ACKNOWLEDGED,
                    "transport cancellation acknowledgement state is invalid");
            if (event.settlement()
                    == ProviderTransportAttempt.Settlement.COMPLETED_PROVIDER_TERMINAL) {
                require(providerTerminalObserved
                                && event.failure() == ProviderTransportAttempt.Failure.NONE
                                && !event.quarantineRequired(),
                        "terminal body close carries an impossible settlement");
                return;
            }
            require(event.settlement()
                            == ProviderTransportAttempt.Settlement.CANCELLED_IN_FLIGHT
                            && (event.failure() == ProviderTransportAttempt.Failure.NONE
                                || isCancellationFailure(event.failure()))
                            && event.quarantineRequired() == !providerTerminalObserved
                            && (event.failure() != ProviderTransportAttempt.Failure.NONE
                                || !providerTerminalObserved),
                    "body-close acknowledgement selects an impossible settlement");
        }

        private static boolean isCancellationFailure(
                ProviderTransportAttempt.Failure failure) {
            return failure == ProviderTransportAttempt.Failure.INTERRUPTION
                    || failure == ProviderTransportAttempt.Failure.CANCELLATION
                    || failure == ProviderTransportAttempt.Failure.BODY_IO;
        }

        private Operation freeze() {
            if (expectedSequence == 1 || settlementEventIndex < 0) {
                throw new IllegalArgumentException("provider transport operation is not settled");
            }
            return new Operation(
                    generationId, attemptId, attemptOrdinal, operationId,
                    observationMode, settlement, failure, cancellationState,
                    cancellationReason, wireDisposition, processBinding,
                    processReceiptId,
                    providerTerminalObserved, bodyCompleted, quarantineRequired, late,
                    firstEventIndex, firstLateEventIndex, lastNonLateEventIndex,
                    cancellationRequestEventIndex,
                    settlementEventIndex);
        }
    }

    private static String id(JsonNode data, String key) {
        String value = QualificationJson.requiredText(data.path(key), "transport " + key);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,511}")) {
            throw new IllegalArgumentException("transport " + key + " is invalid");
        }
        return value;
    }

    private static String processReceiptId(JsonNode data) {
        String value = QualificationJson.requiredText(
                data.path("processReceiptId"), "transport process receipt ID");
        if (!value.matches("pr-[0-9a-f]{64}")) {
            throw new IllegalArgumentException("transport process receipt ID is invalid");
        }
        return value;
    }

    private static Set<String> union(Set<String> first, Set<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>(first);
        values.addAll(second);
        return Set.copyOf(values);
    }

    private static <E extends Enum<E>> E requiredEnum(
            JsonNode data,
            String key,
            Class<E> type) {
        return QualificationJson.requiredEnum(data.path(key), type, "transport " + key);
    }

    private static void requireExactKeys(JsonNode object, Set<String> expected, String label) {
        if (object == null || !object.isObject()) {
            throw new IllegalArgumentException(label + " data must be an object");
        }
        Set<String> actual = new LinkedHashSet<>();
        object.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) {
            throw new IllegalArgumentException(
                    label + " data keys differ; expected " + expected + ", found " + actual);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
