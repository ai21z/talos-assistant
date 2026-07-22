package dev.talos.qualification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationProviderTransportLedgerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void validObservedTransportIsJoinedToTheExactSelectedResponse() {
        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(validTrace());

        assertTrue(ledger.transportLifecycleComplete());
        assertFalse(ledger.transportQuarantinePresent());
        assertFalse(ledger.transportUnobservedPresent());
        assertFalse(ledger.activeProcessBindingComplete());
        assertEquals("trc-transport-generation-1-attempt-1-transport-1",
                ledger.generations().getFirst().selectedResponse().transportOperationId());
    }

    @Test
    void missingEntireLifecycleRemainsReadableButExplicitlyIncomplete() {
        ObjectNode trace = validTrace();
        removeTransportEvents(trace);
        removeTransportBindings(trace);

        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(trace);

        assertTrue(ledger.dispatchedAttemptPresent());
        assertFalse(ledger.transportLifecycleComplete());
    }

    @Test
    void startedButUnsettledTransportIsInvalid() {
        ObjectNode trace = validTrace();
        removeTransportTransition(trace, "SETTLED");

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(trace));
    }

    @Test
    void duplicateOrReorderedTransportSequenceIsInvalid() {
        ObjectNode trace = validTrace();
        transportEvent(trace, "RESPONSE_HEADERS_OBSERVED")
                .put("transportSequence", 2);

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(trace));
    }

    @Test
    void unknownTransportKeysAndStringBooleansAreInvalid() {
        ObjectNode extra = validTrace();
        transportEvent(extra, "SEND_API_ENTERED").put("rawUri", "http://secret");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(extra));

        ObjectNode stringBoolean = validTrace();
        transportEvent(stringBoolean, "SETTLED")
                .put("lateAfterGenerationTerminal", "false");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(stringBoolean));
    }

    @Test
    void quarantineDecisionIsRequiredTypedAndExact() {
        ObjectNode missing = validTrace();
        transportEvent(missing, "SETTLED").remove("quarantineRequired");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(missing));

        ObjectNode stringBoolean = validTrace();
        transportEvent(stringBoolean, "SETTLED").put("quarantineRequired", "false");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(stringBoolean));

        ObjectNode flipped = validTrace();
        transportEvent(flipped, "SETTLED").put("quarantineRequired", true);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(flipped));
    }

    @Test
    void responseCannotDropOrSubstituteItsTransportIdentity() {
        ObjectNode missing = validTrace();
        data(missing, "GENERATION_RESPONSE_CAPTURED").remove("transportOperationId");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(missing));

        ObjectNode substituted = validTrace();
        data(substituted, "GENERATION_COMPLETED")
                .put("transportOperationId", "trc-transport-generation-1-attempt-9-transport-1");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(substituted));
    }

    @Test
    void transportCannotPrecedeDispatchOrMisstateTerminalOrdering() {
        ObjectNode beforeDispatch = validTrace();
        ArrayNode events = (ArrayNode) beforeDispatch.path("events");
        int handoffIndex = transportEventIndex(beforeDispatch, "HANDOFF_ADMITTED");
        JsonNode handoff = events.remove(handoffIndex);
        events.insert(eventIndex(beforeDispatch, "GENERATION_REQUEST_DISPATCHED"), handoff);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(beforeDispatch));

        ObjectNode falseLate = validTrace();
        transportEvent(falseLate, "SETTLED").put("lateAfterGenerationTerminal", true);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(falseLate));
    }

    @Test
    void providerTerminalCannotBeManufacturedAfterBodyEof() {
        ObjectNode trace = validTrace();
        ObjectNode terminal = transportEvent(trace, "PROVIDER_TERMINAL_OBSERVED");
        ObjectNode bodyCompleted = transportEvent(trace, "RESPONSE_BODY_COMPLETED");
        terminal.put("transition", "RESPONSE_BODY_COMPLETED");
        bodyCompleted.put("transition", "PROVIDER_TERMINAL_OBSERVED");

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(trace));
    }

    @Test
    void bodyCompletionSelectsExactlyOneSettlement() {
        ObjectNode terminalAsBodyEof = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(5, "PROVIDER_TERMINAL_OBSERVED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(6, "RESPONSE_BODY_COMPLETED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(7, "SETTLED", "OBSERVED", "COMPLETED_BODY_EOF", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(terminalAsBodyEof));

        ObjectNode bodyEofAsTerminal = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(5, "RESPONSE_BODY_COMPLETED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(6, "SETTLED", "OBSERVED", "COMPLETED_PROVIDER_TERMINAL", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(bodyEofAsTerminal));
    }

    @Test
    void normalProviderResponseCannotBindToBodyEofOrFailedSettlement() {
        ObjectNode eof = validTrace();
        removeTransportTransition(eof, "PROVIDER_TERMINAL_OBSERVED");
        transportEvent(eof, "RESPONSE_BODY_COMPLETED").put("transportSequence", 5);
        ObjectNode eofSettlement = transportEvent(eof, "SETTLED");
        eofSettlement.put("transportSequence", 6);
        eofSettlement.put("settlement", "COMPLETED_BODY_EOF");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(eof));

        ObjectNode failed = validTrace();
        transportEvent(failed, "SETTLED").put("transportFailure", "BODY_IO");
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(failed));

        ObjectNode cancelled = validTrace();
        ObjectNode cancellation = transportEvent(cancelled, "PROVIDER_TERMINAL_OBSERVED");
        cancellation.put("transition", "CANCELLATION_REQUESTED");
        cancellation.put("cancellationState", "REQUESTED");
        cancellation.put("cancellationReason", "SCOPE_CLOSED");
        ObjectNode acknowledgement = transportEvent(cancelled, "RESPONSE_BODY_COMPLETED");
        acknowledgement.put("transition", "CANCELLATION_ACKNOWLEDGED");
        acknowledgement.put("cancellationState", "SEND_ABORT_ACKNOWLEDGED");
        acknowledgement.put("cancellationReason", "SCOPE_CLOSED");
        ObjectNode cancelledSettlement = transportEvent(cancelled, "SETTLED");
        cancelledSettlement.put("settlement", "CANCELLED_IN_FLIGHT");
        cancelledSettlement.put("transportFailure", "INTERRUPTION");
        cancelledSettlement.put("cancellationState", "SEND_ABORT_ACKNOWLEDGED");
        cancelledSettlement.put("cancellationReason", "SCOPE_CLOSED");
        cancelledSettlement.put("quarantineRequired", true);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(cancelled));
    }

    @Test
    void terminalSnapshotCannotInventCancellationState() {
        ObjectNode trace = validTrace();
        transportEvent(trace, "SETTLED").put("cancellationState", "REQUESTED");

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(trace));
    }

    @Test
    void cancellationAndQuarantineSettlementsRejectImpossibleFailureDomains() {
        ObjectNode preHeaders = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "BODY_IO",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(preHeaders));

        ObjectNode beforeSend = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "SEND_API_NOT_ENTERED", false),
                transport(3, "SETTLED", "OBSERVED", "CANCELLED_BEFORE_SEND", "BODY_IO",
                        "PRE_SEND_PREVENTED", "OPERATOR_CANCELLED",
                        "SEND_API_NOT_ENTERED", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(beforeSend));

        ObjectNode inFlight = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(4, "CANCELLATION_ACKNOWLEDGED", "OBSERVED", "NONE", "NONE",
                        "SEND_ABORT_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(5, "SETTLED", "OBSERVED", "CANCELLED_IN_FLIGHT", "CONNECTION",
                        "SEND_ABORT_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(inFlight));

        ObjectNode quarantine = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "SEND_API_NOT_ENTERED", false),
                transport(3, "SETTLED", "OBSERVED", "QUARANTINE_REQUIRED", "BODY_IO",
                        "UNACKNOWLEDGED", "OPERATOR_CANCELLED",
                        "SEND_API_NOT_ENTERED", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(quarantine));

        ObjectNode preSendCancellationQuarantine = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "SEND_API_NOT_ENTERED", false),
                transport(3, "SETTLED", "OBSERVED", "QUARANTINE_REQUIRED", "UNKNOWN",
                        "UNACKNOWLEDGED", "OPERATOR_CANCELLED",
                        "SEND_API_NOT_ENTERED", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(
                        preSendCancellationQuarantine));
    }

    @Test
    void pendingCancellationDivertsOnlyCancellationFailureDomains() {
        ObjectNode interruptedBeforeHeaders = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(4, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "INTERRUPTION",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(interruptedBeforeHeaders));

        ObjectNode bodyFailure = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(5, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(6, "SETTLED", "OBSERVED", "FAILED_DURING_BODY", "BODY_IO",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(bodyFailure));

        ObjectNode independentRuntimeFailure = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(4, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "RUNTIME",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        QualificationProviderTransportLedger.parse(independentRuntimeFailure);
    }

    @Test
    void noSendFailureCannotReplaceOrFollowPreSendCancellation() {
        ObjectNode pendingCancellation = abandonedFullTrace(
                List.of(transport(
                        1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false)),
                List.of(
                        transport(
                                2, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                                "REQUESTED", "SCOPE_CLOSED", "SEND_API_NOT_ENTERED", true),
                        transport(
                                3, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "RUNTIME",
                                "REQUESTED", "SCOPE_CLOSED", "SEND_API_NOT_ENTERED", true)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(pendingCancellation));

        ObjectNode lateWithoutCancellation = abandonedFullTrace(
                List.of(transport(
                        1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false)),
                List.of(transport(
                        2, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "RUNTIME",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", true)));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(lateWithoutCancellation));

        ObjectNode abandonedAfterNoSendFailure = abandonedFullTrace(
                List.of(
                        transport(
                                1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                        transport(
                                2, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "RUNTIME",
                                "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false)),
                List.of());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(
                        abandonedAfterNoSendFailure));
    }

    @Test
    void pendingCancellationNeedsBodyCompletionOrAcknowledgementForTerminalSuccess() {
        ObjectNode trace = abandonedFullTrace(
                List.of(
                        transport(
                                1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                        transport(
                                2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                        transport(
                                3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                        transport(
                                4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                        transport(
                                5, "PROVIDER_TERMINAL_OBSERVED", "OBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "UNKNOWN", false)),
                List.of(
                        transport(
                                6, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                                "REQUESTED", "SCOPE_CLOSED", "UNKNOWN", true),
                        transport(
                                7, "SETTLED", "OBSERVED",
                                "COMPLETED_PROVIDER_TERMINAL", "NONE",
                                "REQUESTED", "SCOPE_CLOSED", "UNKNOWN", true)));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(trace));
    }

    @Test
    void unobservedSettlementCannotCloseAnAbandonedAttempt() {
        ObjectNode unobserved = abandonedFullTrace(
                List.of(
                        transport(
                                1, "HANDOFF_ADMITTED", "LEGACY_UNOBSERVED", "NONE", "NONE",
                                "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                        transport(
                                2, "SETTLED", "LEGACY_UNOBSERVED",
                                "LIFECYCLE_UNOBSERVED", "NONE",
                                "NOT_REQUESTED", "NONE", "UNKNOWN", false)),
                List.of());
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(unobserved));
    }

    @Test
    void generationAbortMayWinAfterResponseOrFailureAttemptAlreadyClosed() {
        ObjectNode responseRace = validTrace();
        replaceGenerationTerminalWithAbandonment(
                responseRace, "GENERATION_COMPLETED");
        QualificationProviderAttemptLedger.parse(responseRace);

        ObjectNode failureRace = failedTrace(true);
        replaceGenerationTerminalWithAbandonment(
                failureRace, "GENERATION_FAILED");
        QualificationProviderAttemptLedger.parse(failureRace);
    }

    @Test
    void generationFailureMayFollowResponseOrAbandonedAttemptAlreadyClosed() {
        ObjectNode responseRace = validTrace();
        replaceGenerationTerminalWithFailure(
                responseRace, "GENERATION_COMPLETED", "RUNTIME_EXCEPTION");
        QualificationProviderAttemptLedger.parse(responseRace);

        ObjectNode abandonmentRace = validTrace();
        removeTransportEvents(abandonmentRace);
        ArrayNode events = (ArrayNode) abandonmentRace.path("events");
        events.remove(eventIndex(abandonmentRace, "GENERATION_REQUEST_DISPATCHED"));
        renumberProviderLedger(abandonmentRace);
        ObjectNode attemptTerminal = (ObjectNode) events.get(
                eventIndex(abandonmentRace, "GENERATION_RESPONSE_CAPTURED"));
        attemptTerminal.put("type", "GENERATION_ATTEMPT_ABANDONED");
        ObjectNode attemptData = (ObjectNode) attemptTerminal.path("data");
        retainProviderAttemptKeys(attemptData);
        attemptData.put("requestDispatched", false);
        attemptData.put("abandonment", "SCOPE_CLOSED");
        replaceGenerationTerminalWithFailure(
                abandonmentRace, "GENERATION_COMPLETED", "RUNTIME_EXCEPTION");
        QualificationProviderAttemptLedger.parse(abandonmentRace);
    }

    @Test
    void cancellationSettlementQuarantinesOnlyBeforeProviderTerminalProof() {
        ObjectNode beforeTerminal = cancelledInFlightTrace(false);
        assertTrue(QualificationProviderTransportLedger.parse(beforeTerminal)
                .quarantinePresent());

        ObjectNode afterTerminal = cancelledInFlightTrace(true);
        assertFalse(QualificationProviderTransportLedger.parse(afterTerminal)
                .quarantinePresent());
    }

    @Test
    void cancellationAcknowledgementMustSettleImmediatelyWithItsExactFailureDomain() {
        ObjectNode delayedSettlement = cancelledInFlightTrace(false);
        ArrayNode delayedEvents = (ArrayNode) delayedSettlement.path("events");
        int settlementIndex = delayedEvents.size() - 1;
        delayedEvents.insert(settlementIndex, event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                7, "RESPONSE_BODY_COMPLETED", "OBSERVED", "NONE", "NONE",
                "BODY_CLOSE_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false)));
        transportEvent(delayedSettlement, "SETTLED").put("transportSequence", 8);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(delayedSettlement));

        ObjectNode sendAbortWithoutFailure = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(4, "CANCELLATION_ACKNOWLEDGED", "OBSERVED", "NONE", "NONE",
                        "SEND_ABORT_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(5, "SETTLED", "OBSERVED", "CANCELLED_IN_FLIGHT", "NONE",
                        "SEND_ABORT_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(sendAbortWithoutFailure));

        ObjectNode sendAbortAfterBodyOpen = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(5, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(6, "CANCELLATION_ACKNOWLEDGED", "OBSERVED", "NONE", "NONE",
                        "SEND_ABORT_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false),
                transport(7, "SETTLED", "OBSERVED", "CANCELLED_IN_FLIGHT", "BODY_IO",
                        "SEND_ABORT_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(sendAbortAfterBodyOpen));
    }

    @Test
    void lateLifecycleSuffixMustFollowGenerationAbandonment() {
        QualificationProviderAttemptLedger.parse(abandonedTrace(true));

        ObjectNode prematureLate = abandonedTrace(false);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(prematureLate));

        ObjectNode terminalGap = abandonedTrace(false);
        transportEvent(terminalGap, "CANCELLATION_REQUESTED")
                .put("lateAfterGenerationTerminal", false);
        transportEvent(terminalGap, "SETTLED")
                .put("lateAfterGenerationTerminal", false);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(terminalGap));
    }

    @Test
    void cancellationReasonMustMatchItsAttemptAbandonment() {
        ObjectNode mismatch = abandonedTrace(true);
        transportEvent(mismatch, "CANCELLATION_REQUESTED")
                .put("cancellationReason", "IDLE_TIMEOUT");
        transportEvent(mismatch, "SETTLED")
                .put("cancellationReason", "IDLE_TIMEOUT");

        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(mismatch));
    }

    @Test
    void failedAttemptCannotPrecedeTransportSettlement() {
        QualificationProviderAttemptLedger.parse(failedTrace(true));

        ObjectNode settlementAfterFailure = failedTrace(false);
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(settlementAfterFailure));
    }

    @Test
    void legacyEarlyCloseAndQuarantineStatesMustMatchRuntimeEmittablePaths() {
        ObjectNode lateHandoff = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", true),
                transport(2, "SETTLED", "OBSERVED", "FAILED_BEFORE_HEADERS", "RUNTIME",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", true));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(lateHandoff));

        ObjectNode cancelledLegacyUnobserved = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "LEGACY_UNOBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(2, "CANCELLATION_REQUESTED", "LEGACY_UNOBSERVED", "NONE", "NONE",
                        "REQUESTED", "SCOPE_CLOSED", "UNKNOWN", false),
                transport(3, "SETTLED", "LEGACY_UNOBSERVED", "LIFECYCLE_UNOBSERVED", "NONE",
                        "REQUESTED", "SCOPE_CLOSED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(cancelledLegacyUnobserved));

        ObjectNode cancelledEarlyClose = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(5, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                        "REQUESTED", "SCOPE_CLOSED", "UNKNOWN", false),
                transport(6, "SETTLED", "OBSERVED", "CLOSED_BEFORE_PROVIDER_TERMINAL", "NONE",
                        "REQUESTED", "SCOPE_CLOSED", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(cancelledEarlyClose));

        ObjectNode observedQuarantineWithoutTypedFailure = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false),
                transport(2, "SETTLED", "OBSERVED", "QUARANTINE_REQUIRED", "NONE",
                        "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(
                        observedQuarantineWithoutTypedFailure));

        ObjectNode legacyQuarantineWithoutCancellation = transportOnlyTrace(
                transport(1, "HANDOFF_ADMITTED", "LEGACY_UNOBSERVED", "NONE", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false),
                transport(2, "SETTLED", "LEGACY_UNOBSERVED", "QUARANTINE_REQUIRED", "NONE",
                        "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationProviderTransportLedger.parse(
                        legacyQuarantineWithoutCancellation));
    }

    @Test
    void legacyUnobservedLifecycleCanNeverBecomeCompleteTransportEvidence() {
        ObjectNode trace = validTrace();
        removeTransportEvents(trace);
        ArrayNode events = (ArrayNode) trace.path("events");
        int responseIndex = eventIndex(trace, "GENERATION_RESPONSE_CAPTURED");
        events.insert(responseIndex, event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                1,
                "HANDOFF_ADMITTED",
                "LEGACY_UNOBSERVED",
                "NONE",
                "NONE",
                "NOT_REQUESTED",
                "NONE",
                "UNKNOWN",
                false)));
        events.insert(responseIndex + 1, event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                2,
                "SETTLED",
                "LEGACY_UNOBSERVED",
                "LIFECYCLE_UNOBSERVED",
                "NONE",
                "NOT_REQUESTED",
                "NONE",
                "UNKNOWN",
                false)));
        data(trace, "GENERATION_COMPLETED").put("providerTerminalEligible", false);

        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(trace);

        assertFalse(ledger.transportLifecycleComplete());
        assertTrue(ledger.transportUnobservedPresent());
    }

    private static ObjectNode validTrace() {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION);
        ArrayNode events = root.putArray("events");
        events.add(event("GENERATION_STARTED", generation(1)));
        events.add(event("GENERATION_ATTEMPT_STARTED", attempt(2, false)));

        Map<String, Object> dispatch = attempt(3, true);
        dispatch.put("providerBodyHash", "sha256:" + "1".repeat(64));
        dispatch.put("dispatchedNativeTools", List.of());
        dispatch.put("toolAuthoritySurface", Map.of(
                "schemaVersion", 1,
                "bound", false,
                "toolChoiceMode", "NONE",
                "namedToolNameHash", "sha256:e3b0c44298fc1c149afbf4c8996fb924"
                        + "27ae41e4649b934ca495991b7852b855",
                "selectedToolNameHashes", List.of(),
                "authorizedToolNameHashes", List.of()));
        events.add(event("GENERATION_REQUEST_DISPATCHED", dispatch));

        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false)));
        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false)));
        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false)));
        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false)));
        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                5, "PROVIDER_TERMINAL_OBSERVED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false)));
        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                6, "RESPONSE_BODY_COMPLETED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false)));
        events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", transport(
                7, "SETTLED", "OBSERVED", "COMPLETED_PROVIDER_TERMINAL", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false)));

        Map<String, Object> response = attempt(4, true);
        response.put("assistantHash", "sha256:" + "2".repeat(64));
        response.put("assistantChars", 6);
        response.put("nativeToolCallCount", 0);
        response.put("capturedNativeToolProposalCount", 0);
        response.put("nativeToolProposalsTruncated", false);
        response.put("textualToolCallPresent", false);
        response.put("completion", "PROVIDER_EOS");
        response.put("providerBacked", true);
        response.put("foreground", true);
        response.put("completedNormally", true);
        response.put("normalized", false);
        response.put("truncated", false);
        response.put("transportOperationId",
                "trc-transport-generation-1-attempt-1-transport-1");
        events.add(event("GENERATION_RESPONSE_CAPTURED", response));

        Map<String, Object> completion = generation(5);
        completion.put("selectedAttemptId", "trc-transport-generation-1-attempt-1");
        completion.put("selectedAttemptOrdinal", 1);
        completion.put("completion", "PROVIDER_EOS");
        completion.put("assistantHash", "sha256:" + "2".repeat(64));
        completion.put("providerTerminalEligible", true);
        completion.put("providerToolCallEligible", false);
        completion.put("transportOperationId",
                "trc-transport-generation-1-attempt-1-transport-1");
        events.add(event("GENERATION_COMPLETED", completion));
        return root;
    }

    private static ObjectNode cancelledInFlightTrace(boolean providerTerminal) {
        List<Map<String, Object>> lifecycle = new ArrayList<>();
        lifecycle.add(transport(1, "HANDOFF_ADMITTED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "SEND_API_NOT_ENTERED", false));
        lifecycle.add(transport(2, "SEND_API_ENTERED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        lifecycle.add(transport(3, "RESPONSE_HEADERS_OBSERVED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        lifecycle.add(transport(4, "RESPONSE_BODY_OPENED", "OBSERVED", "NONE", "NONE",
                "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        long sequence = 5;
        if (providerTerminal) {
            lifecycle.add(transport(sequence++, "PROVIDER_TERMINAL_OBSERVED", "OBSERVED",
                    "NONE", "NONE", "NOT_REQUESTED", "NONE", "UNKNOWN", false));
        }
        lifecycle.add(transport(sequence++, "CANCELLATION_REQUESTED", "OBSERVED", "NONE", "NONE",
                "REQUESTED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        lifecycle.add(transport(sequence++, "CANCELLATION_ACKNOWLEDGED", "OBSERVED", "NONE", "NONE",
                "BODY_CLOSE_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false));
        Map<String, Object> settlement = transport(
                sequence, "SETTLED", "OBSERVED", "CANCELLED_IN_FLIGHT", "BODY_IO",
                "BODY_CLOSE_ACKNOWLEDGED", "OPERATOR_CANCELLED", "UNKNOWN", false);
        settlement.put("quarantineRequired", !providerTerminal);
        lifecycle.add(settlement);
        return transportOnlyTrace(lifecycle);
    }

    private static ObjectNode abandonedTrace(boolean lateAfterGenerationTerminal) {
        ObjectNode trace = validTrace();
        ArrayNode events = (ArrayNode) trace.path("events");
        ObjectNode handoff = eventAtTransition(trace, "HANDOFF_ADMITTED").deepCopy();
        ObjectNode send = eventAtTransition(trace, "SEND_API_ENTERED").deepCopy();
        ObjectNode cancellation = send.deepCopy();
        ObjectNode settled = eventAtTransition(trace, "SETTLED").deepCopy();
        removeTransportEvents(trace);

        ObjectNode attemptTerminal = (ObjectNode) events.get(
                eventIndex(trace, "GENERATION_RESPONSE_CAPTURED"));
        attemptTerminal.put("type", "GENERATION_ATTEMPT_ABANDONED");
        ObjectNode attemptData = (ObjectNode) attemptTerminal.path("data");
        retainProviderAttemptKeys(attemptData);
        attemptData.put("abandonment", "SCOPE_CLOSED");

        ObjectNode generationTerminal = (ObjectNode) events.get(
                eventIndex(trace, "GENERATION_COMPLETED"));
        generationTerminal.put("type", "GENERATION_ABANDONED");
        ObjectNode generationData = (ObjectNode) generationTerminal.path("data");
        retainProviderGenerationKeys(generationData);
        generationData.put("abandonment", "SCOPE_CLOSED");

        int dispatchIndex = eventIndex(trace, "GENERATION_REQUEST_DISPATCHED");
        events.insert(dispatchIndex + 1, handoff);
        events.insert(dispatchIndex + 2, send);

        ObjectNode cancellationData = (ObjectNode) cancellation.path("data");
        cancellationData.put("transportSequence", 3);
        cancellationData.put("transition", "CANCELLATION_REQUESTED");
        cancellationData.put("cancellationState", "REQUESTED");
        cancellationData.put("cancellationReason", "SCOPE_CLOSED");
        cancellationData.put("lateAfterGenerationTerminal", true);

        ObjectNode settledData = (ObjectNode) settled.path("data");
        settledData.put("transportSequence", 4);
        settledData.put("settlement", "QUARANTINE_REQUIRED");
        settledData.put("transportFailure", "UNKNOWN");
        settledData.put("cancellationState", "UNACKNOWLEDGED");
        settledData.put("cancellationReason", "SCOPE_CLOSED");
        settledData.put("quarantineRequired", true);
        settledData.put("lateAfterGenerationTerminal", true);

        int insertionIndex = lateAfterGenerationTerminal
                ? eventIndex(trace, "GENERATION_ABANDONED") + 1
                : eventIndex(trace, "GENERATION_ATTEMPT_ABANDONED") + 1;
        events.insert(insertionIndex, cancellation);
        events.insert(insertionIndex + 1, settled);
        return trace;
    }

    private static ObjectNode abandonedFullTrace(
            List<Map<String, Object>> beforeTerminal,
            List<Map<String, Object>> afterTerminal) {
        ObjectNode trace = validTrace();
        ArrayNode events = (ArrayNode) trace.path("events");
        removeTransportEvents(trace);

        ObjectNode attemptTerminal = (ObjectNode) events.get(
                eventIndex(trace, "GENERATION_RESPONSE_CAPTURED"));
        attemptTerminal.put("type", "GENERATION_ATTEMPT_ABANDONED");
        ObjectNode attemptData = (ObjectNode) attemptTerminal.path("data");
        retainProviderAttemptKeys(attemptData);
        attemptData.put("abandonment", "SCOPE_CLOSED");

        ObjectNode generationTerminal = (ObjectNode) events.get(
                eventIndex(trace, "GENERATION_COMPLETED"));
        generationTerminal.put("type", "GENERATION_ABANDONED");
        ObjectNode generationData = (ObjectNode) generationTerminal.path("data");
        retainProviderGenerationKeys(generationData);
        generationData.put("abandonment", "SCOPE_CLOSED");

        int insertionIndex = eventIndex(trace, "GENERATION_REQUEST_DISPATCHED") + 1;
        for (Map<String, Object> lifecycle : beforeTerminal) {
            events.insert(insertionIndex++, event("PROVIDER_TRANSPORT_LIFECYCLE", lifecycle));
        }
        insertionIndex = eventIndex(trace, "GENERATION_ABANDONED") + 1;
        for (Map<String, Object> lifecycle : afterTerminal) {
            events.insert(insertionIndex++, event("PROVIDER_TRANSPORT_LIFECYCLE", lifecycle));
        }
        return trace;
    }

    private static void replaceGenerationTerminalWithAbandonment(
            ObjectNode trace,
            String currentType) {
        ObjectNode terminal = (ObjectNode) trace.path("events").get(
                eventIndex(trace, currentType));
        terminal.put("type", "GENERATION_ABANDONED");
        ObjectNode terminalData = (ObjectNode) terminal.path("data");
        retainProviderGenerationKeys(terminalData);
        terminalData.put("abandonment", "SCOPE_CLOSED");
    }

    private static void replaceGenerationTerminalWithFailure(
            ObjectNode trace,
            String currentType,
            String failure) {
        ObjectNode terminal = (ObjectNode) trace.path("events").get(
                eventIndex(trace, currentType));
        terminal.put("type", "GENERATION_FAILED");
        ObjectNode terminalData = (ObjectNode) terminal.path("data");
        retainProviderGenerationKeys(terminalData);
        terminalData.put("failure", failure);
    }

    private static ObjectNode failedTrace(boolean settleBeforeAttemptFailure) {
        ObjectNode trace = validTrace();
        ArrayNode events = (ArrayNode) trace.path("events");
        ObjectNode handoff = eventAtTransition(trace, "HANDOFF_ADMITTED").deepCopy();
        ObjectNode send = eventAtTransition(trace, "SEND_API_ENTERED").deepCopy();
        ObjectNode settled = eventAtTransition(trace, "SETTLED").deepCopy();
        removeTransportEvents(trace);

        ObjectNode attemptTerminal = (ObjectNode) events.get(
                eventIndex(trace, "GENERATION_RESPONSE_CAPTURED"));
        attemptTerminal.put("type", "GENERATION_ATTEMPT_FAILED");
        ObjectNode attemptData = (ObjectNode) attemptTerminal.path("data");
        retainProviderAttemptKeys(attemptData);
        attemptData.put("failure", "PROVIDER_EXCEPTION");

        ObjectNode generationTerminal = (ObjectNode) events.get(
                eventIndex(trace, "GENERATION_COMPLETED"));
        generationTerminal.put("type", "GENERATION_FAILED");
        ObjectNode generationData = (ObjectNode) generationTerminal.path("data");
        retainProviderGenerationKeys(generationData);
        generationData.put("failure", "PROVIDER_EXCEPTION");

        ObjectNode settledData = (ObjectNode) settled.path("data");
        settledData.put("transportSequence", 3);
        settledData.put("settlement", "QUARANTINE_REQUIRED");
        settledData.put("transportFailure", "UNKNOWN");
        settledData.put("quarantineRequired", true);

        int dispatchIndex = eventIndex(trace, "GENERATION_REQUEST_DISPATCHED");
        events.insert(dispatchIndex + 1, handoff);
        events.insert(dispatchIndex + 2, send);
        int settlementIndex = settleBeforeAttemptFailure
                ? dispatchIndex + 3
                : eventIndex(trace, "GENERATION_ATTEMPT_FAILED") + 1;
        events.insert(settlementIndex, settled);
        return trace;
    }

    private static Map<String, Object> generation(long sequence) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("providerLedgerVersion", 1);
        data.put("ledgerSequence", sequence);
        data.put("generationId", "trc-transport-generation-1");
        data.put("source", "FOREGROUND_PROVIDER");
        data.put("purpose", "TURN_RESPONSE");
        data.put("toolAuthorityMode", "NONE");
        return data;
    }

    private static Map<String, Object> attempt(long sequence, boolean dispatched) {
        Map<String, Object> data = generation(sequence);
        data.put("attemptId", "trc-transport-generation-1-attempt-1");
        data.put("attemptOrdinal", 1);
        data.put("attemptKind", "PRIMARY");
        data.put("retryReason", "NONE");
        data.put("requestDispatched", dispatched);
        return data;
    }

    private static Map<String, Object> transport(
            long sequence,
            String transition,
            String observationMode,
            String settlement,
            String failure,
            String cancellationState,
            String cancellationReason,
            String wireDisposition,
            boolean late) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("transportLedgerVersion", 1);
        data.put("transportSequence", sequence);
        data.put("generationId", "trc-transport-generation-1");
        data.put("attemptId", "trc-transport-generation-1-attempt-1");
        data.put("attemptOrdinal", 1);
        data.put("transportOperationId",
                "trc-transport-generation-1-attempt-1-transport-1");
        data.put("transition", transition);
        data.put("observationMode", observationMode);
        data.put("settlement", settlement);
        data.put("transportFailure", failure);
        data.put("cancellationState", cancellationState);
        data.put("cancellationReason", cancellationReason);
        data.put("wireDisposition", wireDisposition);
        data.put("quarantineRequired", switch (settlement) {
            case "CANCELLED_IN_FLIGHT", "FAILED_DURING_BODY",
                    "CLOSED_BEFORE_PROVIDER_TERMINAL", "QUARANTINE_REQUIRED" -> true;
            case "FAILED_BEFORE_HEADERS" -> !"SEND_API_NOT_ENTERED".equals(wireDisposition);
            default -> false;
        });
        data.put("lateAfterGenerationTerminal", late);
        data.put("processBinding", "UNAVAILABLE");
        return data;
    }

    private static ObjectNode event(String type, Map<String, Object> data) {
        ObjectNode event = MAPPER.createObjectNode();
        event.put("type", type);
        event.set("data", MAPPER.valueToTree(data));
        return event;
    }

    @SafeVarargs
    private static ObjectNode transportOnlyTrace(Map<String, Object>... lifecycle) {
        return transportOnlyTrace(List.of(lifecycle));
    }

    private static ObjectNode transportOnlyTrace(List<Map<String, Object>> lifecycle) {
        ObjectNode root = MAPPER.createObjectNode();
        ArrayNode events = root.putArray("events");
        for (Map<String, Object> event : lifecycle) {
            events.add(event("PROVIDER_TRANSPORT_LIFECYCLE", event));
        }
        return root;
    }

    private static ObjectNode data(ObjectNode trace, String type) {
        return (ObjectNode) trace.path("events").get(eventIndex(trace, type)).path("data");
    }

    private static ObjectNode transportEvent(ObjectNode trace, String transition) {
        for (JsonNode event : trace.path("events")) {
            if ("PROVIDER_TRANSPORT_LIFECYCLE".equals(event.path("type").asText())
                    && transition.equals(event.path("data").path("transition").asText())) {
                return (ObjectNode) event.path("data");
            }
        }
        throw new IllegalArgumentException("transport transition not found: " + transition);
    }

    private static ObjectNode eventAtTransition(ObjectNode trace, String transition) {
        ArrayNode events = (ArrayNode) trace.path("events");
        return (ObjectNode) events.get(transportEventIndex(trace, transition));
    }

    private static void retainProviderAttemptKeys(ObjectNode data) {
        Set<String> keys = Set.of(
                "providerLedgerVersion", "ledgerSequence", "generationId", "source",
                "purpose", "toolAuthorityMode", "attemptId", "attemptOrdinal",
                "attemptKind", "retryReason", "requestDispatched");
        data.properties().removeIf(entry -> !keys.contains(entry.getKey()));
    }

    private static void retainProviderGenerationKeys(ObjectNode data) {
        Set<String> keys = Set.of(
                "providerLedgerVersion", "ledgerSequence", "generationId", "source",
                "purpose", "toolAuthorityMode");
        data.properties().removeIf(entry -> !keys.contains(entry.getKey()));
    }

    private static int transportEventIndex(ObjectNode trace, String transition) {
        ArrayNode events = (ArrayNode) trace.path("events");
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            if ("PROVIDER_TRANSPORT_LIFECYCLE".equals(event.path("type").asText())
                    && transition.equals(event.path("data").path("transition").asText())) {
                return index;
            }
        }
        throw new IllegalArgumentException("transport transition not found: " + transition);
    }

    private static int eventIndex(ObjectNode trace, String type) {
        ArrayNode events = (ArrayNode) trace.path("events");
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText())) return index;
        }
        throw new IllegalArgumentException("event not found: " + type);
    }

    private static void removeTransportEvents(ObjectNode trace) {
        ArrayNode events = (ArrayNode) trace.path("events");
        List<ObjectNode> retained = new ArrayList<>();
        for (JsonNode event : events) {
            if (!"PROVIDER_TRANSPORT_LIFECYCLE".equals(event.path("type").asText())) {
                retained.add((ObjectNode) event);
            }
        }
        events.removeAll();
        retained.forEach(events::add);
    }

    private static void removeTransportTransition(ObjectNode trace, String transition) {
        ArrayNode events = (ArrayNode) trace.path("events");
        for (int index = 0; index < events.size(); index++) {
            JsonNode event = events.get(index);
            if ("PROVIDER_TRANSPORT_LIFECYCLE".equals(event.path("type").asText())
                    && transition.equals(event.path("data").path("transition").asText())) {
                events.remove(index);
                return;
            }
        }
    }

    private static void removeTransportBindings(ObjectNode trace) {
        data(trace, "GENERATION_RESPONSE_CAPTURED").remove("transportOperationId");
        data(trace, "GENERATION_COMPLETED").remove("transportOperationId");
    }

    private static void renumberProviderLedger(ObjectNode trace) {
        long sequence = 1;
        for (JsonNode event : trace.path("events")) {
            JsonNode data = event.path("data");
            if (data.has("providerLedgerVersion")) {
                ((ObjectNode) data).put("ledgerSequence", sequence++);
            }
        }
    }
}
