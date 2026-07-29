package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.outcome.MutationRecoveryAssessment;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationMutationRecoveryEvidenceTest {
    private static final String TARGET = "notes.md";
    private static final String OBLIGATION_SHA256 = "a".repeat(64);
    private static final String FAILURE_CALL_ID = "call-1-1";
    private static final String REPAIR_CALL_ID = "call-1-2";
    private static final String LATER_CALL_ID = "call-1-3";

    @Test
    void matchedObligationRecoveryBindsExactCallsAndSuppressesOnlyItsFailure()
            throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(matchedRecoveryTrace(true, false, REPAIR_CALL_ID)),
                scenario());

        assertEquals(
                List.of(FAILURE_CALL_ID, REPAIR_CALL_ID),
                trace.calls().stream()
                        .map(QualificationTraceEvidence.Call::toolCallId)
                        .toList());
        assertEquals(1, trace.mutationRecoveries().size());
        QualificationTraceEvidence.MutationRecoveryFact recovery =
                trace.mutationRecoveries().getFirst();
        assertTrue(recovery.proven());
        assertEquals(
                MutationRecoveryAssessment.Decision.RECOVERED_MATCHED_OBLIGATION.name(),
                recovery.decision());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);

        assertFalse(decision.ambiguousToolOutcome());
        assertFalse(decision.runtimeToolTransformationPresent());
        assertEquals(List.of(recovery), decision.provenMutationRecoveries());
    }

    @Test
    void missingRecoveryEventLeavesFailedToolOutcomeAmbiguous() throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(matchedRecoveryTrace(false, false, REPAIR_CALL_ID)),
                scenario());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);

        assertTrue(trace.mutationRecoveries().isEmpty());
        assertTrue(decision.ambiguousToolOutcome());
        assertTrue(decision.provenMutationRecoveries().isEmpty());
    }

    @Test
    void legacyRecoveryClaimRemainsObservedButUnproven() throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(matchedRecoveryTrace(
                        true,
                        false,
                        REPAIR_CALL_ID,
                        1,
                        true)),
                scenario());

        assertEquals(1, trace.mutationRecoveries().size());
        assertFalse(trace.mutationRecoveries().getFirst().proven());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);

        assertTrue(decision.ambiguousToolOutcome());
        assertTrue(decision.provenMutationRecoveries().isEmpty());
    }

    @Test
    void missingTypedOutcomeEvidenceLeavesRecoveryUnproven() throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(matchedRecoveryTrace(
                        true,
                        false,
                        REPAIR_CALL_ID,
                        2,
                        false)),
                scenario());

        assertEquals(1, trace.mutationRecoveries().size());
        assertFalse(trace.mutationRecoveries().getFirst().proven());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);
        assertTrue(decision.ambiguousToolOutcome());
    }

    @Test
    void redundantAlreadyAppliedObservationCannotSuppressAmbiguity() throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(redundantOperationTrace()),
                scenario());

        assertEquals(1, trace.mutationRecoveries().size());
        assertFalse(trace.mutationRecoveries().getFirst().proven());
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .REDUNDANT_ALREADY_APPLIED_OPERATION.name(),
                trace.mutationRecoveries().getFirst().decision());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);

        assertTrue(decision.ambiguousToolOutcome());
        assertTrue(decision.provenMutationRecoveries().isEmpty());
    }

    @Test
    void typedRepairSequenceBindsExactlyButCannotSuppressComparativeFailure()
            throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(typedRepairSequenceTrace()),
                scenario());

        assertEquals(1, trace.mutationRecoveries().size());
        QualificationTraceEvidence.MutationRecoveryFact settlement =
                trace.mutationRecoveries().getFirst();
        assertEquals(
                MutationRecoveryAssessment.Decision
                        .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                settlement.decision());
        assertFalse(settlement.proven());

        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);

        assertTrue(decision.ambiguousToolOutcome());
        assertTrue(decision.provenMutationRecoveries().isEmpty());

        ObjectNode priorReadUpgradedEvidence =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                priorReadUpgradedEvidence,
                "TOOL_OUTCOME_CLASSIFIED",
                1).put("mutationEvidenceKind", "FULL_WRITE_REPLACEMENT");
        QualificationTraceEvidence.Facts upgraded =
                QualificationTraceEvidence.read(
                        QualificationJson.bytes(priorReadUpgradedEvidence),
                        scenario());
        assertFalse(upgraded.mutationRecoveries().getFirst().proven());

        QualificationTraceEvidence.Facts emptyEdit =
                QualificationTraceEvidence.read(
                        QualificationJson.bytes(
                                typedEmptyRepairSequenceTrace(TARGET)),
                        scenario());
        assertFalse(emptyEdit.mutationRecoveries().getFirst().proven());

        QualificationTraceEvidence.Facts fullWrite =
                QualificationTraceEvidence.read(
                        QualificationJson.bytes(
                                typedEmptyRepairSequenceTrace(
                                        TARGET,
                                        "talos.write_file",
                                        "FULL_WRITE_REPLACEMENT")),
                        scenario());
        assertFalse(fullWrite.mutationRecoveries().getFirst().proven());

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(
                                typedEmptyRepairSequenceTrace(
                                        TARGET,
                                        "talos.write_file",
                                        "NONE")),
                        scenario()));

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(
                                typedEmptyRepairSequenceTrace("other.md")),
                        scenario()));
    }

    @Test
    void typedRepairSequenceRequiresNotMigratedExpectedTargetAuthority() {
        ObjectNode completeObligation =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                completeObligation,
                "REQUEST_OBLIGATION_COMPILED",
                0).put("derivationStatus", "COMPLETE");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(completeObligation),
                        scenario()));

        ObjectNode wrongTarget =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        ArrayNode targets = eventData(
                wrongTarget,
                "REQUEST_OBLIGATION_COMPILED",
                0).putArray("requiredMutationTargets");
        targets.add("other.md");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongTarget),
                        scenario()));

        ObjectNode wrongHash =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                wrongHash,
                "REQUEST_OBLIGATION_COMPILED",
                0).put("canonicalSha256", "c".repeat(64));
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongHash),
                        scenario()));
    }

    @Test
    void typedRepairSequenceRejectsProofClaimsAndContradictoryTypedOutcomes() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new QualificationTraceEvidence.MutationRecoveryFact(
                        FAILURE_CALL_ID,
                        REPAIR_CALL_ID,
                        MutationRecoveryAssessment.Decision
                                .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                        true,
                        "trace.json#forged"));

        ObjectNode forgedVerifiedIdentity =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                forgedVerifiedIdentity,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0).put("verifiedRequestObligationSha256", OBLIGATION_SHA256);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(forgedVerifiedIdentity),
                        scenario()));

        ObjectNode forgedCoverage =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                forgedCoverage,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0).putArray("coveredSuccessfulMutationToolCallIds")
                .add(REPAIR_CALL_ID);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(forgedCoverage),
                        scenario()));

        ObjectNode unstructuredRepair =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                unstructuredRepair,
                "TOOL_OUTCOME_CLASSIFIED",
                1).put("mutationEvidenceKind", "EXACT_EDIT_REPLACEMENT");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(unstructuredRepair),
                        scenario()));

        ObjectNode oldStringMiss =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                oldStringMiss,
                "TOOL_OUTCOME_CLASSIFIED",
                0).put("failureReason", "EDIT_OLD_STRING_NOT_FOUND");
        eventData(
                oldStringMiss,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0).put("failureReason", "EDIT_OLD_STRING_NOT_FOUND");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(oldStringMiss),
                        scenario()));

        ObjectNode wrongErrorCode =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                wrongErrorCode,
                "TOOL_OUTCOME_CLASSIFIED",
                0).put("errorCode", ToolError.INTERNAL_ERROR);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongErrorCode),
                        scenario()));

        ObjectNode contradictorySuccess =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                contradictorySuccess,
                "TOOL_OUTCOME_CLASSIFIED",
                1).put("denied", true);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(contradictorySuccess),
                        scenario()));

        ObjectNode legacyOutcomeSchema =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        for (int occurrence = 0; occurrence < 2; occurrence++) {
            ObjectNode classification = eventData(
                    legacyOutcomeSchema,
                    "TOOL_OUTCOME_CLASSIFIED",
                    occurrence);
            classification.put("schemaVersion", 1);
            classification.remove("errorCode");
        }
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(legacyOutcomeSchema),
                        scenario()));
    }

    @Test
    void typedRepairSequenceRejectsAmbiguousFailureAndMutationCardinality() {
        ObjectNode secondEligibleFailure =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        insertToolOutcomeBeforeVerification(
                secondEligibleFailure,
                LATER_CALL_ID,
                "talos.edit_file",
                TARGET,
                3,
                false,
                true,
                false,
                "EDIT_EMPTY_ARGUMENTS",
                "NONE");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(secondEligibleFailure),
                        scenario()));

        ObjectNode secondSameTargetSuccess =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        insertToolOutcomeBeforeVerification(
                secondSameTargetSuccess,
                LATER_CALL_ID,
                "talos.write_file",
                TARGET,
                3,
                true,
                true,
                false,
                "NONE",
                "FULL_WRITE_REPLACEMENT");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(secondSameTargetSuccess),
                        scenario()));

        ObjectNode outsideRequiredTargets =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        insertToolOutcomeBeforeVerification(
                outsideRequiredTargets,
                LATER_CALL_ID,
                "talos.write_file",
                "other.md",
                3,
                true,
                true,
                false,
                "NONE",
                "FULL_WRITE_REPLACEMENT");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(outsideRequiredTargets),
                        scenario()));
    }

    @Test
    void typedRepairSequenceRequiresExactAuthoritativeVerificationTuple() {
        ObjectNode wrongClaimSource =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                wrongClaimSource,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0).put(
                        "verificationSource",
                        "DOCUMENT_EXTRACTION_TOOL_RESULT");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongClaimSource),
                        scenario()));

        ObjectNode wrongClaimStatus =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                wrongClaimStatus,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0).put("verificationStatus", "READBACK_ONLY");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongClaimStatus),
                        scenario()));

        ObjectNode wrongRootStatus =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        ((ObjectNode) wrongRootStatus.path("verification"))
                .put("status", "READBACK_ONLY");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongRootStatus),
                        scenario()));

        ObjectNode wrongEventStatus =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        eventData(
                wrongEventStatus,
                "VERIFICATION_COMPLETED",
                0).put("status", "READBACK_ONLY");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongEventStatus),
                        scenario()));
    }

    @Test
    void typedRepairSequenceRequiresStrictFailureRepairVerificationOrder() {
        ObjectNode failureClassificationAfterRepair =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        moveEventAfter(
                failureClassificationAfterRepair,
                "TOOL_OUTCOME_CLASSIFIED",
                0,
                "TOOL_CALL_PARSED",
                1);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(failureClassificationAfterRepair),
                        scenario()));

        ObjectNode repairClassificationAfterVerification =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        moveEventAfter(
                repairClassificationAfterVerification,
                "TOOL_OUTCOME_CLASSIFIED",
                1,
                "VERIFICATION_COMPLETED",
                0);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(repairClassificationAfterVerification),
                        scenario()));

        ObjectNode recoveryBeforeVerification =
                (ObjectNode) QualificationJson.tree(typedRepairSequenceTrace());
        moveEventAfter(
                recoveryBeforeVerification,
                "VERIFICATION_COMPLETED",
                0,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(recoveryBeforeVerification),
                        scenario()));
    }

    @Test
    void unknownRepairIdentityAndLaterMutationFailClosed() {
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(matchedRecoveryTrace(
                                true,
                                false,
                                "call-9-9")),
                        scenario()));
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(matchedRecoveryTrace(
                                true,
                                true,
                                REPAIR_CALL_ID)),
                        scenario()));
    }

    @Test
    void typedFailureReasonAndRepairEvidenceMustMatchTheRecoveryClaim() {
        ObjectNode wrongFailureReason =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        eventData(
                wrongFailureReason,
                "TOOL_OUTCOME_CLASSIFIED",
                0).put("failureReason", "EDIT_EMPTY_ARGUMENTS");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongFailureReason),
                        scenario()));

        ObjectNode unstructuredRepair =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        eventData(
                unstructuredRepair,
                "TOOL_OUTCOME_CLASSIFIED",
                1).put("mutationEvidenceKind", "NONE");
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(unstructuredRepair),
                        scenario()));
    }

    @Test
    void receiptCoverageAndPerTurnCallLineageFailClosed() {
        ObjectNode wrongCoverage =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        ArrayNode coverage = eventData(
                wrongCoverage,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0).putArray("coveredSuccessfulMutationToolCallIds");
        coverage.add(REPAIR_CALL_ID);
        coverage.add(FAILURE_CALL_ID);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongCoverage),
                        scenario()));

        ObjectNode wrongTurn =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        wrongTurn.put("turnNumber", 2);
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(wrongTurn),
                        scenario()));
    }

    @Test
    void outcomeOrdinalGapCannotReplaceButDoesNotConflictWithExactCallBinding()
            throws Exception {
        ObjectNode trace =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        eventData(
                trace,
                "TOOL_OUTCOME_CLASSIFIED",
                0).put("outcomeOrdinal", 2);
        eventData(
                trace,
                "TOOL_OUTCOME_CLASSIFIED",
                1).put("outcomeOrdinal", 3);
        ObjectNode recovery = eventData(
                trace,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0);
        recovery.put("failureOrdinal", 2);
        recovery.put("repairOrdinal", 3);

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(
                QualificationJson.bytes(trace),
                scenario());

        assertTrue(facts.mutationRecoveries().getFirst().proven());
    }

    @Test
    void recoveryOrdinalMustMatchTheTypedOutcomeOrdinal() {
        ObjectNode trace =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        eventData(
                trace,
                "TOOL_OUTCOME_CLASSIFIED",
                0).put("outcomeOrdinal", 2);
        eventData(
                trace,
                "TOOL_OUTCOME_CLASSIFIED",
                1).put("outcomeOrdinal", 3);

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario()));
    }

    @Test
    void deletingOneSuccessfulMutationClassificationFailsClosed() {
        ObjectNode trace =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, true, REPAIR_CALL_ID));
        removeEvent(trace, "TOOL_OUTCOME_CLASSIFIED", 2);

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario()));
    }

    @Test
    void duplicateTypedOutcomeForOneCallFailsClosed() {
        ObjectNode trace =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        duplicateEvent(trace, "TOOL_OUTCOME_CLASSIFIED", 0);

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario()));
    }

    @Test
    void typedRepairSequenceRejectsDuplicateSuccessClassificationIdentity() {
        ObjectNode trace =
                (ObjectNode) QualificationJson.tree(
                        typedRepairSequenceTrace());
        duplicateEvent(trace, "TOOL_OUTCOME_CLASSIFIED", 1);
        eventData(
                trace,
                "TOOL_OUTCOME_CLASSIFIED",
                2).put("outcomeOrdinal", 3);

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario()));
    }

    @Test
    void matchedRecoveryAfterTerminalOutcomeFailsClosed() {
        ObjectNode trace =
                (ObjectNode) QualificationJson.tree(
                        matchedRecoveryTrace(true, false, REPAIR_CALL_ID));
        moveEventToEnd(
                trace,
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                0);

        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        QualificationJson.bytes(trace),
                        scenario()));
    }

    @Test
    void deniedPolicyClaimBindsItsTypedDeniedOutcomeWithoutClaimingRecovery()
            throws Exception {
        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(
                QualificationJson.bytes(deniedPolicyTrace()),
                scenario());

        assertEquals(1, facts.calls().size());
        assertEquals(
                QualificationTraceEvidence.ApprovalDisposition.DENIED,
                facts.calls().getFirst().approvalDisposition());
        assertEquals(
                QualificationTraceEvidence.CallSettlement.BLOCKED,
                facts.calls().getFirst().settlement());
        assertTrue(facts.mutationRecoveries().isEmpty());
    }

    @Test
    void policyDenialDoesNotRequireAUserApprovalEvent() throws Exception {
        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(
                QualificationJson.bytes(policyDeniedTrace()),
                scenario());

        assertEquals(
                QualificationTraceEvidence.ApprovalDisposition.NONE,
                facts.calls().getFirst().approvalDisposition());
        assertEquals(
                QualificationTraceEvidence.CallSettlement.BLOCKED,
                facts.calls().getFirst().settlement());
        assertTrue(facts.mutationRecoveries().isEmpty());
    }

    @Test
    void recoveredFailureCanCoexistWithAnUnrelatedPartialFailure()
            throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(partialRecoveryTrace()),
                scenario());
        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);

        assertEquals(1, decision.provenMutationRecoveries().size());
        assertEquals(
                FAILURE_CALL_ID,
                decision.provenMutationRecoveries()
                        .getFirst()
                        .failureToolCallId());
        assertTrue(
                decision.ambiguousToolOutcome(),
                "the unrelated failure must remain visible after exact recovery");
    }

    @Test
    void provenRecoveryAddsRuntimeObservationWithoutTransformationFact()
            throws Exception {
        QualificationTraceEvidence.Facts trace = QualificationTraceEvidence.read(
                QualificationJson.bytes(matchedRecoveryTrace(true, false, REPAIR_CALL_ID)),
                scenario());
        QualificationCausalFinalizer.DecisionEvidence decision =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario(),
                        trace,
                        QualificationRecords.AnswerOrigin.NONE,
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract
                                .DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        true,
                        true,
                        false,
                        false,
                        false,
                        true,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        false,
                        decision);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        "T1154-recovery", "r1", facts);

        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.owner() == QualificationRecords.CausalOwner.RUNTIME_POLICY
                        && entry.stage()
                                == QualificationRecords.CausalStage.TOOL_EXECUTION
                        && entry.observedFact()
                                == QualificationRecords.CausalFact
                                        .CANONICAL_REQUIREMENTS_PASSED
                        && entry.evidenceReference().contains(
                                "MUTATION_FAILURE_RECOVERY_EVALUATED")
                        && !entry.firstCause()));
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.observedFact()
                        == QualificationRecords.CausalFact
                                .RUNTIME_TOOL_CALL_TRANSFORMATION_PROVEN));
    }

    private static QualificationProtocol.Scenario scenario() {
        return new QualificationProtocol.Scenario(
                "T1154-recovery",
                QualificationProtocol.BankKind.FULL,
                QualificationProtocol.Archetype.RECOVERY,
                QualificationProtocol.ScenarioCategory.REPAIR,
                "fixture.json",
                "b".repeat(64),
                "Change draft to final while preserving every other line.",
                QualificationProtocol.ApprovalBehavior.APPROVE_ONCE,
                QualificationRecords.ResponseOwnerContract
                        .DETERMINISTIC_RUNTIME_ALLOWED,
                List.of("SUCCEEDED"),
                List.of(TARGET),
                List.of(),
                List.of(new QualificationProtocol.ExpectedDecision(
                        "mutation",
                        QualificationProtocol.DecisionKind.REQUIRED_TOOL,
                        List.of("talos.edit_file", "talos.write_file"),
                        false)),
                List.of(new QualificationProtocol.AtomicRequirement(
                        "final-text",
                        "FILE_CONTAINS",
                        TARGET,
                        "final")));
    }

    private static Map<String, Object> matchedRecoveryTrace(
            boolean includeRecovery,
            boolean includeLaterMutation,
            String claimedRepairCallId) {
        return matchedRecoveryTrace(
                includeRecovery,
                includeLaterMutation,
                claimedRepairCallId,
                2,
                true);
    }

    private static Map<String, Object> matchedRecoveryTrace(
            boolean includeRecovery,
            boolean includeLaterMutation,
            String claimedRepairCallId,
            int recoverySchemaVersion,
            boolean includeTypedOutcomes) {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent());
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1));
        events.add(executedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1, false));
        if (includeTypedOutcomes) {
            events.add(classifiedOutcome(
                    FAILURE_CALL_ID,
                    1,
                    false,
                    true,
                    false,
                    "EDIT_OLD_STRING_NOT_FOUND",
                    "NONE"));
        }
        events.add(parsedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2));
        events.add(executedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2, true));
        if (includeTypedOutcomes) {
            events.add(classifiedOutcome(
                    REPAIR_CALL_ID,
                    2,
                    true,
                    true,
                    false,
                    "NONE",
                    "FULL_WRITE_REPLACEMENT"));
        }
        if (includeLaterMutation) {
            events.add(parsedTool(
                    LATER_CALL_ID, "talos.edit_file", TARGET, 3));
            events.add(executedTool(
                    LATER_CALL_ID, "talos.edit_file", TARGET, 3, true));
            if (includeTypedOutcomes) {
                events.add(classifiedOutcome(
                        LATER_CALL_ID,
                        3,
                        true,
                        true,
                        false,
                        "NONE",
                        "EXACT_EDIT_REPLACEMENT"));
            }
        }
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "PASSED", "problemCount", 0)));
        if (includeRecovery) {
            events.add(simpleEvent(
                    "MUTATION_FAILURE_RECOVERY_EVALUATED",
                    recoveryData(
                            recoverySchemaVersion,
                            1,
                            2,
                            FAILURE_CALL_ID,
                            claimedRepairCallId,
                            MutationRecoveryAssessment.Decision
                                    .RECOVERED_MATCHED_OBLIGATION.name(),
                            true,
                            true,
                            "EDIT_OLD_STRING_NOT_FOUND",
                            OBLIGATION_SHA256,
                            OBLIGATION_SHA256,
                            "POST_APPLY_STATIC",
                            "PASSED",
                            includeLaterMutation
                                    ? List.of(REPAIR_CALL_ID, LATER_CALL_ID)
                                    : List.of(REPAIR_CALL_ID))));
        }
        return trace(
                events,
                "PASSED",
                "COMPLETE",
                "SUCCEEDED",
                "COMPLETED_VERIFIED");
    }

    private static Map<String, Object> redundantOperationTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent());
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.write_file", TARGET, 1));
        events.add(executedTool(
                FAILURE_CALL_ID, "talos.write_file", TARGET, 1, true));
        events.add(classifiedOutcome(
                FAILURE_CALL_ID,
                1,
                true,
                true,
                false,
                "NONE",
                "FULL_WRITE_REPLACEMENT"));
        events.add(parsedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2));
        events.add(executedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2, false));
        events.add(classifiedOutcome(
                REPAIR_CALL_ID,
                2,
                false,
                true,
                false,
                "EDIT_OLD_STRING_NOT_FOUND",
                "NONE"));
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "NOT_RUN", "problemCount", 0)));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        2,
                        1,
                        REPAIR_CALL_ID,
                        FAILURE_CALL_ID,
                        MutationRecoveryAssessment.Decision
                                .REDUNDANT_ALREADY_APPLIED_OPERATION.name(),
                        true,
                        false,
                        "EDIT_OLD_STRING_NOT_FOUND",
                        OBLIGATION_SHA256,
                        "",
                        "NOT_RUN",
                        "NOT_RUN",
                        List.of())));
        return trace(events, "NOT_RUN", "PARTIAL", "PARTIAL", "PARTIAL");
    }

    private static Map<String, Object> deniedPolicyTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent());
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.write_file", TARGET, 1));
        events.add(lineageToolEvent(
                "APPROVAL_REQUIRED",
                FAILURE_CALL_ID,
                "talos.write_file",
                TARGET,
                1));
        events.add(lineageToolEvent(
                "APPROVAL_DENIED",
                FAILURE_CALL_ID,
                "talos.write_file",
                TARGET,
                1));
        events.add(lineageToolEvent(
                "TOOL_CALL_BLOCKED",
                FAILURE_CALL_ID,
                "talos.write_file",
                TARGET,
                1));
        events.add(classifiedOutcome(
                FAILURE_CALL_ID,
                1,
                false,
                true,
                true,
                "USER_APPROVAL_DENIED",
                "NONE"));
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "NOT_RUN", "problemCount", 0)));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        1,
                        0,
                        FAILURE_CALL_ID,
                        "",
                        MutationRecoveryAssessment.Decision
                                .DENIED_OR_POLICY.name(),
                        false,
                        false,
                        "USER_APPROVAL_DENIED",
                        OBLIGATION_SHA256,
                        "",
                        "NOT_RUN",
                        "NOT_RUN",
                        List.of())));
        return trace(events, "NOT_RUN", "PARTIAL", "DENIED", "PARTIAL");
    }

    private static Map<String, Object> policyDeniedTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent());
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.write_file", TARGET, 1));
        events.add(lineageToolEvent(
                "TOOL_CALL_BLOCKED",
                FAILURE_CALL_ID,
                "talos.write_file",
                TARGET,
                1));
        events.add(classifiedOutcome(
                FAILURE_CALL_ID,
                1,
                false,
                true,
                true,
                "PERMISSION_POLICY_DENIED",
                "NONE"));
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "NOT_RUN", "problemCount", 0)));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        1,
                        0,
                        FAILURE_CALL_ID,
                        "",
                        MutationRecoveryAssessment.Decision
                                .DENIED_OR_POLICY.name(),
                        false,
                        false,
                        "PERMISSION_POLICY_DENIED",
                        OBLIGATION_SHA256,
                        "",
                        "NOT_RUN",
                        "NOT_RUN",
                        List.of())));
        return trace(events, "NOT_RUN", "PARTIAL", "DENIED", "PARTIAL");
    }

    private static Map<String, Object> partialRecoveryTrace() {
        String unrelatedTarget = "unrelated.md";
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent());
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1));
        events.add(executedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1, false));
        events.add(classifiedOutcome(
                FAILURE_CALL_ID,
                1,
                false,
                true,
                false,
                "EDIT_OLD_STRING_NOT_FOUND",
                "NONE"));
        events.add(parsedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2));
        events.add(executedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2, true));
        events.add(classifiedOutcome(
                REPAIR_CALL_ID,
                2,
                true,
                true,
                false,
                "NONE",
                "FULL_WRITE_REPLACEMENT"));
        events.add(parsedTool(
                LATER_CALL_ID, "talos.edit_file", unrelatedTarget, 3));
        events.add(executedTool(
                LATER_CALL_ID, "talos.edit_file", unrelatedTarget, 3, false));
        events.add(classifiedOutcome(
                LATER_CALL_ID,
                3,
                false,
                true,
                false,
                "EDIT_OLD_STRING_AMBIGUOUS",
                "NONE"));
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "PASSED", "problemCount", 0)));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        1,
                        2,
                        FAILURE_CALL_ID,
                        REPAIR_CALL_ID,
                        MutationRecoveryAssessment.Decision
                                .RECOVERED_MATCHED_OBLIGATION.name(),
                        true,
                        true,
                        "EDIT_OLD_STRING_NOT_FOUND",
                        OBLIGATION_SHA256,
                        OBLIGATION_SHA256,
                        "POST_APPLY_STATIC",
                        "PASSED",
                        List.of(REPAIR_CALL_ID))));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        3,
                        0,
                        LATER_CALL_ID,
                        "",
                        MutationRecoveryAssessment.Decision
                                .FAILURE_NOT_ELIGIBLE.name(),
                        false,
                        false,
                        "EDIT_OLD_STRING_AMBIGUOUS",
                        unrelatedTarget,
                        OBLIGATION_SHA256,
                        OBLIGATION_SHA256,
                        "POST_APPLY_STATIC",
                        "PASSED",
                        List.of(REPAIR_CALL_ID))));
        return trace(events, "PASSED", "PARTIAL", "PARTIAL", "PARTIAL");
    }

    private static Map<String, Object> typedRepairSequenceTrace() {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent(
                "NOT_MIGRATED",
                List.of(TARGET)));
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1));
        events.add(executedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1, false));
        events.add(classifiedOutcome(
                FAILURE_CALL_ID,
                1,
                false,
                true,
                false,
                "EDIT_FULL_REWRITE_REQUIRED",
                "NONE"));
        events.add(parsedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2));
        events.add(executedTool(
                REPAIR_CALL_ID, "talos.write_file", TARGET, 2, true));
        events.add(classifiedOutcome(
                REPAIR_CALL_ID,
                2,
                true,
                true,
                false,
                "NONE",
                "NONE"));
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "PASSED", "problemCount", 0)));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        1,
                        2,
                        FAILURE_CALL_ID,
                        REPAIR_CALL_ID,
                        MutationRecoveryAssessment.Decision
                                .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                        true,
                        false,
                        "EDIT_FULL_REWRITE_REQUIRED",
                        OBLIGATION_SHA256,
                        "",
                        "POST_APPLY_STATIC",
                        "PASSED",
                        List.of())));
        return trace(
                events,
                "PASSED",
                "COMPLETE",
                "SUCCEEDED",
                "COMPLETED_VERIFIED");
    }

    private static Map<String, Object> typedEmptyRepairSequenceTrace(
            String readTarget
    ) {
        return typedEmptyRepairSequenceTrace(
                readTarget,
                "talos.edit_file",
                "EXACT_EDIT_REPLACEMENT");
    }

    private static Map<String, Object> typedEmptyRepairSequenceTrace(
            String readTarget,
            String repairToolName,
            String repairEvidenceKind
    ) {
        List<Map<String, Object>> events = new ArrayList<>();
        events.add(requestObligationEvent(
                "NOT_MIGRATED",
                List.of(TARGET)));
        events.add(parsedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1));
        events.add(executedTool(
                FAILURE_CALL_ID, "talos.edit_file", TARGET, 1, false));
        events.add(classifiedOutcome(
                FAILURE_CALL_ID,
                1,
                false,
                true,
                false,
                "EDIT_EMPTY_ARGUMENTS",
                "NONE"));
        events.add(parsedTool(
                REPAIR_CALL_ID, "talos.read_file", readTarget, 2));
        events.add(executedTool(
                REPAIR_CALL_ID, "talos.read_file", readTarget, 2, true));
        events.add(classifiedOutcome(
                REPAIR_CALL_ID,
                2,
                true,
                false,
                false,
                "NONE",
                "NONE"));
        events.add(parsedTool(
                LATER_CALL_ID, repairToolName, TARGET, 3));
        events.add(executedTool(
                LATER_CALL_ID, repairToolName, TARGET, 3, true));
        events.add(classifiedOutcome(
                LATER_CALL_ID,
                3,
                true,
                true,
                false,
                "NONE",
                repairEvidenceKind));
        events.add(simpleEvent(
                "VERIFICATION_COMPLETED",
                Map.of("status", "PASSED", "problemCount", 0)));
        events.add(simpleEvent(
                "MUTATION_FAILURE_RECOVERY_EVALUATED",
                recoveryData(
                        2,
                        1,
                        3,
                        FAILURE_CALL_ID,
                        LATER_CALL_ID,
                        MutationRecoveryAssessment.Decision
                                .RESOLVED_TYPED_REPAIR_SEQUENCE_UNPROVEN.name(),
                        true,
                        false,
                        "EDIT_EMPTY_ARGUMENTS",
                        OBLIGATION_SHA256,
                        "",
                        "POST_APPLY_STATIC",
                        "PASSED",
                        List.of())));
        return trace(
                events,
                "PASSED",
                "COMPLETE",
                "SUCCEEDED",
                "COMPLETED_VERIFIED");
    }

    private static Map<String, Object> requestObligationEvent() {
        return requestObligationEvent("COMPLETE", List.of(TARGET));
    }

    private static Map<String, Object> requestObligationEvent(
            String derivationStatus,
            List<String> requiredMutationTargets
    ) {
        return simpleEvent(
                "REQUEST_OBLIGATION_COMPILED",
                Map.of(
                        "canonicalSha256", OBLIGATION_SHA256,
                        "derivationStatus", derivationStatus,
                        "requiredMutationTargets",
                        List.copyOf(requiredMutationTargets)));
    }

    private static Map<String, Object> parsedTool(
            String toolCallId,
            String toolName,
            String path,
            int callOrdinal) {
        String toolNameHash = ProviderResponseCapture.toolNameHash(toolName);
        String argumentsHash = ProviderResponseCapture.canonicalArgumentsHash(
                Map.of("path", path, "ordinal", callOrdinal));
        Map<String, Object> authority = Map.ofEntries(
                Map.entry("schemaVersion", 1),
                Map.entry("status", "AUTHORIZED"),
                Map.entry("kind", "RUNTIME_DETERMINISTIC"),
                Map.entry("authorityId", sha256(
                        "trace-t1154" + '\0' + toolCallId
                                + '\0' + "RUNTIME_DETERMINISTIC")),
                Map.entry("toolNameHash", toolNameHash),
                Map.entry("argumentsHash", argumentsHash),
                Map.entry("runtimeReason", "DIRECT_RUNTIME_API"));
        return toolEvent(
                "TOOL_CALL_PARSED",
                toolName,
                path,
                Map.ofEntries(
                        Map.entry("toolCallId", toolCallId),
                        Map.entry("toolNameHash", toolNameHash),
                        Map.entry("argumentsHash", argumentsHash),
                        Map.entry("executionAuthority", authority)));
    }

    private static Map<String, Object> executedTool(
            String toolCallId,
            String toolName,
            String path,
            int callOrdinal,
            boolean success) {
        return toolEvent(
                "TOOL_EXECUTED",
                toolName,
                path,
                Map.ofEntries(
                        Map.entry("toolCallId", toolCallId),
                        Map.entry(
                                "toolNameHash",
                                ProviderResponseCapture.toolNameHash(toolName)),
                        Map.entry(
                                "argumentsHash",
                                ProviderResponseCapture.canonicalArgumentsHash(
                                        Map.of(
                                                "path", path,
                                                "ordinal", callOrdinal))),
                        Map.entry("success", success)));
    }

    private static Map<String, Object> lineageToolEvent(
            String type,
            String toolCallId,
            String toolName,
            String path,
            int callOrdinal) {
        return toolEvent(
                type,
                toolName,
                path,
                Map.ofEntries(
                        Map.entry("toolCallId", toolCallId),
                        Map.entry(
                                "toolNameHash",
                                ProviderResponseCapture.toolNameHash(toolName)),
                        Map.entry(
                                "argumentsHash",
                                ProviderResponseCapture.canonicalArgumentsHash(
                                        Map.of(
                                                "path", path,
                                                "ordinal", callOrdinal)))));
    }

    private static Map<String, Object> recoveryData(
            int schemaVersion,
            int failureOrdinal,
            int repairOrdinal,
            String failureToolCallId,
            String repairToolCallId,
            String decision,
            boolean resolved,
            boolean recovered,
            String failureReason,
            String requestObligationSha256,
            String verifiedRequestObligationSha256,
            String verificationSource,
            String verificationStatus,
            List<String> coveredSuccessfulMutationToolCallIds) {
        return recoveryData(
                schemaVersion,
                failureOrdinal,
                repairOrdinal,
                failureToolCallId,
                repairToolCallId,
                decision,
                resolved,
                recovered,
                failureReason,
                TARGET,
                requestObligationSha256,
                verifiedRequestObligationSha256,
                verificationSource,
                verificationStatus,
                coveredSuccessfulMutationToolCallIds);
    }

    private static Map<String, Object> recoveryData(
            int schemaVersion,
            int failureOrdinal,
            int repairOrdinal,
            String failureToolCallId,
            String repairToolCallId,
            String decision,
            boolean resolved,
            boolean recovered,
            String failureReason,
            String target,
            String requestObligationSha256,
            String verifiedRequestObligationSha256,
            String verificationSource,
            String verificationStatus,
            List<String> coveredSuccessfulMutationToolCallIds) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", schemaVersion);
        data.put("failureOrdinal", failureOrdinal);
        data.put("repairOrdinal", repairOrdinal);
        data.put("failureToolCallId", failureToolCallId);
        data.put("repairToolCallId", repairToolCallId);
        data.put("decision", decision);
        data.put("resolved", resolved);
        data.put("recovered", recovered);
        data.put("failureReason", failureReason);
        data.put("targetHint", target);
        data.put("targetSha256", sha256(target));
        data.put("requestObligationSha256", requestObligationSha256);
        data.put(
                "verifiedRequestObligationSha256",
                verifiedRequestObligationSha256);
        data.put("verificationSource", verificationSource);
        data.put("verificationStatus", verificationStatus);
        if (schemaVersion == 2) {
            data.put(
                    "coveredSuccessfulMutationToolCallIds",
                    List.copyOf(coveredSuccessfulMutationToolCallIds));
        }
        return Map.copyOf(data);
    }

    private static Map<String, Object> classifiedOutcome(
            String toolCallId,
            int outcomeOrdinal,
            boolean success,
            boolean mutating,
            boolean denied,
            String failureReason,
            String mutationEvidenceKind) {
        String errorCode = success
                ? ""
                : denied
                ? ToolError.DENIED
                : failureReason.startsWith("EDIT_")
                ? ToolError.INVALID_PARAMS
                : ToolError.INTERNAL_ERROR;
        return simpleEvent(
                "TOOL_OUTCOME_CLASSIFIED",
                Map.ofEntries(
                        Map.entry("schemaVersion", 2),
                        Map.entry("outcomeOrdinal", outcomeOrdinal),
                        Map.entry("toolCallId", toolCallId),
                        Map.entry("success", success),
                        Map.entry("mutating", mutating),
                        Map.entry("denied", denied),
                        Map.entry("errorCode", errorCode),
                        Map.entry("failureReason", failureReason),
                        Map.entry("mutationEvidenceKind", mutationEvidenceKind)));
    }

    private static Map<String, Object> trace(
            List<Map<String, Object>> events,
            String verificationStatus,
            String outcomeStatus,
            String mutationStatus,
            String classification) {
        List<Map<String, Object>> completeEvents = new ArrayList<>(events);
        completeEvents.add(simpleEvent(
                "OUTCOME_EVALUATED",
                Map.of(
                        "status", outcomeStatus,
                        "classification", classification)));
        return Map.ofEntries(
                Map.entry("schemaVersion", 5),
                Map.entry("traceId", "trace-t1154"),
                Map.entry("turnNumber", 1),
                Map.entry(
                        "model",
                        Map.of("backend", "llama_cpp", "model", "test-model")),
                Map.entry(
                        "taskContract",
                        Map.of(
                                "type", "FILE_EDIT",
                                "mutationAllowed", true,
                                "verificationRequired", true)),
                Map.entry(
                        "promptAudit",
                        Map.ofEntries(
                                Map.entry("taskType", "FILE_EDIT"),
                                Map.entry("mutationAllowed", true),
                                Map.entry(
                                        "actionObligation",
                                        "MUTATING_TOOL_REQUIRED"),
                                Map.entry("nativeTools", List.of()),
                                Map.entry("promptTools", List.of()))),
                Map.entry(
                        "toolSurface",
                        Map.of(
                                "nativeTools", List.of(),
                                "promptTools", List.of(),
                                "reason", "runtime authority test")),
                Map.entry("events", List.copyOf(completeEvents)),
                Map.entry("warnings", List.of()),
                Map.entry(
                        "verification",
                        Map.of("status", verificationStatus)),
                Map.entry(
                        "outcome",
                        Map.of(
                                "status", outcomeStatus,
                                "mutationStatus", mutationStatus,
                                "classification", classification)));
    }

    private static Map<String, Object> toolEvent(
            String type,
            String toolName,
            String path,
            Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pathHint", path);
        data.put(
                "canonicalToolNameHash",
                ProviderResponseCapture.toolNameHash(toolName));
        data.putAll(extra);
        return Map.of(
                "type", type,
                "timestamp", "2026-07-28T00:00:00Z",
                "phase", "APPLY",
                "toolName", toolName,
                "data", Map.copyOf(data));
    }

    private static Map<String, Object> simpleEvent(
            String type,
            Map<String, Object> data) {
        return Map.of(
                "type", type,
                "timestamp", "2026-07-28T00:00:00Z",
                "phase", "VERIFY",
                "toolName", "",
                "data", data);
    }

    private static ObjectNode eventData(
            ObjectNode trace,
            String type,
            int occurrence) {
        int seen = 0;
        for (var event : trace.withArray("events")) {
            if (type.equals(event.path("type").asText())) {
                if (seen == occurrence) {
                    return (ObjectNode) event.path("data");
                }
                seen++;
            }
        }
        throw new IllegalArgumentException(
                "missing event " + type + " occurrence " + occurrence);
    }

    private static void removeEvent(
            ObjectNode trace,
            String type,
            int occurrence) {
        ArrayNode events = trace.withArray("events");
        int seen = 0;
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText())) {
                if (seen == occurrence) {
                    events.remove(index);
                    return;
                }
                seen++;
            }
        }
        throw new IllegalArgumentException(
                "missing event " + type + " occurrence " + occurrence);
    }

    private static void duplicateEvent(
            ObjectNode trace,
            String type,
            int occurrence) {
        ArrayNode events = trace.withArray("events");
        int seen = 0;
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText())) {
                if (seen == occurrence) {
                    events.insert(index + 1, events.get(index).deepCopy());
                    return;
                }
                seen++;
            }
        }
        throw new IllegalArgumentException(
                "missing event " + type + " occurrence " + occurrence);
    }

    private static void insertToolOutcomeBeforeVerification(
            ObjectNode trace,
            String toolCallId,
            String toolName,
            String path,
            int callOrdinal,
            boolean success,
            boolean mutating,
            boolean denied,
            String failureReason,
            String mutationEvidenceKind
    ) {
        ArrayNode events = trace.withArray("events");
        int index = eventIndex(events, "VERIFICATION_COMPLETED", 0);
        events.insert(
                index++,
                QualificationJson.tree(parsedTool(
                        toolCallId,
                        toolName,
                        path,
                        callOrdinal)));
        events.insert(
                index++,
                QualificationJson.tree(executedTool(
                        toolCallId,
                        toolName,
                        path,
                        callOrdinal,
                        success)));
        events.insert(
                index,
                QualificationJson.tree(classifiedOutcome(
                        toolCallId,
                        callOrdinal,
                        success,
                        mutating,
                        denied,
                        failureReason,
                        mutationEvidenceKind)));
    }

    private static void moveEventToEnd(
            ObjectNode trace,
            String type,
            int occurrence) {
        ArrayNode events = trace.withArray("events");
        int seen = 0;
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText())) {
                if (seen == occurrence) {
                    var event = events.get(index).deepCopy();
                    events.remove(index);
                    events.add(event);
                    return;
                }
                seen++;
            }
        }
        throw new IllegalArgumentException(
                "missing event " + type + " occurrence " + occurrence);
    }

    private static void moveEventAfter(
            ObjectNode trace,
            String type,
            int occurrence,
            String targetType,
            int targetOccurrence
    ) {
        ArrayNode events = trace.withArray("events");
        int sourceIndex = eventIndex(events, type, occurrence);
        var event = events.get(sourceIndex).deepCopy();
        events.remove(sourceIndex);
        int targetIndex = eventIndex(events, targetType, targetOccurrence);
        events.insert(targetIndex + 1, event);
    }

    private static int eventIndex(
            ArrayNode events,
            String type,
            int occurrence
    ) {
        int seen = 0;
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText())) {
                if (seen == occurrence) return index;
                seen++;
            }
        }
        throw new IllegalArgumentException(
                "missing event " + type + " occurrence " + occurrence);
    }

    private static String sha256(String value) {
        try {
            return "sha256:" + HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(
                            value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
