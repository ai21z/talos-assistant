package dev.talos.qualification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Strict negative matrix for protocol-v6 physical and terminal answer provenance. */
class QualificationProtocolV6ProvenanceNegativeMatrixTest {
    private static final String ANSWER = "Ready";
    private static final byte[] ANSWER_BYTES = ANSWER.getBytes(StandardCharsets.UTF_8);
    private static final String ANSWER_HASH = "sha256:"
            + QualificationScenarioBank.sha256(ANSWER_BYTES);
    private static final String GENERATION_ID = "trace-1-generation-1";
    private static final String ATTEMPT_ID = GENERATION_ID + "-attempt-1";
    private static final Set<String> PHYSICAL_EVENTS = Set.of(
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

    @TempDir
    Path temp;

    @Test
    void canonicalFixtureProvesThePositiveControlBeforeNegativeMutation() {
        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(validModelTrace());

        assertEquals(1, ledger.generations().size());
        assertOrigin(
                validModelTrace(),
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                "canonical model provenance");
    }

    @Test
    void nativeProposalLedgerIsBoundedHashedAndTiedToSelectedResponse() {
        ObjectNode trace = validNativeToolTrace();
        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(trace);
        QualificationProviderAttemptLedger.Proposal proposal = ledger.selectedProposal(
                GENERATION_ID, ATTEMPT_ID, 1);

        assertEquals("sha256:" + "1".repeat(64), proposal.toolNameHash());
        assertEquals("sha256:" + "2".repeat(64), proposal.argumentsHash());

        ObjectNode rawNameLeak = validNativeToolTrace();
        data(rawNameLeak, "PROVIDER_TOOL_PROPOSAL_CAPTURED")
                .put("toolName", "provider-canary");
        assertLedgerRejected(rawNameLeak, "data keys differ");

        ObjectNode wrongCapturedCount = validNativeToolTrace();
        data(wrongCapturedCount, "GENERATION_RESPONSE_CAPTURED")
                .put("capturedNativeToolProposalCount", 0);
        assertLedgerRejected(wrongCapturedCount, "contradicts response count");

        ObjectNode wrongProposalHash = validNativeToolTrace();
        data(wrongProposalHash, "PROVIDER_TOOL_PROPOSAL_CAPTURED")
                .put("argumentsHash", "not-a-hash");
        assertLedgerRejected(wrongProposalHash, "lowercase SHA-256");
    }

    @Test
    void canonicalPlaceholderAndScriptedSyntheticReturnsRemainValidWithoutDispatch() {
        assertEquals(1, QualificationProviderAttemptLedger.parse(validSyntheticTrace(
                "PLACEHOLDER_RUNTIME", "PLACEHOLDER_RESPONSE")).generations().size());
        assertEquals(1, QualificationProviderAttemptLedger.parse(validSyntheticTrace(
                "SCRIPTED_HARNESS", "HARNESS_FIXTURE")).generations().size());

        ObjectNode forgedProviderSynthetic = validSyntheticTrace(
                "PLACEHOLDER_RUNTIME", "PLACEHOLDER_RESPONSE");
        for (JsonNode event : events(forgedProviderSynthetic)) {
            JsonNode eventData = event.path("data");
            if (eventData.has("source")) {
                ((ObjectNode) eventData).put("source", "FOREGROUND_PROVIDER");
                ((ObjectNode) eventData).put("purpose", "TURN_RESPONSE");
            }
        }
        assertLedgerRejected(forgedProviderSynthetic, "lacks a dispatched physical request");
    }

    @Test
    void explicitLegacyTransportGapIsReadableButNeverProviderEligible() {
        ObjectNode trace = validLegacyUnobservedTrace();
        QualificationProviderAttemptLedger.Ledger ledger =
                QualificationProviderAttemptLedger.parse(trace);
        QualificationProviderAttemptLedger.Attempt attempt = ledger.generations()
                .getFirst().attempts().getFirst();

        assertTrue(attempt.transportUnobserved());
        assertFalse(attempt.requestDispatched());
        assertFalse(attempt.response().providerBacked());
        assertOrigin(trace, QualificationRecords.AnswerOrigin.NONE,
                "legacy transport cannot prove model provenance");

        ObjectNode missingMarker = validLegacyUnobservedTrace();
        removeEvent(missingMarker, "GENERATION_TRANSPORT_UNOBSERVED");
        renumberPhysicalEvents(missingMarker);
        assertLedgerRejected(missingMarker, "lacks a dispatched physical request");

        ObjectNode forgedProviderBacked = validLegacyUnobservedTrace();
        data(forgedProviderBacked, "GENERATION_RESPONSE_CAPTURED")
                .put("providerBacked", true);
        assertLedgerRejected(forgedProviderBacked, "derived flags contradict");

        ObjectNode forgedEligibility = validLegacyUnobservedTrace();
        data(forgedEligibility, "GENERATION_COMPLETED")
                .put("providerTerminalEligible", true);
        assertLedgerRejected(forgedEligibility, "contradicts its selected response");
    }

    @Test
    void ledgerRejectsDuplicateOrUnclosedPhysicalState() {
        ObjectNode duplicateGeneration = validModelTrace();
        events(duplicateGeneration).insert(1, events(duplicateGeneration).get(0).deepCopy());
        renumberPhysicalEvents(duplicateGeneration);
        assertLedgerRejected(duplicateGeneration, "duplicate physical generationId");

        ObjectNode duplicateAttempt = validModelTrace();
        int attemptStart = eventIndex(duplicateAttempt, "GENERATION_ATTEMPT_STARTED");
        events(duplicateAttempt).insert(
                attemptStart + 1, events(duplicateAttempt).get(attemptStart).deepCopy());
        renumberPhysicalEvents(duplicateAttempt);
        assertLedgerRejected(duplicateAttempt, "duplicate physical attemptId");

        ObjectNode duplicateAttemptTerminal = validModelTrace();
        int response = eventIndex(duplicateAttemptTerminal, "GENERATION_RESPONSE_CAPTURED");
        Map<String, Object> failureData = attemptData(
                1, ATTEMPT_ID, 1, "PRIMARY", "NONE", true);
        failureData.put("failure", "PROVIDER_EXCEPTION");
        events(duplicateAttemptTerminal).insert(
                response + 1, QualificationJson.tree(event(
                        "GENERATION_ATTEMPT_FAILED", failureData)));
        renumberPhysicalEvents(duplicateAttemptTerminal);
        assertLedgerRejected(duplicateAttemptTerminal, "follows closed attempt");

        ObjectNode openAttempt = validModelTrace();
        removeEvent(openAttempt, "GENERATION_RESPONSE_CAPTURED");
        renumberPhysicalEvents(openAttempt);
        assertLedgerRejected(openAttempt, "physical attempt remained open");

        ObjectNode openGeneration = validModelTrace();
        removeEvent(openGeneration, "GENERATION_COMPLETED");
        renumberPhysicalEvents(openGeneration);
        assertLedgerRejected(openGeneration, "physical generation is not closed");
    }

    @Test
    void ledgerRejectsOverlappingAttemptsWithinOneGeneration() {
        ObjectNode overlapping = validRetryModelTrace();
        int retryStart = secondEventIndex(overlapping, "GENERATION_ATTEMPT_STARTED");
        JsonNode retryStartEvent = events(overlapping).remove(retryStart);
        int firstResponse = eventIndex(overlapping, "GENERATION_RESPONSE_CAPTURED");
        events(overlapping).insert(firstResponse, retryStartEvent);
        renumberPhysicalEvents(overlapping);

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(overlapping),
                "a physical retry must not start while the prior attempt remains open");
    }

    @Test
    void providerGenerationFailureRequiresAnExactlyMatchingDispatchedFinalAttempt() {
        assertLedgerRejected(
                failedTraceWithoutAttempt("PROVIDER_EXCEPTION"),
                "provider generation failure lacks a dispatched final attempt");
        assertLedgerRejected(
                failedTrace(false, "PROVIDER_EXCEPTION", "PROVIDER_EXCEPTION"),
                "provider attempt failure lacks dispatch");
        assertLedgerRejected(
                failedTrace(true, "RUNTIME_EXCEPTION", "PROVIDER_EXCEPTION"),
                "generation and final attempt failure disagree");
    }

    @Test
    void everyAttemptFailureMustMatchItsObservedTransportState() {
        assertLedgerRejected(
                recoveredFailureTrace(false, "PROVIDER_TRANSIENT"),
                "provider attempt failure lacks dispatch");
        assertLedgerRejected(
                recoveredFailureTrace(true, "RUNTIME_PRE_DISPATCH"),
                "pre-dispatch failure follows dispatch");
        assertLedgerRejected(
                failedTraceWithoutAttempt("TRANSPORT_UNOBSERVED"),
                "transport-unobserved generation failure lacks an attempt");
    }

    @Test
    void failureVocabularyMustMatchTheGenerationSourceFamily() {
        ObjectNode providerFailureOnHarness = retypePhysicalGeneration(
                failedTrace(true, "PROVIDER_EXCEPTION", "PROVIDER_EXCEPTION"),
                "SCRIPTED_HARNESS", "HARNESS_FIXTURE");
        assertLedgerRejected(
                providerFailureOnHarness,
                "non-provider source");

        ObjectNode unobservedTransportOnPlaceholder = retypePhysicalGeneration(
                unobservedFailureTrace(),
                "PLACEHOLDER_RUNTIME", "PLACEHOLDER_RESPONSE");
        assertLedgerRejected(
                unobservedTransportOnPlaceholder,
                "non-provider source");

        assertLedgerRejected(
                failedTraceWithoutAttempt("HARNESS_EXCEPTION"),
                "non-harness source");

        ObjectNode runtimeFailureOnHarness = retypePhysicalGeneration(
                failedTraceWithoutAttempt("RUNTIME_PRE_DISPATCH"),
                "SCRIPTED_HARNESS", "HARNESS_FIXTURE");
        assertLedgerRejected(
                runtimeFailureOnHarness,
                "harness source");
    }

    @Test
    void causalRuntimeFailureExcludesUnobservedTransport() {
        assertTrue(QualificationProviderAttemptLedger.parse(
                failedTrace(false, "RUNTIME_PRE_DISPATCH", "RUNTIME_PRE_DISPATCH"))
                .causalRuntimeFailurePresent());
        assertTrue(QualificationProviderAttemptLedger.parse(
                failedTrace(true, "RUNTIME_EXCEPTION", "RUNTIME_EXCEPTION"))
                .causalRuntimeFailurePresent());
        QualificationProviderAttemptLedger.Ledger unobserved =
                QualificationProviderAttemptLedger.parse(unobservedFailureTrace());
        assertFalse(unobserved.causalRuntimeFailurePresent());
        assertFalse(unobserved.causalProviderFailurePresent());
    }

    @Test
    void nativeProposalCannotAppearAfterItsGenerationCompleted() {
        ObjectNode reordered = validNativeToolTrace();
        int proposalIndex = eventIndex(reordered, "PROVIDER_TOOL_PROPOSAL_CAPTURED");
        JsonNode proposal = events(reordered).remove(proposalIndex);
        int completedIndex = eventIndex(reordered, "GENERATION_COMPLETED");
        events(reordered).insert(completedIndex + 1, proposal);

        assertLedgerRejected(reordered, "physical event follows closed generation");
    }

    @Test
    void ledgerRejectsSequenceSourceAndDerivedFlagContradictions() {
        ObjectNode badSequence = validModelTrace();
        data(badSequence, "GENERATION_RESPONSE_CAPTURED").put("ledgerSequence", 99);
        assertLedgerRejected(badSequence, "provider ledger sequence is not contiguous");

        ObjectNode badSource = validModelTrace();
        data(badSource, "GENERATION_REQUEST_DISPATCHED")
                .put("source", "BACKGROUND_PROVIDER");
        assertLedgerRejected(badSource, "source/purpose differs from generation start");

        List<FieldMutation> derivedContradictions = List.of(
                new FieldMutation("providerBacked", false),
                new FieldMutation("foreground", false),
                new FieldMutation("completedNormally", false),
                new FieldMutation("normalized", true),
                new FieldMutation("truncated", true));
        for (FieldMutation contradiction : derivedContradictions) {
            ObjectNode trace = validModelTrace();
            data(trace, "GENERATION_RESPONSE_CAPTURED")
                    .put(contradiction.field(), contradiction.value());
            assertLedgerRejected(
                    trace, "derived flags contradict typed source/completion");
        }

        ObjectNode badPurpose = validModelTrace();
        data(badPurpose, "GENERATION_STARTED")
                .put("purpose", "BACKGROUND_MAINTENANCE");
        assertLedgerRejected(badPurpose, "source/purpose is not canonical");
    }

    @Test
    void generationCompletionMustSelectTheExactFinalResponse() {
        ObjectNode unknownAttempt = validModelTrace();
        data(unknownAttempt, "GENERATION_COMPLETED")
                .put("selectedAttemptId", GENERATION_ID + "-attempt-99");
        assertLedgerRejected(unknownAttempt, "does not select its final response attempt");

        ObjectNode wrongOrdinal = validModelTrace();
        data(wrongOrdinal, "GENERATION_COMPLETED").put("selectedAttemptOrdinal", 2);
        assertLedgerRejected(wrongOrdinal, "does not select its final response attempt");

        ObjectNode nonFinalAttempt = validRetryModelTrace();
        data(nonFinalAttempt, "GENERATION_COMPLETED")
                .put("selectedAttemptId", ATTEMPT_ID)
                .put("selectedAttemptOrdinal", 1);
        assertLedgerRejected(nonFinalAttempt, "does not select its final response attempt");

        for (CompletionMutation mismatch : List.of(
                new CompletionMutation("assistantHash", "sha256:" + "0".repeat(64)),
                new CompletionMutation("completion", "PROVIDER_LENGTH"),
                new CompletionMutation("providerTerminalEligible", false),
                new CompletionMutation("providerToolCallEligible", true))) {
            ObjectNode trace = validModelTrace();
            ObjectNode completion = data(trace, "GENERATION_COMPLETED");
            if (mismatch.value() instanceof Boolean value) {
                completion.put(mismatch.field(), value);
            } else {
                completion.put(mismatch.field(), mismatch.value().toString());
            }
            assertLedgerRejected(trace, "contradicts its selected response");
        }
    }

    @Test
    void matcherRejectsMissingDuplicateOrReorderedTerminalTail() {
        for (String type : List.of(
                "TERMINAL_ANSWER_SELECTED",
                "ROUTED_ANSWER_CAPTURED",
                "OUTCOME_RENDERED",
                "TRACE_COMPLETED")) {
            ObjectNode missing = validModelTrace();
            removeEvent(missing, type);
            assertOrigin(missing, QualificationRecords.AnswerOrigin.NONE, "missing " + type);

            ObjectNode duplicate = validModelTrace();
            int index = eventIndex(duplicate, type);
            events(duplicate).insert(index + 1, events(duplicate).get(index).deepCopy());
            assertOrigin(duplicate, QualificationRecords.AnswerOrigin.NONE, "duplicate " + type);
        }

        for (TailSwap swap : List.of(
                new TailSwap("TERMINAL_ANSWER_SELECTED", "ROUTED_ANSWER_CAPTURED"),
                new TailSwap("ROUTED_ANSWER_CAPTURED", "OUTCOME_RENDERED"),
                new TailSwap("OUTCOME_RENDERED", "TRACE_COMPLETED"))) {
            ObjectNode reordered = validModelTrace();
            swapEvents(reordered, swap.first(), swap.second());
            assertOrigin(
                    reordered,
                    QualificationRecords.AnswerOrigin.NONE,
                    "reordered " + swap.first() + "/" + swap.second());
        }

        ObjectNode routedMismatch = validModelTrace();
        data(routedMismatch, "ROUTED_ANSWER_CAPTURED")
                .put("answerHash", "sha256:" + "0".repeat(64));
        assertOrigin(
                routedMismatch,
                QualificationRecords.AnswerOrigin.NONE,
                "routed answer differs from selected answer");
    }

    @Test
    void anyTypedActionAfterTerminalSelectionInvalidatesAttribution() {
        List<Map<String, Object>> invalidatingEvents = List.of(
                event("TOOL_EXECUTED", Map.of()),
                event("APPROVAL_GRANTED", Map.of()),
                event("GENERATION_DIAGNOSTIC", Map.of()),
                event("PROVIDER_TOOL_PROPOSAL_CAPTURED", Map.of()),
                event("DETERMINISTIC_ANSWER_SELECTED", deterministicData(
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC)));
        for (Map<String, Object> invalidating : invalidatingEvents) {
            ObjectNode trace = validModelTrace();
            int outcome = eventIndex(trace, "OUTCOME_RENDERED");
            events(trace).insert(outcome, QualificationJson.tree(invalidating));
            assertOrigin(
                    trace,
                    QualificationRecords.AnswerOrigin.NONE,
                    "later invalidation " + invalidating.get("type"));
        }
    }

    @Test
    void unknownPostSelectionEventFailsClosedInsteadOfBeingAssumedOrthogonal() {
        ObjectNode trace = validModelTrace();
        int outcome = eventIndex(trace, "OUTCOME_RENDERED");
        events(trace).insert(outcome, QualificationJson.tree(event(
                "ANSWER_REWRITTEN", Map.of("replacementHash", ANSWER_HASH))));

        assertOrigin(
                trace,
                QualificationRecords.AnswerOrigin.NONE,
                "an unknown post-selection event cannot preserve verified provenance");
    }

    @Test
    void deterministicToolAndOperatorOriginsRequireMatchingTypedSelections() {
        for (QualificationRecords.AnswerOrigin origin : List.of(
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC,
                QualificationRecords.AnswerOrigin.OPERATOR)) {
            assertOrigin(deterministicTrace(origin, origin), origin, origin.name());
        }

        ObjectNode missingSelection = deterministicTrace(
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC);
        removeEvent(missingSelection, "DETERMINISTIC_ANSWER_SELECTED");
        assertOrigin(
                missingSelection,
                QualificationRecords.AnswerOrigin.NONE,
                "terminal origin without typed selection");

        assertOrigin(
                deterministicTrace(
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC),
                QualificationRecords.AnswerOrigin.NONE,
                "selection and terminal typed origins disagree");

        for (QualificationRecords.AnswerOrigin forbiddenSelection : List.of(
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                QualificationRecords.AnswerOrigin.NONE)) {
            assertOrigin(
                    deterministicTrace(forbiddenSelection, forbiddenSelection),
                    QualificationRecords.AnswerOrigin.NONE,
                    "forbidden deterministic selection " + forbiddenSelection);
        }

        ObjectNode modelWithoutPhysicalBinding = deterministicTrace(
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationRecords.AnswerOrigin.MODEL_PROVIDER);
        removeEvent(modelWithoutPhysicalBinding, "DETERMINISTIC_ANSWER_SELECTED");
        ObjectNode terminal = data(modelWithoutPhysicalBinding, "TERMINAL_ANSWER_SELECTED");
        terminal.put("sourceGenerationId", GENERATION_ID);
        terminal.put("sourceAttemptId", ATTEMPT_ID);
        assertOrigin(
                modelWithoutPhysicalBinding,
                QualificationRecords.AnswerOrigin.NONE,
                "model terminal lacks a selected physical provider response");
    }

    @Test
    void matcherFailsClosedOnMalformedUtf8() {
        byte[] malformedAnswer = new byte[]{(byte) 0xc3, 0x28};
        ObjectNode unicodeTrace = validModelTrace();
        unicodeTrace.put("note", "\u00e9");
        byte[] malformedTrace = QualificationJson.bytes(unicodeTrace);
        boolean corrupted = false;
        for (int index = 0; index < malformedTrace.length - 1; index++) {
            if (malformedTrace[index] == (byte) 0xc3
                    && malformedTrace[index + 1] == (byte) 0xa9) {
                malformedTrace[index + 1] = 0x28;
                corrupted = true;
                break;
            }
        }
        assertTrue(corrupted, "test fixture must contain and corrupt the UTF-8 sequence");

        assertEquals(
                QualificationRecords.AnswerOrigin.NONE,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        malformedAnswer,
                        QualificationJson.bytes(validModelTrace()),
                        QualificationProtocol.VERSION));
        assertEquals(
                QualificationRecords.AnswerOrigin.NONE,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        ANSWER_BYTES,
                        malformedTrace,
                        QualificationProtocol.VERSION));
    }

    @Test
    void preResultParserRejectsTimingClaimsThatContradictThePhysicalLedger()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog, scenario);
        Path timingsPath = packet.runRoot().resolve("timings.json");
        ObjectNode baseline = (ObjectNode) QualificationJson.readTree(timingsPath);

        List<TimingContradiction> contradictions = List.of(
                new TimingContradiction(
                        "timedOut", true, "TIMING_TRACE_TIMEOUT_MISMATCH"),
                new TimingContradiction(
                        "outputCapHit", true, "TIMING_TRACE_OUTPUT_CAP_MISMATCH"),
                new TimingContradiction(
                        "toolEligibleGenerations",
                        baseline.path("toolEligibleGenerations").intValue() + 1,
                        "TIMING_TRACE_TOOL_ELIGIBLE_COUNT_MISMATCH"),
                new TimingContradiction(
                        "backendErrorCode", "PROVIDER_EXCEPTION",
                        "TIMING_BACKEND_ERROR_CODE_FORBIDDEN_V6"),
                new TimingContradiction(
                        "firstOutputMillis",
                        baseline.path("totalTurnWallTimeMillis").longValue() + 1,
                        "TIMING_EVIDENCE_INVALID"));

        for (TimingContradiction contradiction : contradictions) {
            ObjectNode timing = baseline.deepCopy();
            put(timing, contradiction.field(), contradiction.value());
            QualificationJson.write(timingsPath, timing);

            QualificationPreResultEvidence.Snapshot snapshot =
                    QualificationPreResultEvidence.capture(
                            packet.root(), packet.runRoot(), scenario.id(), catalog);

            assertFalse(snapshot.complete(), contradiction.field());
            assertTrue(
                    snapshot.findings().contains(contradiction.expectedFinding()),
                    () -> contradiction.field() + ": " + snapshot.findings());
        }

        QualificationJson.write(timingsPath, baseline);
        QualificationPreResultEvidence.Snapshot restored =
                QualificationPreResultEvidence.capture(
                        packet.root(), packet.runRoot(), scenario.id(), catalog);
        assertFalse(restored.complete());
        assertEquals(List.of("ACTIVE_PROCESS_LAUNCH_RECEIPT_UNAVAILABLE"),
                restored.findings());
    }

    private static ObjectNode validModelTrace() {
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                3, ATTEMPT_ID, 1, "PRIMARY", "NONE", true)));
        physical.add(event("GENERATION_RESPONSE_CAPTURED", responseData(
                4, ATTEMPT_ID, 1, "PRIMARY", "NONE", "PROVIDER_EOS",
                false, false, true)));
        physical.add(event("GENERATION_COMPLETED", completionData(
                5, ATTEMPT_ID, 1, "PROVIDER_EOS", true)));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode validRetryModelTrace() {
        String retryAttempt = GENERATION_ID + "-attempt-2";
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                3, ATTEMPT_ID, 1, "PRIMARY", "NONE", true)));
        physical.add(event("GENERATION_RESPONSE_CAPTURED", responseData(
                4, ATTEMPT_ID, 1, "PRIMARY", "NONE", "PROVIDER_LENGTH",
                false, true, false)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                5, retryAttempt, 2, "OUTPUT_CAP_LIFT_RETRY",
                "OUTPUT_CAP_TRUNCATION", false)));
        physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                6, retryAttempt, 2, "OUTPUT_CAP_LIFT_RETRY",
                "OUTPUT_CAP_TRUNCATION", true)));
        physical.add(event("GENERATION_RESPONSE_CAPTURED", responseData(
                7, retryAttempt, 2, "OUTPUT_CAP_LIFT_RETRY",
                "OUTPUT_CAP_TRUNCATION", "PROVIDER_EOS", false, false, true)));
        physical.add(event("GENERATION_COMPLETED", completionData(
                8, retryAttempt, 2, "PROVIDER_EOS", true)));
        return modelTrace(physical, retryAttempt);
    }

    private static ObjectNode validNativeToolTrace() {
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                3, ATTEMPT_ID, 1, "PRIMARY", "NONE", true)));
        Map<String, Object> response = responseData(
                4, ATTEMPT_ID, 1, "PRIMARY", "NONE", "PROVIDER_EOS",
                false, false, true);
        response.put("nativeToolCallCount", 1);
        response.put("capturedNativeToolProposalCount", 1);
        physical.add(event("GENERATION_RESPONSE_CAPTURED", response));
        Map<String, Object> proposal = attemptData(
                4, ATTEMPT_ID, 1, "PRIMARY", "NONE", true);
        proposal.put("proposalOrdinal", 1);
        proposal.put("providerCallIdHash", "sha256:" + "0".repeat(64));
        proposal.put("toolNameClassification", "CANONICAL");
        proposal.put("toolNameHash", "sha256:" + "1".repeat(64));
        proposal.put("toolNameChars", 15);
        proposal.put("argumentNameHashes", List.of("sha256:" + "3".repeat(64)));
        proposal.put("argumentCount", 1);
        proposal.put("argumentNameHashesTruncated", false);
        proposal.put("argumentsHash", "sha256:" + "2".repeat(64));
        physical.add(event("PROVIDER_TOOL_PROPOSAL_CAPTURED", proposal));
        Map<String, Object> completed = completionData(
                5, ATTEMPT_ID, 1, "PROVIDER_EOS", true);
        completed.put("providerToolCallEligible", true);
        physical.add(event("GENERATION_COMPLETED", completed));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode failedTraceWithoutAttempt(String generationFailure) {
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        Map<String, Object> failed = generationData(2);
        failed.put("failure", generationFailure);
        physical.add(event("GENERATION_FAILED", failed));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode failedTrace(
            boolean dispatched,
            String attemptFailure,
            String generationFailure) {
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        int sequence = 3;
        if (dispatched) {
            physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                    sequence++, ATTEMPT_ID, 1, "PRIMARY", "NONE", true)));
        }
        Map<String, Object> attemptFailed = attemptData(
                sequence++, ATTEMPT_ID, 1, "PRIMARY", "NONE", dispatched);
        attemptFailed.put("failure", attemptFailure);
        physical.add(event("GENERATION_ATTEMPT_FAILED", attemptFailed));
        Map<String, Object> generationFailed = generationData(sequence);
        generationFailed.put("failure", generationFailure);
        physical.add(event("GENERATION_FAILED", generationFailed));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode recoveredFailureTrace(
            boolean firstDispatched,
            String firstFailure) {
        String retryAttempt = GENERATION_ID + "-attempt-2";
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        int sequence = 3;
        if (firstDispatched) {
            physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                    sequence++, ATTEMPT_ID, 1, "PRIMARY", "NONE", true)));
        }
        Map<String, Object> failed = attemptData(
                sequence++, ATTEMPT_ID, 1, "PRIMARY", "NONE", firstDispatched);
        failed.put("failure", firstFailure);
        physical.add(event("GENERATION_ATTEMPT_FAILED", failed));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                sequence++, retryAttempt, 2, "TRANSIENT_RETRY",
                "TRANSIENT_ENGINE_FAILURE", false)));
        physical.add(event("GENERATION_REQUEST_DISPATCHED", attemptData(
                sequence++, retryAttempt, 2, "TRANSIENT_RETRY",
                "TRANSIENT_ENGINE_FAILURE", true)));
        physical.add(event("GENERATION_RESPONSE_CAPTURED", responseData(
                sequence++, retryAttempt, 2, "TRANSIENT_RETRY",
                "TRANSIENT_ENGINE_FAILURE", "PROVIDER_EOS",
                false, false, true)));
        physical.add(event("GENERATION_COMPLETED", completionData(
                sequence, retryAttempt, 2, "PROVIDER_EOS", true)));
        return modelTrace(physical, retryAttempt);
    }

    private static ObjectNode unobservedFailureTrace() {
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        physical.add(event("GENERATION_TRANSPORT_UNOBSERVED", attemptData(
                3, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        Map<String, Object> attemptFailed = attemptData(
                4, ATTEMPT_ID, 1, "PRIMARY", "NONE", false);
        attemptFailed.put("failure", "TRANSPORT_UNOBSERVED");
        physical.add(event("GENERATION_ATTEMPT_FAILED", attemptFailed));
        Map<String, Object> generationFailed = generationData(5);
        generationFailed.put("failure", "TRANSPORT_UNOBSERVED");
        physical.add(event("GENERATION_FAILED", generationFailed));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode retypePhysicalGeneration(
            ObjectNode trace,
            String source,
            String purpose) {
        for (JsonNode event : events(trace)) {
            if (!PHYSICAL_EVENTS.contains(event.path("type").asText())) continue;
            JsonNode eventData = event.path("data");
            if (!eventData.has("source")) continue;
            ((ObjectNode) eventData).put("source", source);
            ((ObjectNode) eventData).put("purpose", purpose);
        }
        return trace;
    }

    private static ObjectNode validSyntheticTrace(String source, String purpose) {
        List<Map<String, Object>> physical = new ArrayList<>();
        Map<String, Object> generation = generationData(1);
        generation.put("source", source);
        generation.put("purpose", purpose);
        physical.add(event("GENERATION_STARTED", generation));
        Map<String, Object> attempt = attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false);
        attempt.put("source", source);
        attempt.put("purpose", purpose);
        physical.add(event("GENERATION_ATTEMPT_STARTED", attempt));
        Map<String, Object> response = attemptData(
                3, ATTEMPT_ID, 1, "PRIMARY", "NONE", false);
        response.put("source", source);
        response.put("purpose", purpose);
        response.put("assistantHash", ANSWER_HASH);
        response.put("assistantChars", ANSWER.length());
        response.put("nativeToolCallCount", 0);
        response.put("capturedNativeToolProposalCount", 0);
        response.put("nativeToolProposalsTruncated", false);
        response.put("textualToolCallPresent", false);
        response.put("completion", "SYNTHETIC_RETURN");
        response.put("providerBacked", false);
        response.put("foreground", false);
        response.put("completedNormally", false);
        response.put("normalized", false);
        response.put("truncated", false);
        physical.add(event("GENERATION_RESPONSE_CAPTURED", response));
        Map<String, Object> completed = new LinkedHashMap<>(generation);
        completed.put("ledgerSequence", 4);
        completed.put("selectedAttemptId", ATTEMPT_ID);
        completed.put("selectedAttemptOrdinal", 1);
        completed.put("completion", "SYNTHETIC_RETURN");
        completed.put("assistantHash", ANSWER_HASH);
        completed.put("providerTerminalEligible", false);
        completed.put("providerToolCallEligible", false);
        physical.add(event("GENERATION_COMPLETED", completed));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode validLegacyUnobservedTrace() {
        List<Map<String, Object>> physical = new ArrayList<>();
        physical.add(event("GENERATION_STARTED", generationData(1)));
        physical.add(event("GENERATION_ATTEMPT_STARTED", attemptData(
                2, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        physical.add(event("GENERATION_TRANSPORT_UNOBSERVED", attemptData(
                3, ATTEMPT_ID, 1, "PRIMARY", "NONE", false)));
        Map<String, Object> response = responseData(
                4, ATTEMPT_ID, 1, "PRIMARY", "NONE", "PROVIDER_EOS",
                false, false, false);
        response.put("requestDispatched", false);
        response.put("providerBacked", false);
        physical.add(event("GENERATION_RESPONSE_CAPTURED", response));
        physical.add(event("GENERATION_COMPLETED", completionData(
                5, ATTEMPT_ID, 1, "PROVIDER_EOS", false)));
        return modelTrace(physical, ATTEMPT_ID);
    }

    private static ObjectNode modelTrace(
            List<Map<String, Object>> physical,
            String selectedAttemptId) {
        List<Map<String, Object>> all = new ArrayList<>(physical);
        all.add(event("TERMINAL_ANSWER_SELECTED", Map.ofEntries(
                Map.entry("answerHash", ANSWER_HASH),
                Map.entry("answerChars", ANSWER.length()),
                Map.entry("origin", "MODEL_PROVIDER"),
                Map.entry("sourceGenerationId", GENERATION_ID),
                Map.entry("sourceAttemptId", selectedAttemptId))));
        all.add(event("ROUTED_ANSWER_CAPTURED", answerData()));
        all.add(event("OUTCOME_RENDERED", Map.of(
                "status", "COMPLETE",
                "classification", "COMPLETED")));
        all.add(event("TRACE_COMPLETED", Map.of()));
        return (ObjectNode) QualificationJson.tree(Map.of(
                "schemaVersion", QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION,
                "events", all));
    }

    private static ObjectNode deterministicTrace(
            QualificationRecords.AnswerOrigin selectedOrigin,
            QualificationRecords.AnswerOrigin terminalOrigin) {
        List<Map<String, Object>> all = new ArrayList<>();
        all.add(event("DETERMINISTIC_ANSWER_SELECTED", deterministicData(selectedOrigin)));
        all.add(event("TERMINAL_ANSWER_SELECTED", Map.ofEntries(
                Map.entry("answerHash", ANSWER_HASH),
                Map.entry("answerChars", ANSWER.length()),
                Map.entry("origin", terminalOrigin.name()))));
        all.add(event("ROUTED_ANSWER_CAPTURED", answerData()));
        all.add(event("OUTCOME_RENDERED", Map.of(
                "status", "COMPLETE",
                "classification", "COMPLETED")));
        all.add(event("TRACE_COMPLETED", Map.of()));
        return (ObjectNode) QualificationJson.tree(Map.of(
                "schemaVersion", QualificationProviderAttemptLedger.TRACE_SCHEMA_VERSION,
                "events", all));
    }

    private static Map<String, Object> generationData(int sequence) {
        return new LinkedHashMap<>(Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", sequence),
                Map.entry("generationId", GENERATION_ID),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE")));
    }

    private static Map<String, Object> attemptData(
            int sequence,
            String attemptId,
            int ordinal,
            String kind,
            String retryReason,
            boolean dispatched) {
        Map<String, Object> data = generationData(sequence);
        data.put("attemptId", attemptId);
        data.put("attemptOrdinal", ordinal);
        data.put("attemptKind", kind);
        data.put("retryReason", retryReason);
        data.put("requestDispatched", dispatched);
        return data;
    }

    private static Map<String, Object> responseData(
            int sequence,
            String attemptId,
            int ordinal,
            String kind,
            String retryReason,
            String completion,
            boolean normalized,
            boolean truncated,
            boolean completedNormally) {
        Map<String, Object> data = attemptData(
                sequence, attemptId, ordinal, kind, retryReason, true);
        data.put("assistantHash", ANSWER_HASH);
        data.put("assistantChars", ANSWER.length());
        data.put("nativeToolCallCount", 0);
        data.put("capturedNativeToolProposalCount", 0);
        data.put("nativeToolProposalsTruncated", false);
        data.put("textualToolCallPresent", false);
        data.put("completion", completion);
        data.put("providerBacked", true);
        data.put("foreground", true);
        data.put("completedNormally", completedNormally);
        data.put("normalized", normalized);
        data.put("truncated", truncated);
        return data;
    }

    private static Map<String, Object> completionData(
            int sequence,
            String attemptId,
            int ordinal,
            String completion,
            boolean terminalEligible) {
        Map<String, Object> data = generationData(sequence);
        data.put("selectedAttemptId", attemptId);
        data.put("selectedAttemptOrdinal", ordinal);
        data.put("completion", completion);
        data.put("assistantHash", ANSWER_HASH);
        data.put("providerTerminalEligible", terminalEligible);
        data.put("providerToolCallEligible", false);
        return data;
    }

    private static Map<String, Object> answerData() {
        return Map.of(
                "answerHash", ANSWER_HASH,
                "answerChars", ANSWER.length());
    }

    private static Map<String, Object> deterministicData(
            QualificationRecords.AnswerOrigin origin) {
        return Map.ofEntries(
                Map.entry("answerHash", ANSWER_HASH),
                Map.entry("answerChars", ANSWER.length()),
                Map.entry("origin", origin.name()));
    }

    private static Map<String, Object> event(String type, Map<String, ?> data) {
        return Map.of("type", type, "data", data);
    }

    private static ArrayNode events(ObjectNode trace) {
        return (ArrayNode) trace.path("events");
    }

    private static ObjectNode data(ObjectNode trace, String type) {
        return (ObjectNode) events(trace).get(eventIndex(trace, type)).path("data");
    }

    private static int eventIndex(ObjectNode trace, String type) {
        for (int index = 0; index < events(trace).size(); index++) {
            if (type.equals(events(trace).get(index).path("type").asText(""))) return index;
        }
        throw new IllegalArgumentException("missing trace event " + type);
    }

    private static int secondEventIndex(ObjectNode trace, String type) {
        boolean foundFirst = false;
        for (int index = 0; index < events(trace).size(); index++) {
            if (!type.equals(events(trace).get(index).path("type").asText(""))) continue;
            if (foundFirst) return index;
            foundFirst = true;
        }
        throw new IllegalArgumentException("missing second trace event " + type);
    }

    private static void removeEvent(ObjectNode trace, String type) {
        events(trace).remove(eventIndex(trace, type));
    }

    private static void swapEvents(ObjectNode trace, String first, String second) {
        int firstIndex = eventIndex(trace, first);
        int secondIndex = eventIndex(trace, second);
        JsonNode firstValue = events(trace).get(firstIndex).deepCopy();
        JsonNode secondValue = events(trace).get(secondIndex).deepCopy();
        events(trace).set(firstIndex, secondValue);
        events(trace).set(secondIndex, firstValue);
    }

    private static void renumberPhysicalEvents(ObjectNode trace) {
        int sequence = 0;
        for (JsonNode event : events(trace)) {
            if (PHYSICAL_EVENTS.contains(event.path("type").asText(""))) {
                ((ObjectNode) event.path("data")).put("ledgerSequence", ++sequence);
            }
        }
    }

    private static void assertLedgerRejected(ObjectNode trace, String expectedMessage) {
        IllegalArgumentException rejected = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationProviderAttemptLedger.parse(trace));
        assertTrue(
                rejected.getMessage().contains(expectedMessage),
                () -> "Expected '" + expectedMessage + "' in: " + rejected.getMessage());
    }

    private static void assertOrigin(
            ObjectNode trace,
            QualificationRecords.AnswerOrigin expected,
            String label) {
        assertEquals(
                expected,
                QualificationCausalOwnership.matchedAnswerOrigin(
                        ANSWER_BYTES,
                        QualificationJson.bytes(trace),
                        QualificationProtocol.VERSION),
                label);
    }

    private static void put(ObjectNode object, String field, Object value) {
        if (value instanceof Boolean bool) {
            object.put(field, bool);
        } else if (value instanceof Integer integer) {
            object.put(field, integer);
        } else if (value instanceof Long longValue) {
            object.put(field, longValue);
        } else {
            object.put(field, value.toString());
        }
    }

    private record FieldMutation(String field, boolean value) {}

    private record CompletionMutation(String field, Object value) {}

    private record TailSwap(String first, String second) {}

    private record TimingContradiction(String field, Object value, String expectedFinding) {}
}
