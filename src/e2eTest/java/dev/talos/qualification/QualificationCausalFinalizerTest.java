package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.PromptAuditSnapshot;
import dev.talos.runtime.trace.TraceRedactionMode;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCausalFinalizerTest {
    @TempDir
    Path temp;

    @Test
    void mechanicalMatrixKeepsEveryOwnerDistinctAndFailsClosed() {
        List<Case> cases = List.of(
                new Case("model", facts(true, false, true, false, true, false,
                        false, false, false, false), QualificationRecords.CausalOwner.MODEL,
                        QualificationRecords.FailureClass.MODEL_FAILURE, true),
                new Case("runtime", facts(true, false, true, false, false, true,
                        false, false, false, false), QualificationRecords.CausalOwner.RUNTIME_POLICY,
                        QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT, false),
                new Case("verifier", facts(true, true, false, false, true, false,
                        true, false, false, false), QualificationRecords.CausalOwner.VERIFIER,
                        QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT, false),
                new Case("provider", facts(true, true, false, false, false, false,
                        false, true, false, false), QualificationRecords.CausalOwner.PROVIDER,
                        QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE, false),
                new Case("unknown-incomplete-evidence", facts(false, false, false, false, false, false,
                        false, false, false, false), QualificationRecords.CausalOwner.UNKNOWN,
                        QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE, false),
                new Case("hardware", facts(true, true, false, false, false, false,
                        false, true, true, false), QualificationRecords.CausalOwner.HARDWARE,
                        QualificationRecords.FailureClass.HARDWARE_FAILURE, false),
                new Case("operator", facts(true, true, false, false, false, false,
                        false, false, false, true), QualificationRecords.CausalOwner.OPERATOR,
                        QualificationRecords.FailureClass.OPERATOR_INTERRUPTION, true),
                new Case("mixed", facts(true, true, true, false, false, true,
                        true, false, false, false), QualificationRecords.CausalOwner.MIXED,
                        QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE, false),
                new Case("unknown", facts(true, true, false, false, false, false,
                        false, false, false, false), QualificationRecords.CausalOwner.UNKNOWN,
                        QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE, false));

        for (Case fixture : cases) {
            QualificationCausalFinalizer.Document document =
                    QualificationCausalFinalizer.derive("F-test", "r1", fixture.facts());

            assertEquals(fixture.owner(), document.causalOwner(), fixture.id());
            assertEquals(fixture.failureClass(), document.failureClass(), fixture.id());
            assertEquals(fixture.scoreEligible(), document.comparativeScoreEligible(), fixture.id());
            List<QualificationRecords.CausalLedgerEntry> first = document.causalLedger().stream()
                    .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                    .toList();
            long expectedFirstCauseCount = fixture.owner() == QualificationRecords.CausalOwner.MIXED
                    ? 2
                    : fixture.owner() == QualificationRecords.CausalOwner.UNKNOWN ? 0 : 1;
            assertEquals(expectedFirstCauseCount, first.size(), fixture.id());
            if (expectedFirstCauseCount == 1) {
                assertEquals(document.causalOwner(), first.getFirst().owner(), fixture.id());
                assertEquals(document.reasonCode(), first.getFirst().reasonCode(), fixture.id());
            }
        }
    }

    @Test
    void multipleMechanicallyObservedContributingOwnersRemainMixed() {
        List<MechanicalMixedCase> cases = List.of(
                new MechanicalMixedCase(
                        "provider-and-timeout",
                        new QualificationCausalFinalizer.MechanicalFacts(
                                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                                QualificationRecords.TrustGates.allPassed(),
                                false, false, true, false, false, false,
                                false, false, true, false, false),
                        QualificationRecords.CausalOwner.PROVIDER,
                        QualificationRecords.CausalOwner.RUNTIME_POLICY),
                new MechanicalMixedCase(
                        "hardware-and-timeout",
                        new QualificationCausalFinalizer.MechanicalFacts(
                                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                                QualificationRecords.TrustGates.allPassed(),
                                false, false, true, false, false, false,
                                false, false, true, true, false),
                        QualificationRecords.CausalOwner.HARDWARE,
                        QualificationRecords.CausalOwner.RUNTIME_POLICY),
                new MechanicalMixedCase(
                        "operator-and-runtime-failure",
                        new QualificationCausalFinalizer.MechanicalFacts(
                                QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED,
                                QualificationRecords.TrustGates.allPassed(),
                                true, true, false, false,
                                false, false, false,
                                false, true, false, false, true, false,
                                QualificationCausalFinalizer.DecisionEvidence.none()),
                        QualificationRecords.CausalOwner.OPERATOR,
                        QualificationRecords.CausalOwner.RUNTIME_POLICY));

        for (MechanicalMixedCase fixture : cases) {
            QualificationCausalFinalizer.Document document =
                    QualificationCausalFinalizer.derive("F-test", "r1", fixture.facts());

            assertEquals(QualificationRecords.CausalOwner.MIXED,
                    document.causalOwner(), fixture.id());
            assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    document.failureClass(), fixture.id());
            assertFalse(document.comparativeScoreEligible(), fixture.id());
            List<QualificationRecords.CausalOwner> owners = document.causalLedger().stream()
                    .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                    .map(QualificationRecords.CausalLedgerEntry::owner)
                    .toList();
            assertEquals(Set.of(fixture.firstOwner(), fixture.secondOwner()),
                    Set.copyOf(owners), fixture.id());
        }
    }

    @Test
    void hardwareCauseSubsumesItsProviderFailureButNotAContributingCause() {
        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        "F-test", "r1",
                        new QualificationCausalFinalizer.MechanicalFacts(
                                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                                QualificationRecords.TrustGates.allPassed(),
                                false, false, false, false, false, false,
                                false, false, true, true, false));

        assertEquals(QualificationRecords.CausalOwner.HARDWARE, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.HARDWARE_FAILURE,
                document.failureClass());
        List<QualificationRecords.CausalLedgerEntry> first = document.causalLedger().stream()
                .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                .toList();
        assertEquals(1, first.size());
        assertEquals(QualificationRecords.CausalOwner.HARDWARE,
                first.getFirst().owner());
    }

    @Test
    void satisfiedProviderOutcomeHasNoSyntheticFailureCause() {
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "F-test", "r1",
                facts(true, true, true, false, true, false,
                        false, false, false, false));

        assertEquals(QualificationRecords.FailureClass.NONE, document.failureClass());
        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER, document.answerOrigin());
        assertTrue(document.comparativeScoreEligible());
        assertEquals(0, document.causalLedger().stream()
                .filter(QualificationRecords.CausalLedgerEntry::firstCause).count());
    }

    @Test
    void failedProviderBoundDecisionIsModelFailureEvenWhenAnswerAtomicsPass() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", false,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false);
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "decision-failure", "r1",
                modelFacts(true, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.MODEL_FAILURE,
                document.failureClass());
        assertTrue(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.firstCause()
                        && entry.stage() == QualificationRecords.CausalStage.DECISION
                        && entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_FAILED));
    }

    @Test
    void providerBoundWrongDecisionCanOwnFailureWithoutMatchedTerminalProse() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", false,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        false, false, false,
                        false, false, false, false, false,
                        decisionEvidence);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive("decision-only", "r1", facts);

        assertEquals(QualificationRecords.AnswerOrigin.NONE, document.answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.MODEL_FAILURE,
                document.failureClass());
        assertTrue(document.comparativeScoreEligible());
    }

    @Test
    void providerBoundWrongDecisionIsModelOwnedWhenRuntimeMayAuthorTheFinalResponse() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", false,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        false, true, false,
                        false, false, false, false, false,
                        decisionEvidence);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        "runtime-response-model-decision-failure", "r1", facts);

        assertEquals(QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                document.answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.MODEL_FAILURE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.MODEL_DECISION_FAILED,
                document.reasonCode());
        assertTrue(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.firstCause()
                        && entry.stage() == QualificationRecords.CausalStage.DECISION
                        && entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_FAILED));
    }

    @Test
    void mechanicallyProvenRuntimeGenerationFailureIsRuntimeOwned() {
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false, false,
                        true, false, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none());

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive("runtime-failure", "r1", facts);

        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY,
                document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.RUNTIME_GENERATION_FAILED,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.firstCause()
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .RUNTIME_GENERATION_FAILURE_PROVEN));
    }

    @Test
    void precisePreAnswerFailuresRemainStructurallyValidWithoutUnknownDownstreamOwners() {
        List<QualificationCausalFinalizer.MechanicalFacts> cases = List.of(
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false,
                        false, false, true, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none()),
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false,
                        false, false, true, true, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none()),
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false,
                        false, true, false, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none()));

        for (int index = 0; index < cases.size(); index++) {
            QualificationCausalFinalizer.Document document =
                    QualificationCausalFinalizer.derive(
                            "pre-answer-failure-" + index, "r1", cases.get(index));
            QualificationRecords.RunResult result = runResult(document);

            QualificationCausalOwnership.Evaluation evaluation =
                    QualificationCausalOwnership.evaluateStructural(result);

            assertTrue(document.causalOwner() != QualificationRecords.CausalOwner.UNKNOWN);
            assertTrue(document.causalLedger().stream().noneMatch(entry ->
                    entry.owner() == QualificationRecords.CausalOwner.UNKNOWN));
            assertTrue(evaluation.structurallyComplete(), evaluation.reasonCodes().toString());
        }
    }

    @Test
    void failedTrustGateDoesNotInventRuntimePolicyAsItsCausalOwner() {
        QualificationRecords.TrustGates failedMutationScope =
                new QualificationRecords.TrustGates(
                        true, false, true, true, true, true, true, true);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        failedMutationScope,
                        true, true, false, false,
                        true, false, false,
                        false, false, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none());

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive("trust-owner", "r1", facts);

        assertUnknownIncomplete(
                document,
                QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN);
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.observedFact()
                        == QualificationRecords.CausalFact.TRUST_GATE_MUTATION_SCOPE_FAILED
                        && entry.owner() == QualificationRecords.CausalOwner.UNKNOWN));
    }

    @Test
    void causalDocumentRejectsAnySchemaNameOtherThanTheCanonicalV2Schema() {
        QualificationCausalFinalizer.Document valid = QualificationCausalFinalizer.derive(
                "schema", "r1",
                facts(true, true, true, false, true, false,
                        false, false, false, false));

        assertThrows(IllegalArgumentException.class, () ->
                new QualificationCausalFinalizer.Document(
                        "talos.modelQualification.notCausalFinalization",
                        valid.schemaVersion(), valid.scenarioId(), valid.repetitionId(),
                        valid.evidenceIdentity(), valid.failureClass(), valid.answerOrigin(),
                        valid.responseOwnerContract(), valid.causalOwner(), valid.reasonCode(),
                        valid.comparativeScoreEligible(), valid.trustGates(),
                        valid.requirements(), valid.canaryPassed(), valid.evidenceComplete(),
                        valid.causalLedger()));
    }

    @Test
    void wrongModelDecisionAndLaterProviderFailureRemainMixed() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", false,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, false, false, false,
                        false, false, false,
                        false, false, true, false, false, false,
                        decisionEvidence);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        "model-decision-plus-provider-failure", "r1", facts);

        assertEquals(QualificationRecords.CausalOwner.MIXED, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.MULTIPLE_CONTRIBUTING_OWNERS,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertEquals(Set.of(
                        QualificationRecords.CausalOwner.MODEL,
                        QualificationRecords.CausalOwner.PROVIDER),
                document.causalLedger().stream()
                        .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                        .map(QualificationRecords.CausalLedgerEntry::owner)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void operatorOriginOutsideOperatorOwnedContractIsMixedAndNotScoreable() {
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        false, false, false,
                        false, false, false, false, true, false,
                        QualificationCausalFinalizer.DecisionEvidence.none());

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        "operator-origin-wrong-contract", "r1", facts);

        assertEquals(QualificationRecords.CausalOwner.MIXED, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
        assertEquals(Set.of(
                        QualificationRecords.CausalOwner.OPERATOR,
                        QualificationRecords.CausalOwner.RUNTIME_POLICY),
                document.causalLedger().stream()
                        .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                        .map(QualificationRecords.CausalLedgerEntry::owner)
                        .collect(java.util.stream.Collectors.toSet()));
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.firstCause()
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .RESPONSE_OWNER_CONTRACT_VIOLATED));
    }

    @Test
    void unboundDecisionOriginRemainsUnknownAndHasNoForgedFirstCause() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", false,
                                QualificationRecords.CausalOwner.UNKNOWN,
                                "trace.json#unbound-decision")),
                        false);
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "decision-unknown", "r1",
                modelFacts(false, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason
                        .DECISION_ORIGIN_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.owner() == QualificationRecords.CausalOwner.UNKNOWN
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .SCORED_DECISION_ORIGIN_NOT_PROVEN));
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_PASSED
                        || entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_FAILED));
    }

    @Test
    void ambiguousToolSettlementPreventsLaterProviderProseFromTakingModelBlame() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        true);
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "tool-outcome-unknown", "r1",
                modelFacts(false, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason
                        .TOOL_OUTCOME_CAUSE_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.stage() == QualificationRecords.CausalStage.TOOL_EXECUTION
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .TOOL_OUTCOME_CAUSAL_OWNER_NOT_PROVEN
                        && !entry.firstCause()));
    }

    @Test
    void runtimeTransformationBeforeFailedUtilityBlocksUnsupportedModelOnlyBlame() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false,
                        true);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "transformed-utility-unknown", "r1",
                modelFacts(false, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.firstCause()
                        && entry.owner() == QualificationRecords.CausalOwner.MODEL));
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                !entry.firstCause()
                        && entry.owner() == QualificationRecords.CausalOwner.RUNTIME_POLICY
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .RUNTIME_TOOL_CALL_TRANSFORMATION_PROVEN));
    }

    @Test
    void runtimeToolTransformationBeforeSuccessfulUtilityBlocksUnsupportedCredit() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false,
                        true);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "tool-transformed-utility-pass-unknown", "r1",
                modelFacts(true, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason
                        .UTILITY_OWNER_UNPROVEN_AFTER_RUNTIME_TOOL_TRANSFORMATION,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().noneMatch(
                QualificationRecords.CausalLedgerEntry::firstCause));
    }

    @Test
    void wrongRawModelDecisionRemainsModelOwnedAfterRuntimeToolTransformation() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", false,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false,
                        true);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "wrong-raw-tool-transformed", "r1",
                modelFacts(true, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.MODEL_FAILURE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.MODEL_DECISION_FAILED,
                document.reasonCode());
        assertTrue(document.comparativeScoreEligible());
    }

    @Test
    void semanticAnswerTransformationBeforeFailedUtilityBlocksModelFailure() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "grounded-answer", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-response")),
                        false,
                        false,
                        true);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "answer-transformed-utility-unknown", "r1",
                modelFacts(false, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.failureClass()
                != QualificationRecords.FailureClass.MODEL_FAILURE);
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.firstCause()
                        && entry.owner() == QualificationRecords.CausalOwner.MODEL));
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                !entry.firstCause()
                        && entry.owner() == QualificationRecords.CausalOwner.RUNTIME_POLICY
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .RUNTIME_ANSWER_TRANSFORMATION_PROVEN));
    }

    @Test
    void semanticAnswerTransformationBeforeSuccessfulUtilityBlocksUnsupportedModelCredit() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "grounded-answer", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-response")),
                        false,
                        false,
                        true);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "answer-transformed-utility-pass-unknown", "r1",
                modelFacts(true, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason
                        .UTILITY_OWNER_UNPROVEN_AFTER_RUNTIME_ANSWER_TRANSFORMATION,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().noneMatch(
                QualificationRecords.CausalLedgerEntry::firstCause));
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                !entry.firstCause()
                        && entry.owner() == QualificationRecords.CausalOwner.RUNTIME_POLICY
                        && entry.observedFact() == QualificationRecords.CausalFact
                                .RUNTIME_ANSWER_TRANSFORMATION_PROVEN));
    }

    @Test
    void providerAnswerTransformationAlsoBlocksModelCreditWhenRuntimeResponseIsAllowed() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "grounded-answer", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-response")),
                        false,
                        false,
                        true);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        true, false, false,
                        false, false, false, false, false,
                        decisionEvidence);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "runtime-allowed-provider-answer-transformed", "r1", facts);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
    }

    @Test
    void runtimeOwnedAnswerTransformationDoesNotObscureRuntimeOwnedSuccess() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(), false, false, true);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        false, true, false,
                        false, false, false, false, false,
                        decisionEvidence);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "runtime-answer-transformed", "r1", facts);

        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.NONE, document.failureClass());
        assertTrue(document.comparativeScoreEligible());
    }

    @Test
    void deterministicTerminalAnswerCannotOwnFailedFileRequirementWithoutCausalProof() {
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "deterministic-answer-file-failure", "r1",
                requirementFailureFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        QualificationCausalFinalizer.RequirementDomain.FILE));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason
                        .REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().noneMatch(
                QualificationRecords.CausalLedgerEntry::firstCause));
    }

    @Test
    void modelTerminalAnswerCannotOwnFailedFileRequirementWithoutCausalProof() {
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "model-answer-file-failure", "r1",
                requirementFailureFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        QualificationCausalFinalizer.RequirementDomain.FILE));

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason
                         .REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.observedFact() == QualificationRecords.CausalFact
                        .REQUIREMENT_DOMAIN_CAUSAL_OWNER_NOT_PROVEN
                        && entry.evidenceReference().equals(
                                "mechanical-requirements.json#failed-non-answer-domains")));
    }

    @Test
    void wrongModelDecisionCannotHideUnownedFileRequirementFailure() {
        QualificationCausalFinalizer.DecisionEvidence decision =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "wrong-tool", false,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, true, false, false,
                        true, false, false,
                        false, false, false, false, false, false,
                        decision,
                        Set.of(QualificationCausalFinalizer.RequirementDomain.FILE));

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "model-decision-plus-file", "r1", facts);

        assertUnknownIncomplete(document,
                QualificationRecords.CausalReason.REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN);
    }

    @Test
    void runtimeTrustFailureCannotHideUnownedTraceRequirementFailure() {
        QualificationRecords.TrustGates trust = new QualificationRecords.TrustGates(
                true, true, true, false, true, true, true, true);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        trust,
                        false, true, false, false,
                        false, true, false,
                        false, false, false, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none(),
                        Set.of(QualificationCausalFinalizer.RequirementDomain.TRACE));

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "runtime-trust-plus-trace", "r1", facts);

        assertUnknownIncomplete(document,
                QualificationRecords.CausalReason.REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN);
    }

    @Test
    void knownProviderCauseCannotHideMixedUnownedFileAndTraceRequirements() {
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false,
                        false, false, true, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none(),
                        Set.of(
                                QualificationCausalFinalizer.RequirementDomain.FILE,
                                QualificationCausalFinalizer.RequirementDomain.TRACE));

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "provider-plus-unowned-domains", "r1", facts);

        assertUnknownIncomplete(document,
                QualificationRecords.CausalReason.REQUIREMENT_OWNER_NOT_MECHANICALLY_PROVEN);
    }

    @Test
    void unboundDecisionRemainsUnknownEvenWithIndependentRuntimeTrustFailure() {
        QualificationRecords.TrustGates trust = new QualificationRecords.TrustGates(
                true, true, true, false, true, true, true, true);
        QualificationCausalFinalizer.DecisionEvidence decision =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "unbound", false,
                                QualificationRecords.CausalOwner.UNKNOWN,
                                "trace.json#unbound-decision")),
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        trust,
                        true, true, false, false,
                        true, false, false,
                        false, false, false, false, false, false,
                        decision,
                        Set.of());

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "unbound-plus-runtime-trust", "r1", facts);

        assertUnknownIncomplete(document,
                QualificationRecords.CausalReason.DECISION_ORIGIN_NOT_MECHANICALLY_PROVEN);
    }

    @Test
    void ambiguousToolSettlementRemainsUnknownEvenWithProviderFailure() {
        QualificationCausalFinalizer.DecisionEvidence decision =
                new QualificationCausalFinalizer.DecisionEvidence(List.of(), true);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false,
                        false, false, true, false, false, false,
                        decision,
                        Set.of(QualificationCausalFinalizer.RequirementDomain.ANSWER));

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "ambiguous-tool-plus-provider", "r1", facts);

        assertUnknownIncomplete(document,
                QualificationRecords.CausalReason.TOOL_OUTCOME_CAUSE_NOT_MECHANICALLY_PROVEN);
    }

    @Test
    void modelAnswerFailureAndIndependentTrustGateFailureRemainUnknown() {
        QualificationRecords.TrustGates trust = new QualificationRecords.TrustGates(
                true, true, true, false, true, true, true, true);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        trust,
                        false, true, false, false,
                        true, false, false,
                        false, false, false, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none(),
                        Set.of(QualificationCausalFinalizer.RequirementDomain.ANSWER));

        assertUnknownIncomplete(
                QualificationCausalFinalizer.derive(
                        "model-answer-plus-unowned-trust", "r1", facts),
                QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN);
    }

    @Test
    void providerFailureAndIndependentDeterministicAnswerFailureRemainMixed() {
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, true, false,
                        false, false, true, false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none(),
                        Set.of(QualificationCausalFinalizer.RequirementDomain.ANSWER));

        assertMixedOwners(
                QualificationCausalFinalizer.derive(
                        "provider-plus-runtime-answer", "r1", facts),
                Set.of(
                        QualificationRecords.CausalOwner.PROVIDER,
                        QualificationRecords.CausalOwner.RUNTIME_POLICY));
    }

    @Test
    void deterministicTerminalAnswerOwnsItsFailedAnswerRequirement() {
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "deterministic-answer-answer-failure", "r1",
                requirementFailureFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        QualificationCausalFinalizer.RequirementDomain.ANSWER));

        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT,
                document.failureClass());
    }

    @Test
    void untransformedModelAnswerOwnsItsFailedAnswerRequirement() {
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "model-answer-answer-failure", "r1",
                requirementFailureFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        QualificationCausalFinalizer.RequirementDomain.ANSWER));

        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.MODEL_FAILURE,
                document.failureClass());
        assertTrue(document.comparativeScoreEligible());
    }

    @Test
    void presentationRoundTripDoesNotObscureAnOtherwiseProvenModelFailure() {
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "grounded-answer", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-response")),
                        false,
                        false,
                        false);

        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "presentation-round-trip-model-failure", "r1",
                modelFacts(false, decisionEvidence));

        assertEquals(QualificationRecords.CausalOwner.MODEL, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.MODEL_FAILURE,
                document.failureClass());
        assertTrue(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.observedFact() == QualificationRecords.CausalFact
                        .RUNTIME_ANSWER_TRANSFORMATION_PROVEN));
    }

    @Test
    void exactNativeProposalLinkDistinguishesModelDecisionFromUnboundCall() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();
        String wrongTool = "talos.write_file";
        if (expected.allowedTools().contains(wrongTool)) wrongTool = "talos.grep";
        QualificationRecords.DecisionResult wrong =
                new QualificationRecords.DecisionResult(expected.id(), false);
        QualificationTraceEvidence.Call linked = new QualificationTraceEvidence.Call(
                wrongTool, "README.md", true, false, List.of("README.md"),
                QualificationTraceEvidence.ApprovalDisposition.NONE,
                "generation-decision", "attempt-decision", true,
                QualificationTraceEvidence.CallSettlement.EXECUTED_SUCCESS);
        QualificationTraceEvidence.Call unbound = new QualificationTraceEvidence.Call(
                wrongTool, "README.md", true, false, List.of("README.md"));

        QualificationCausalFinalizer.DecisionEvidence linkedEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario, traceFacts(
                                List.of(linked), List.of(wrong), selectedProviderLedger(true)),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER, false);
        QualificationCausalFinalizer.DecisionEvidence unboundEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario, traceFacts(List.of(unbound), List.of(wrong)),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER, false);

        assertEquals(QualificationRecords.CausalOwner.MODEL,
                linkedEvidence.observations().getFirst().owner());
        assertTrue(linkedEvidence.complete());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                unboundEvidence.observations().getFirst().owner());
        assertFalse(unboundEvidence.complete());

        Set<String> unavailableSurface = Set.of(wrongTool);
        QualificationCausalFinalizer.DecisionEvidence unavailableEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(
                                List.of(linked),
                                List.of(wrong),
                                selectedProviderLedger(true, unavailableSurface),
                                unavailableSurface),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                unavailableEvidence.observations().getFirst().owner());
        assertFalse(unavailableEvidence.complete());
    }

    @Test
    void nativeToolCallOpportunityIsBoundToTheCallsExactGenerationAndAttempt() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();
        String requiredTool = expected.allowedTools().getFirst();
        String otherTool = expected.allowedTools().contains("talos.write_file")
                ? "talos.grep"
                : "talos.write_file";
        QualificationTraceEvidence.Call firstGenerationCall =
                new QualificationTraceEvidence.Call(
                        otherTool, "README.md", true, false, List.of("README.md"),
                        QualificationTraceEvidence.ApprovalDisposition.NONE,
                        "generation-first", "attempt-first", true,
                        QualificationTraceEvidence.CallSettlement.EXECUTED_SUCCESS);
        QualificationRecords.DecisionResult wrong =
                new QualificationRecords.DecisionResult(expected.id(), false);

        QualificationCausalFinalizer.DecisionEvidence cannotBorrowLaterSurface =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(
                                List.of(firstGenerationCall),
                                List.of(wrong),
                                twoGenerationProviderLedger(
                                        Set.of(otherTool), Set.of(requiredTool)),
                                Set.of(requiredTool)),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);
        QualificationCausalFinalizer.DecisionEvidence retainsExactEarlierSurface =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(
                                List.of(firstGenerationCall),
                                List.of(wrong),
                                twoGenerationProviderLedger(
                                        Set.of(requiredTool), Set.of(otherTool)),
                                Set.of(requiredTool)),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                cannotBorrowLaterSurface.observations().getFirst().owner());
        assertFalse(cannotBorrowLaterSurface.complete());
        assertEquals(QualificationRecords.CausalOwner.MODEL,
                retainsExactEarlierSurface.observations().getFirst().owner());
        assertTrue(retainsExactEarlierSurface.complete());
    }

    @Test
    void requiredToolOmissionByMatchedProviderAnswerIsModelOwned() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();

        QualificationCausalFinalizer.DecisionEvidence evidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(List.of(), List.of(
                                new QualificationRecords.DecisionResult(expected.id(), false)),
                                selectedProviderLedger(false)),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);

        assertEquals(QualificationRecords.CausalOwner.MODEL,
                evidence.observations().getFirst().owner());
        assertFalse(evidence.observations().getFirst().correct());
        assertTrue(evidence.complete());
    }

    @Test
    void requiredToolOmissionRequiresMechanicallySelectedToolOpportunity()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();
        String unavailable = "talos.write_file";
        if (expected.allowedTools().contains(unavailable)) unavailable = "talos.delete_path";

        QualificationTestPacket excluded = QualificationTestPacket.create(
                temp.resolve("required-tool-surface-excluded"), catalog, scenario);
        setToolSurface(excluded, List.of(unavailable));
        QualificationTraceEvidence.Facts excludedTrace = QualificationTraceEvidence.read(
                excluded.runRoot().resolve("trace.json"), scenario);
        QualificationCausalFinalizer.DecisionEvidence excludedEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario, excludedTrace,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER, false);

        QualificationTestPacket included = QualificationTestPacket.create(
                temp.resolve("required-tool-surface-included"), catalog, scenario);
        setToolSurface(included, List.of(expected.allowedTools().getFirst()));
        setDispatchedNativeTools(included, List.of(expected.allowedTools().getFirst()));
        QualificationTraceEvidence.Facts includedTrace = QualificationTraceEvidence.read(
                included.runRoot().resolve("trace.json"), scenario);
        QualificationCausalFinalizer.DecisionEvidence includedEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario, includedTrace,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER, false);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                excludedEvidence.observations().getFirst().owner());
        assertFalse(excludedEvidence.complete());
        assertEquals(QualificationRecords.CausalOwner.MODEL,
                includedEvidence.observations().getFirst().owner());
        assertTrue(includedEvidence.complete());

        QualificationTestPacket legacyUnbound = QualificationTestPacket.create(
                temp.resolve("required-tool-legacy-unbound"), catalog, scenario);
        setToolSurface(legacyUnbound, List.of(expected.allowedTools().getFirst()));
        setLegacyUnboundDispatch(legacyUnbound);
        QualificationCausalFinalizer.DecisionEvidence legacyUnboundEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        QualificationTraceEvidence.read(
                                legacyUnbound.runRoot().resolve("trace.json"), scenario),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                legacyUnboundEvidence.observations().getFirst().owner());
        assertFalse(legacyUnboundEvidence.complete());

        QualificationTestPacket nativeDisappeared = QualificationTestPacket.create(
                temp.resolve("required-tool-native-disappeared"), catalog, scenario);
        setToolSurface(nativeDisappeared, List.of(expected.allowedTools().getFirst()));
        setDispatchedNativeTools(nativeDisappeared, List.of());
        assertThrows(
                IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(
                        nativeDisappeared.runRoot().resolve("trace.json"), scenario));

        QualificationTestPacket promptOnly = QualificationTestPacket.create(
                temp.resolve("required-tool-prompt-only"), catalog, scenario);
        setToolSurface(
                promptOnly, List.of(), List.of(expected.allowedTools().getFirst()));
        setDispatchedNativeTools(promptOnly, List.of());
        QualificationCausalFinalizer.DecisionEvidence promptOnlyEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        QualificationTraceEvidence.read(
                                promptOnly.runRoot().resolve("trace.json"), scenario),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                promptOnlyEvidence.observations().getFirst().owner());
        assertFalse(promptOnlyEvidence.complete());
    }

    @Test
    void productionUnboundDispatchRemainsIncompleteThroughRunLevelFinalization()
            throws Exception {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();
        String requiredTool = expected.allowedTools().getFirst();
        String answer = "No tool call was made.";
        Path tracePath = temp.resolve("production-unbound-trace.json");

        LocalTurnTraceCapture.begin(
                "trace-production-unbound",
                "session-production-unbound",
                1,
                "2026-07-16T00:00:00Z",
                "workspace",
                "auto",
                "test",
                "model",
                "exercise legacy unbound dispatch");
        try {
            LocalTurnTraceCapture.recordPolicyTrace(new TurnPolicyTrace(
                    "READ_ONLY_QA",
                    false,
                    false,
                    List.of(),
                    List.of(),
                    "INSPECT",
                    "INSPECT",
                    List.of(requiredTool),
                    List.of(),
                    List.of(),
                    "qualification unbound dispatch"));
            LocalTurnTraceCapture.recordPromptAudit(new PromptAuditSnapshot(
                    1,
                    "READ_ONLY_QA",
                    false,
                    false,
                    "INSPECT",
                    "INSPECT",
                    "READ_ONLY",
                    "NONE",
                    "NOT_DERIVED",
                    "NONE_OR_NOT_DERIVED",
                    "NONE_OR_NOT_DERIVED",
                    "NONE_OR_NOT_DERIVED",
                    "NOT_DERIVED",
                    0,
                    true,
                    "AFTER_HISTORY_BEFORE_USER",
                    "frame-hash",
                    "[CurrentTurnCapability]",
                    1,
                    1,
                    2,
                    "prompt-hash",
                    List.of(requiredTool),
                    List.of(),
                    List.of(),
                    TraceRedactionMode.DEFAULT,
                    "NOT_DERIVED"));
            ProviderResponseCapture.Generation generation =
                    ProviderResponseCapture.openGeneration(
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Purpose.TURN_RESPONSE);
            ProviderResponseCapture.Attempt attempt = generation.startAttempt(
                    ProviderResponseCapture.AttemptKind.PRIMARY,
                    ProviderResponseCapture.RetryReason.NONE);
            attempt.requestDispatched(
                    ProviderTransportHandoff.DispatchEvidence.fromProviderBody(
                            "production-unbound-provider-body",
                            List.of()));
            ProviderResponseCapture.Receipt receipt = attempt.respond(
                    new ProviderResponseCapture.Response(
                            answer,
                            List.of(),
                            ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                            ProviderResponseCapture.Completion.PROVIDER_EOS,
                            false,
                            false,
                            false));
            generation.complete(receipt);
            LocalTurnTraceCapture.recordProviderAnswerSelected(answer, receipt);
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(answer);
            LocalTurnTraceCapture.recordOutcome(
                    "FAILED",
                    "PASSED",
                    "NOT_REQUIRED",
                    "NONE",
                    scenario.expectedTerminalStates().getFirst());
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            QualificationJson.write(tracePath, trace);
        } finally {
            LocalTurnTraceCapture.clear();
        }

        byte[] traceBytes = Files.readAllBytes(tracePath);
        QualificationTraceEvidence.Facts traceFacts =
                QualificationTraceEvidence.read(traceBytes, scenario);
        QualificationRecords.AnswerOrigin origin =
                QualificationCausalOwnership.matchedAnswerOrigin(
                        answer.getBytes(StandardCharsets.UTF_8),
                        traceBytes,
                        QualificationProtocol.VERSION);
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario, traceFacts, origin, false);
        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        scenario.id(), "r1", modelFacts(false, decisionEvidence));

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER, origin);
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                decisionEvidence.observations().getFirst().owner());
        assertFalse(decisionEvidence.complete());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.DECISION_ORIGIN_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
    }

    @Test
    void noToolAbstentionRequiresANonemptyMechanicallySelectedToolOpportunity()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.NO_TOOL))
                .findFirst()
                .orElseThrow();

        QualificationTestPacket excluded = QualificationTestPacket.create(
                temp.resolve("no-tool-surface-excluded"), catalog, scenario);
        setToolSurface(excluded, List.of());
        QualificationCausalFinalizer.DecisionEvidence excludedEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        QualificationTraceEvidence.read(
                                excluded.runRoot().resolve("trace.json"), scenario),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);

        QualificationTestPacket included = QualificationTestPacket.create(
                temp.resolve("no-tool-surface-included"), catalog, scenario);
        setToolSurface(included, List.of("talos.read_file"));
        setDispatchedNativeTools(included, List.of("talos.read_file"));
        QualificationCausalFinalizer.DecisionEvidence includedEvidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        QualificationTraceEvidence.read(
                                included.runRoot().resolve("trace.json"), scenario),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                excludedEvidence.observations().getFirst().owner());
        assertFalse(excludedEvidence.complete());
        assertEquals(QualificationRecords.CausalOwner.MODEL,
                includedEvidence.observations().getFirst().owner());
        assertTrue(includedEvidence.complete());
    }

    @Test
    void requiredToolOmissionRemainsModelOwnedWhenRuntimeAuthorsTerminalAnswer() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();

        QualificationCausalFinalizer.DecisionEvidence evidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(List.of(), List.of(
                                new QualificationRecords.DecisionResult(expected.id(), false)),
                                selectedProviderLedger(false)),
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        false);

        assertEquals(QualificationRecords.CausalOwner.MODEL,
                evidence.observations().getFirst().owner());
        assertFalse(evidence.observations().getFirst().correct());
        assertTrue(evidence.complete());
    }

    @Test
    void suppressedProviderToolProposalRemainsModelOwnedWhenRuntimeAuthorsTerminalAnswer() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .full().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.NO_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();

        QualificationCausalFinalizer.DecisionEvidence evidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(List.of(), List.of(
                                new QualificationRecords.DecisionResult(expected.id(), false)),
                                selectedProviderLedger(true)),
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        false);

        assertEquals(QualificationRecords.CausalOwner.MODEL,
                evidence.observations().getFirst().owner());
        assertFalse(evidence.observations().getFirst().correct());
        assertTrue(evidence.complete());
    }

    @Test
    void deterministicAnswerCannotOwnToolDecisionWithoutSelectedProviderDecision() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();

        QualificationCausalFinalizer.DecisionEvidence evidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(List.of(), List.of(
                                new QualificationRecords.DecisionResult(expected.id(), false))),
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        false);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                evidence.observations().getFirst().owner());
        assertFalse(evidence.complete());
    }

    @Test
    void unparsedProviderToolResponseCannotBeMisclassifiedAsModelOmission() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();

        QualificationCausalFinalizer.DecisionEvidence evidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(List.of(), List.of(
                                new QualificationRecords.DecisionResult(expected.id(), false)),
                                selectedProviderLedger(true)),
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        false);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                evidence.observations().getFirst().owner());
        assertFalse(evidence.complete());
    }

    @Test
    void laterFailedForegroundGenerationCannotResurrectOlderProviderDecision() {
        QualificationProtocol.Scenario scenario = QualificationTestPacket.protocol6Catalog()
                .admission().scenarios().stream()
                .filter(candidate -> candidate.expectedDecisions().stream().anyMatch(decision ->
                        decision.scored()
                                && decision.kind()
                                        == QualificationProtocol.DecisionKind.REQUIRED_TOOL))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.ExpectedDecision expected = scenario.expectedDecisions().stream()
                .filter(QualificationProtocol.ExpectedDecision::scored)
                .findFirst()
                .orElseThrow();
        QualificationProviderAttemptLedger.Ledger earlier = selectedProviderLedger(false);
        QualificationProviderAttemptLedger.Generation laterFailure =
                new QualificationProviderAttemptLedger.Generation(
                        "generation-later-failure",
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        List.of(), "GENERATION_FAILED", "", 0,
                        false, false,
                        ProviderResponseCapture.Failure.PROVIDER_EXCEPTION,
                        null, 4, 5);
        QualificationProviderAttemptLedger.Ledger ledger =
                new QualificationProviderAttemptLedger.Ledger(List.of(
                        earlier.generations().getFirst(), laterFailure));

        QualificationCausalFinalizer.DecisionEvidence evidence =
                QualificationCausalFinalizer.decisionEvidence(
                        scenario,
                        traceFacts(List.of(), List.of(
                                new QualificationRecords.DecisionResult(expected.id(), false)),
                                ledger),
                        QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                        false);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                evidence.observations().getFirst().owner());
        assertFalse(evidence.complete());
    }

    @Test
    void successfulDeterministicAnswerIsEligibleOnlyWhenTheScenarioContractAllowsIt() {
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false, false, true,
                        false, false, false, false);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive("diagnostic", "r1", facts);

        assertEquals(QualificationRecords.FailureClass.NONE, document.failureClass());
        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY, document.causalOwner());
        assertTrue(document.comparativeScoreEligible());
    }

    @Test
    void optionalModelAnswerCannotBecomeScoreEligibleModelFailure() {
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, true, false, false, true, false,
                        false, false, false, false, false);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive("diagnostic", "r1", facts);

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                document.answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN,
                document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
    }

    @Test
    void toolDeterministicOriginRemainsDistinctAndObeysTheResponseOwnerContract() {
        QualificationCausalFinalizer.MechanicalFacts allowed =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.DETERMINISTIC_RUNTIME_ALLOWED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        false, false, true,
                        false, false, false, false);

        QualificationCausalFinalizer.Document accepted =
                QualificationCausalFinalizer.derive("tool-owned", "r1", allowed);

        assertEquals(QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC,
                accepted.answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY,
                accepted.causalOwner());
        assertEquals(QualificationRecords.FailureClass.NONE, accepted.failureClass());
        assertTrue(accepted.comparativeScoreEligible());

        QualificationCausalFinalizer.MechanicalFacts forbidden =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        true, true, false, false,
                        false, false, true,
                        false, false, false, false);
        QualificationCausalFinalizer.Document rejected =
                QualificationCausalFinalizer.derive("tool-forbidden", "r1", forbidden);
        assertEquals(QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC,
                rejected.answerOrigin());
        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY,
                rejected.causalOwner());
        assertEquals(QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT,
                rejected.failureClass());
        assertFalse(rejected.comparativeScoreEligible());
    }

    @Test
    void passingUtilityWithoutTerminalOriginRemainsIncompleteEvidence() {
        QualificationCausalFinalizer.Document document = QualificationCausalFinalizer.derive(
                "unknown-origin", "r1",
                facts(true, true, true, false, false, false,
                        false, false, false, false));

        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().anyMatch(entry ->
                entry.observedFact() == QualificationRecords.CausalFact
                        .RESPONSE_OWNER_CONTRACT_NOT_PROVEN));
    }

    @Test
    void runFinalizerRefusesForgedPublishedOwnershipAndWritesOnlyFromEvidence() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path resultPath = packet.runRoot().resolve("result.json");
        ObjectNode forged = (ObjectNode) QualificationJson.readTree(resultPath);
        forged.put("causalOwner", QualificationRecords.CausalOwner.RUNTIME_POLICY.name());
        forged.put("causalReasonCode", "FORGED_RUNTIME_OWNER");
        QualificationJson.write(resultPath, forged);
        assertThrows(IllegalStateException.class, () -> QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), catalog.admission().scenarios().getFirst().id(), catalog));

        Files.delete(resultPath);
        Files.delete(packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE));
        Files.delete(packet.runRoot().resolve(QualificationRunFinalizer.CANARY_FILE));
        Path writtenResult = QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), catalog.admission().scenarios().getFirst().id(), catalog);
        QualificationCausalFinalizer.Document document = QualificationJson.read(
                packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE),
                QualificationCausalFinalizer.Document.class);

        assertLaunchReceiptBlocked(document);
        assertTrue(Files.isRegularFile(writtenResult));
        assertThrows(IllegalStateException.class, () -> QualificationRunFinalizer.finalizeRun(
                packet.root(), packet.runRoot(), catalog.admission().scenarios().getFirst().id(), catalog));
    }

    @Test
    void validatorRejectsForgedOwnerEvenWhenTheLedgerIsSelfConsistent() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path resultPath = packet.runRoot().resolve("result.json");
        ObjectNode result = (ObjectNode) QualificationJson.readTree(resultPath);
        result.put("causalOwner", QualificationRecords.CausalOwner.RUNTIME_POLICY.name());
        result.put("causalReasonCode", "OUTCOME_SATISFIED");
        QualificationJson.write(resultPath, result);

        QualificationEvidenceValidator.Validation validation =
                QualificationEvidenceValidator.validate(packet.root(), catalog);

        assertFalse(validation.valid());
        assertTrue(validation.findings().stream().anyMatch(finding ->
                        finding.contains("publication verification failed")
                                && finding.contains("result and causal finalization disagree")),
                () -> String.join("\n", validation.findings()));
    }

    @Test
    void missingSourceArtifactBecomesUnknownRatherThanAFalseHarnessCause() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Files.delete(packet.runRoot().resolve("resources.json"));

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.deriveFromArtifacts(
                        packet.root(), packet.runRoot(),
                        catalog.admission().scenarios().getFirst().id(), catalog);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
        assertTrue(document.causalLedger().stream().allMatch(entry ->
                entry.owner() == QualificationRecords.CausalOwner.UNKNOWN));
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_PASSED
                        || entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_FAILED
                        || entry.observedFact()
                                == QualificationRecords.CausalFact.MODEL_RESPONSE_HASH_MATCHED));
    }

    @Test
    void incompletePreResultEvidenceCannotPublishPreciseDownstreamObservations() {
        QualificationRecords.TrustGates incomplete = new QualificationRecords.TrustGates(
                true, true, true, true, true, true, true, false);
        QualificationCausalFinalizer.DecisionEvidence decisionEvidence =
                new QualificationCausalFinalizer.DecisionEvidence(
                        List.of(new QualificationCausalFinalizer.DecisionObservation(
                                "required-tool", true,
                                QualificationRecords.CausalOwner.MODEL,
                                "trace.json#provider-native-decision")),
                        false);
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        incomplete,
                        true, true, false, false,
                        true, false, false,
                        false, false, false, false, false,
                        decisionEvidence);

        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive("incomplete", "r1", facts);

        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream().allMatch(entry ->
                entry.owner() == QualificationRecords.CausalOwner.UNKNOWN));
        assertTrue(document.causalLedger().stream().noneMatch(entry ->
                entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_PASSED
                        || entry.observedFact()
                                == QualificationRecords.CausalFact.SCORED_DECISION_FAILED
                        || entry.observedFact()
                                == QualificationRecords.CausalFact.MODEL_RESPONSE_HASH_MATCHED));
    }

    @Test
    void artifactMatrixCannotAttributeAnyActorBeforeActiveProcessBinding() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();

        QualificationTestPacket model = packet("model", catalog);
        setAnswerAndProviderHash(model, "5");
        QualificationPreResultEvidence.Snapshot modelEvidence =
                evidence(model, catalog, scenario);
        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                matchedOrigin(model));
        assertTrue(modelEvidence.requirements().stream()
                .anyMatch(result -> !result.passed()));
        assertLaunchReceiptBlocked(derive(model, catalog, scenario));

        QualificationTestPacket runtime = packet("runtime", catalog);
        replaceProviderWithDeterministic(runtime, "4");
        assertEquals(QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                matchedOrigin(runtime));
        assertLaunchReceiptBlocked(derive(runtime, catalog, scenario));

        QualificationTestPacket verifier = packet("verifier", catalog);
        setVerificationStatus(verifier, "FAILED");
        assertEquals("FAILED", evidence(verifier, catalog, scenario)
                .trace().verificationStatus());
        assertLaunchReceiptBlocked(derive(verifier, catalog, scenario));

        QualificationTestPacket provider = packet("provider", catalog);
        replaceGenerationWithFailure(provider, "PROVIDER_EXCEPTION");
        assertTrue(evidence(provider, catalog, scenario)
                .trace().providerAttempts().providerFailurePresent());
        assertLaunchReceiptBlocked(derive(provider, catalog, scenario));

        QualificationTestPacket harness = packet("harness", catalog);
        Files.delete(harness.runRoot().resolve("approvals.json"));
        assertTrue(evidence(harness, catalog, scenario).findings().stream()
                .anyMatch(finding -> finding.equals(
                        "MISSING_OR_EMPTY_JSON:approvals.json")));
        assertLaunchReceiptBlocked(derive(harness, catalog, scenario));

        QualificationTestPacket hardware = packet("hardware", catalog);
        replaceGenerationWithFailure(hardware, "PROVIDER_RESOURCE_EXHAUSTED");
        setGpuResourceEvidence(hardware, 100L, 99L);
        QualificationPreResultEvidence.Snapshot hardwareEvidence =
                evidence(hardware, catalog, scenario);
        assertTrue(hardwareEvidence.trace().providerAttempts()
                .hardwareResourceExhaustionPresent());
        assertTrue(QualificationCausalFinalizer.hardwareExhausted(
                true,
                hardwareEvidence.resources(),
                hardwareEvidence.machine().physicalRamBytes()));
        assertLaunchReceiptBlocked(derive(hardware, catalog, scenario));

        QualificationTestPacket unknown = packet("unknown", catalog);
        removeProviderAnswer(unknown);
        setTraceTerminal(unknown, "UNEXPECTED_TERMINAL");
        Files.writeString(unknown.runRoot().resolve("transcript.txt"),
                "Outcome: UNEXPECTED_TERMINAL\n", StandardCharsets.UTF_8);
        QualificationCausalFinalizer.Document unknownDocument = derive(unknown, catalog, scenario);
        assertEquals(QualificationRecords.AnswerOrigin.NONE, matchedOrigin(unknown));
        assertEquals("UNEXPECTED_TERMINAL",
                evidence(unknown, catalog, scenario).trace().terminalState());
        assertLaunchReceiptBlocked(unknownDocument);
    }

    @Test
    void unownedInterruptedOperationCannotBecomeProviderOrRuntimeCausality()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("unowned-interruption", catalog);
        replaceGenerationWithFailure(packet, "INTERRUPTION_CAUSE_UNPROVEN");

        QualificationProviderAttemptLedger.Ledger ledger =
                evidence(packet, catalog, scenario).trace().providerAttempts();
        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);

        QualificationCausalFinalizer.MechanicalFacts interruptionFacts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false, false,
                        ledger.causalRuntimeFailurePresent(),
                        ledger.causalProviderFailurePresent(),
                        false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none());
        QualificationCausalFinalizer.Document unmasked =
                QualificationCausalFinalizer.derive(
                        scenario.id(), "unowned-interruption", interruptionFacts);

        assertTrue(ledger.terminalFailurePresent());
        assertFalse(ledger.causalProviderFailurePresent());
        assertFalse(ledger.causalRuntimeFailurePresent());
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, unmasked.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                unmasked.failureClass());
        assertEquals(QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                unmasked.reasonCode());
        assertFalse(unmasked.comparativeScoreEligible());
        assertTrue(unmasked.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));

        // The artifact-derived document is intentionally still blocked by
        // the independent active-process launch-receipt gap (T1188). Keep the
        // assertion so fixing that dependency cannot silently reclassify the
        // neutral interruption.
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
    }

    @Test
    void typedRepetitionAbandonmentIsOwnedByRuntimePolicy() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("runtime-repetition-abandonment", catalog);
        replaceGenerationWithAbandonment(packet, "REPETITION_LOOP");

        QualificationProviderAttemptLedger.Ledger ledger =
                evidence(packet, catalog, scenario).trace().providerAttempts();
        QualificationCausalFinalizer.MechanicalFacts facts =
                new QualificationCausalFinalizer.MechanicalFacts(
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.TrustGates.allPassed(),
                        false, false, false, false,
                        false, false, false, false,
                        ledger.causalRuntimeFailurePresent(),
                        ledger.causalProviderFailurePresent(),
                        false, false, false,
                        QualificationCausalFinalizer.DecisionEvidence.none());
        QualificationCausalFinalizer.Document document =
                QualificationCausalFinalizer.derive(
                        scenario.id(), "runtime-repetition-abandonment", facts);

        assertTrue(ledger.causalRuntimeFailurePresent());
        assertEquals(QualificationRecords.CausalOwner.RUNTIME_POLICY,
                document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.RUNTIME_GENERATION_FAILED,
                document.reasonCode());
    }

    @Test
    void unownedInterruptStreamAndScopeClosureDoNotProveRuntimeFirstCause() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        for (String abandonment : List.of(
                "INTERRUPTION_CAUSE_UNPROVEN",
                "RUNTIME_STREAM_CLOSED",
                "SCOPE_CLOSED")) {
            QualificationTestPacket packet = packet(
                    "unowned-" + abandonment.toLowerCase(java.util.Locale.ROOT), catalog);
            replaceGenerationWithAbandonment(packet, abandonment);

            QualificationProviderAttemptLedger.Ledger ledger =
                    evidence(packet, catalog, scenario).trace().providerAttempts();

            assertFalse(ledger.causalRuntimeFailurePresent(), abandonment);
        }
    }

    @Test
    void activeProcessGapPrecedesOtherwiseAttributableArtifactEffects() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();

        QualificationTestPacket providerFallback = packet("provider-fallback", catalog);
        replaceGenerationWithFailure(providerFallback, "PROVIDER_EXCEPTION");
        replaceProviderWithDeterministic(providerFallback, "5");
        QualificationPreResultEvidence.Snapshot providerFallbackEvidence =
                evidence(providerFallback, catalog, scenario);
        assertTrue(providerFallbackEvidence.trace().providerAttempts()
                .providerFailurePresent());
        assertEquals(QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                matchedOrigin(providerFallback));
        assertLaunchReceiptBlocked(derive(providerFallback, catalog, scenario));

        QualificationTestPacket hardwareProvider = packet("hardware-provider", catalog);
        replaceGenerationWithFailure(hardwareProvider, "PROVIDER_RESOURCE_EXHAUSTED");
        setGpuResourceEvidence(hardwareProvider, 1_000L, 990L);
        assertTrue(evidence(hardwareProvider, catalog, scenario)
                .trace().providerAttempts().hardwareResourceExhaustionPresent());
        assertLaunchReceiptBlocked(derive(hardwareProvider, catalog, scenario));

        QualificationTestPacket capped = packet("output-cap", catalog);
        setAnswerAndProviderHash(capped, "5");
        markOutputCap(capped);
        setTiming(capped, "outputCapHit", true);
        QualificationPreResultEvidence.Snapshot cappedEvidence =
                evidence(capped, catalog, scenario);
        assertTrue(cappedEvidence.trace().providerAttempts().outputCapHit());
        assertTrue(cappedEvidence.trace().providerAttempts().causalOutputCapHit());
        assertTrue(cappedEvidence.timings().outputCapHit());
        assertLaunchReceiptBlocked(derive(capped, catalog, scenario));

        QualificationTestPacket backgroundCap = packet("output-cap-passing", catalog);
        appendBackgroundOutputCap(backgroundCap);
        setTiming(backgroundCap, "outputCapHit", true);
        QualificationCausalFinalizer.Document backgroundCapDocument =
                derive(backgroundCap, catalog, scenario);
        QualificationPreResultEvidence.Snapshot backgroundCapEvidence =
                evidence(backgroundCap, catalog, scenario);
        assertTrue(backgroundCapEvidence.trace().providerAttempts().outputCapHit());
        assertFalse(backgroundCapEvidence.trace().providerAttempts().causalOutputCapHit());
        assertFalse(backgroundCapEvidence.trace().providerAttempts()
                .nonModelContributionPresent(),
                "background maintenance must not contaminate foreground answer causality");
        assertTrue(backgroundCapEvidence.timings().outputCapHit());
        assertFalse(backgroundCapDocument.causalLedger().stream().anyMatch(entry ->
                entry.observedFact()
                        == QualificationRecords.CausalFact.OUTPUT_CAP_HIT_PROVEN));
        assertLaunchReceiptBlocked(backgroundCapDocument);

        QualificationTestPacket timeout = packet("runtime-timeout", catalog);
        replaceGenerationWithTimeout(timeout);
        setTiming(timeout, "timedOut", true);
        QualificationPreResultEvidence.Snapshot timeoutEvidence =
                evidence(timeout, catalog, scenario);
        assertTrue(timeoutEvidence.trace().providerAttempts().timedOut());
        assertTrue(timeoutEvidence.trace().providerAttempts().causalTimedOut());
        assertTrue(timeoutEvidence.timings().timedOut());
        assertLaunchReceiptBlocked(derive(timeout, catalog, scenario));
    }

    @Test
    void acceptedSecondForegroundGenerationIsANonModelContribution() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("accepted-speculative-retry", catalog);
        appendCleanForegroundGeneration(packet, "5");

        QualificationPreResultEvidence.Snapshot snapshot =
                evidence(packet, catalog, scenario);

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                matchedOrigin(packet));
        assertTrue(snapshot.trace().providerAttempts().nonModelContributionPresent());
        assertTrue(snapshot.findings().contains(
                "NON_MODEL_CONTRIBUTION_PRECEDES_MODEL_ANSWER"));
        assertFalse(snapshot.complete(),
                "an untyped runtime retry selection cannot remain comparative model-only evidence");
    }

    @Test
    void powershellBackendTextCannotForgeHardwareOwnership() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("forged-hardware-text", catalog);
        replaceGenerationWithFailure(packet, "PROVIDER_EXCEPTION");
        setTiming(packet, "backendErrorCode", "out_of_memory");
        setGpuResourceEvidence(packet, 100L, 99L);

        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);

        assertOwner(document,
                QualificationRecords.CausalOwner.UNKNOWN,
                QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE);
        assertFalse(document.comparativeScoreEligible());
    }

    @Test
    void activeProcessGapPrecedesAFailedTrustGateAndWrongModelAnswer() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("trust-before-model", catalog);
        setAnswerAndProviderHash(packet, "5");
        Files.writeString(packet.runRoot().resolve("transcript.txt"),
                "terminal state deliberately absent\n", StandardCharsets.UTF_8);

        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);

        assertFalse(document.trustGates().terminalStateDisclosure());
        assertLaunchReceiptBlocked(document);
    }

    @Test
    void activeProcessGapPrecedesVerifierAndModelAttribution() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("verifier-not-model", catalog);
        setAnswerAndProviderHash(packet, "5");
        setVerificationStatus(packet, "FAILED");

        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);

        assertEquals(QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                matchedOrigin(packet));
        assertEquals("FAILED", evidence(packet, catalog, scenario)
                .trace().verificationStatus());
        assertLaunchReceiptBlocked(document);
    }

    @Test
    void activeProcessGapPrecedesContributingRuntimeAndVerifierFacts()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("mixed", catalog);
        replaceProviderWithDeterministic(packet, "4");
        setVerificationStatus(packet, "FAILED");

        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);

        assertEquals(QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                matchedOrigin(packet));
        assertEquals("FAILED", evidence(packet, catalog, scenario)
                .trace().verificationStatus());
        assertLaunchReceiptBlocked(document);
    }

    @Test
    void modelIdentityMismatchDoesNotFabricateHarnessOwnership() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("model-identity-mismatch", catalog);
        ObjectNode trace = trace(packet);
        ((ObjectNode) trace.path("model")).put("model", "different-runtime-alias");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);

        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);
        QualificationPreResultEvidence.Snapshot identityEvidence =
                evidence(packet, catalog, scenario);

        assertFalse(document.evidenceComplete());
        assertEquals("different-runtime-alias",
                identityEvidence.trace().modelId());
        assertFalse("qwen14-runtime".equals(
                identityEvidence.trace().modelId()));
        assertFalse(document.trustGates().modelIdentityAgreement());
        assertLaunchReceiptBlocked(document);
    }

    @Test
    void explicitNonPtyCaptureIsTypedHarnessFailure() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("typed-harness-pty-failure", catalog);
        Path capturePath = packet.runRoot().resolve("transcript.raw.json");
        ObjectNode capture = (ObjectNode) QualificationJson.readTree(capturePath);
        capture.put("tty", false);
        QualificationJson.write(capturePath, capture);

        QualificationCausalFinalizer.Document document = derive(packet, catalog, scenario);

        assertFalse(document.evidenceComplete());
        assertEquals(QualificationRecords.CausalOwner.HARNESS, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(QualificationRecords.CausalReason.HARNESS_STAGE_FAILED,
                document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        List<QualificationRecords.CausalLedgerEntry> first = document.causalLedger().stream()
                .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                .toList();
        assertEquals(1, first.size());
        assertEquals(QualificationRecords.CausalOwner.HARNESS,
                first.getFirst().owner());
        assertEquals(QualificationRecords.CausalFact.HARNESS_PTY_CAPTURE_FAILED,
                first.getFirst().observedFact());
    }

    @Test
    void f18CannotAttributeOperatorOwnershipBeforeActiveProcessBinding()
            throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario f18 = catalog.full().scenarios().stream()
                .filter(scenario -> scenario.id().equals("F18-recovery-user-interruption"))
                .findFirst()
                .orElseThrow();
        QualificationTestPacket packet = QualificationTestPacket.create(
                temp.resolve("f18"), catalog, f18);

        QualificationCausalFinalizer.Document document = derive(packet, catalog, f18);
        byte[] traceBytes = Files.readAllBytes(packet.runRoot().resolve("trace.json"));
        byte[] answerBytes = Files.readAllBytes(packet.runRoot().resolve("answer.txt"));
        QualificationTraceEvidence.Facts trace =
                QualificationTraceEvidence.read(traceBytes, f18);
        var capturedPty = QualificationJson.readTree(
                packet.runRoot().resolve("transcript.raw.json"));
        QualificationRecords.AnswerOrigin matchedOrigin =
                QualificationCausalOwnership.matchedAnswerOrigin(
                        answerBytes, traceBytes, QualificationProtocol.VERSION);

        assertLaunchReceiptBlocked(document);
        assertEquals(QualificationRecords.AnswerOrigin.NONE, document.answerOrigin());
        assertEquals(QualificationRecords.AnswerOrigin.OPERATOR, matchedOrigin);
        assertTrue(QualificationPreResultEvidence.operatorInterruptionProven(
                capturedPty, f18, trace,
                new String(answerBytes, StandardCharsets.UTF_8), matchedOrigin));

        ObjectNode capture = (ObjectNode) QualificationJson.readTree(
                packet.runRoot().resolve("transcript.raw.json"));
        ArrayNode events = (ArrayNode) capture.path("events");
        for (int index = events.size() - 1; index >= 0; index--) {
            if ("user-interrupt".equals(events.get(index).path("data").asText(""))) {
                events.remove(index);
            }
        }
        QualificationJson.write(packet.runRoot().resolve("transcript.raw.json"), capture);

        QualificationCausalFinalizer.Document unproven = derive(packet, catalog, f18);
        assertFalse(unproven.causalOwner() == QualificationRecords.CausalOwner.OPERATOR);
        assertFalse(QualificationPreResultEvidence.operatorInterruptionProven(
                capture, f18, trace,
                new String(answerBytes, StandardCharsets.UTF_8), matchedOrigin));
    }

    @Test
    void physicalRamSaturationMathCannotOverflowWhileAttributionIsBlocked() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationTestPacket.protocol6Catalog();
        QualificationProtocol.Scenario scenario = catalog.admission().scenarios().getFirst();
        QualificationTestPacket packet = packet("ram-saturation", catalog);
        replaceGenerationWithFailure(packet, "PROVIDER_RESOURCE_EXHAUSTED");
        Path machinePath = packet.root().resolve("machines/reference.json");
        ObjectNode machine = (ObjectNode) QualificationJson.readTree(machinePath);
        machine.put("physicalRamBytes", Long.MAX_VALUE);
        machine.put("vramBytes", 0L);
        QualificationJson.write(machinePath, machine);
        QualificationTestPacket.refreshMachineIdentity(packet);
        setResource(packet, "systemRamBytes", Long.MAX_VALUE);
        setResource(packet, "processWorkingSetBytes", Long.MAX_VALUE);
        setResource(packet, "gpuVramBytes", 0L);
        setResource(packet, "gpuVramUsedBytes", 0L);
        writeResourceSample(packet, 0L, 0L, 0L, Long.MAX_VALUE, 0L);

        QualificationPreResultEvidence.Snapshot ramEvidence =
                evidence(packet, catalog, scenario);
        assertTrue(ramEvidence.trace().providerAttempts()
                .hardwareResourceExhaustionPresent());
        assertTrue(QualificationCausalFinalizer.hardwareExhausted(
                true, ramEvidence.resources(), ramEvidence.machine().physicalRamBytes()));
        assertLaunchReceiptBlocked(derive(packet, catalog, scenario));
        assertTrue(QualificationCausalFinalizer.atLeastPercent(
                Long.MAX_VALUE, Long.MAX_VALUE, 98));
        assertFalse(QualificationCausalFinalizer.atLeastPercent(
                97, 100, 98));
        assertFalse(QualificationCausalFinalizer.hardwareExhausted(
                true,
                new QualificationRecords.ResourceEvidence(
                        QualificationRecords.RESOURCE_SCHEMA,
                        QualificationRecords.SCHEMA_VERSION,
                        1L,
                        Long.MAX_VALUE,
                        0L,
                        0L,
                        0L,
                        "process-only negative control"),
                Long.MAX_VALUE));
    }

    @Test
    void trackedCliRefusesToWriteIntoFrozenProtocolV5Packet() throws Exception {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(5);
        QualificationTestPacket packet = QualificationTestPacket.create(temp, catalog);
        Path output = packet.runRoot().resolve(QualificationCausalFinalizer.OUTPUT_FILE);
        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();

        int exit = QualificationCausalFinalizerMain.run(
                new String[]{
                        packet.root().toString(),
                        packet.runRoot().toString(),
                        catalog.admission().scenarios().getFirst().id()},
                new PrintStream(stdout, true, StandardCharsets.UTF_8),
                new PrintStream(stderr, true, StandardCharsets.UTF_8));

        assertEquals(1, exit, stderr.toString(StandardCharsets.UTF_8));
        assertTrue(stderr.toString(StandardCharsets.UTF_8)
                .contains("run finalization writes exactly protocol v6"));
        assertFalse(Files.exists(output));
        assertTrue(stdout.toString(StandardCharsets.UTF_8).isBlank());
    }

    @Test
    void causalFinalizerRejectsFutureProtocolInsteadOfProjectingCompatibility() throws Exception {
        QualificationScenarioBank.Catalog current = QualificationTestPacket.protocol6Catalog();
        QualificationTestPacket packet = QualificationTestPacket.create(temp, current);
        QualificationScenarioBank.Catalog future = new QualificationScenarioBank.Catalog(
                7,
                current.admission(),
                current.full(),
                current.fixtures(),
                current.sha256());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> QualificationCausalFinalizer.deriveFromArtifacts(
                        packet.root(),
                        packet.runRoot(),
                        current.admission().scenarios().getFirst().id(),
                        future));

        assertTrue(failure.getMessage().contains("exactly protocol v6 is writable"));
    }

    private QualificationTestPacket packet(
            String name,
            QualificationScenarioBank.Catalog catalog) throws Exception {
        return QualificationTestPacket.create(temp.resolve(name), catalog);
    }

    private static QualificationCausalFinalizer.Document derive(
            QualificationTestPacket packet,
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario) throws Exception {
        return QualificationCausalFinalizer.deriveFromArtifacts(
                packet.root(), packet.runRoot(), scenario.id(), catalog);
    }

    private static QualificationPreResultEvidence.Snapshot evidence(
            QualificationTestPacket packet,
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.Scenario scenario) throws Exception {
        return QualificationPreResultEvidence.capture(
                packet.root(), packet.runRoot(), scenario.id(), catalog);
    }

    private static QualificationRecords.AnswerOrigin matchedOrigin(
            QualificationTestPacket packet) throws Exception {
        return QualificationCausalOwnership.matchedAnswerOrigin(
                Files.readAllBytes(packet.runRoot().resolve("answer.txt")),
                Files.readAllBytes(packet.runRoot().resolve("trace.json")),
                QualificationProtocol.VERSION);
    }

    private static void assertOwner(
            QualificationCausalFinalizer.Document document,
            QualificationRecords.CausalOwner owner,
            QualificationRecords.FailureClass failureClass) {
        assertEquals(owner, document.causalOwner());
        assertEquals(failureClass, document.failureClass());
        if (owner == QualificationRecords.CausalOwner.MIXED
                || owner == QualificationRecords.CausalOwner.UNKNOWN
                || owner == QualificationRecords.CausalOwner.PROVIDER
                || owner == QualificationRecords.CausalOwner.HARNESS
                || owner == QualificationRecords.CausalOwner.HARDWARE
                || owner == QualificationRecords.CausalOwner.RUNTIME_POLICY
                || owner == QualificationRecords.CausalOwner.VERIFIER) {
            assertFalse(document.comparativeScoreEligible());
        }
    }

    private static void assertLaunchReceiptBlocked(
            QualificationCausalFinalizer.Document document) {
        assertOwner(document,
                QualificationRecords.CausalOwner.UNKNOWN,
                QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE);
        assertFalse(document.evidenceComplete());
        assertEquals(QualificationRecords.CausalReason.MANDATORY_EVIDENCE_INCOMPLETE,
                document.reasonCode());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
    }

    private static void setAnswerAndProviderHash(
            QualificationTestPacket packet,
            String answer) throws Exception {
        Files.writeString(packet.runRoot().resolve("answer.txt"), answer + "\n",
                StandardCharsets.UTF_8);
        ObjectNode trace = trace(packet);
        String hash = answerHash(answer);
        for (var event : trace.withArray("events")) {
            String type = event.path("type").asText("");
            if ("GENERATION_RESPONSE_CAPTURED".equals(type)
                    || "GENERATION_COMPLETED".equals(type)) {
                ((ObjectNode) event.path("data")).put("assistantHash", hash);
            }
            if ("TERMINAL_ANSWER_SELECTED".equals(type)
                    || "ROUTED_ANSWER_CAPTURED".equals(type)) {
                ((ObjectNode) event.path("data")).put("answerChars", answer.length());
            }
            if ("GENERATION_RESPONSE_CAPTURED".equals(type)) {
                ((ObjectNode) event.path("data")).put("assistantChars", answer.length());
            }
            if ("TERMINAL_ANSWER_SELECTED".equals(type)
                    || "ROUTED_ANSWER_CAPTURED".equals(type)) {
                ((ObjectNode) event.path("data")).put("answerHash", hash);
            }
        }
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void replaceProviderWithDeterministic(
            QualificationTestPacket packet,
            String answer) throws Exception {
        Files.writeString(packet.runRoot().resolve("answer.txt"), answer + "\n",
                StandardCharsets.UTF_8);
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        String hash = answerHash(answer);
        for (int index = events.size() - 1; index >= 0; index--) {
            if ("DETERMINISTIC_ANSWER_SELECTED".equals(
                    events.get(index).path("type").asText(""))) {
                events.remove(index);
            }
        }
        ObjectNode deterministic = (ObjectNode) QualificationJson.tree(Map.of(
                "type", "DETERMINISTIC_ANSWER_SELECTED",
                "timestamp", "2026-07-10T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "answerHash", hash,
                        "answerChars", answer.length(),
                        "origin", "RUNTIME_DETERMINISTIC")));
        int terminalIndex = eventIndex(events, "TERMINAL_ANSWER_SELECTED");
        events.insert(terminalIndex, deterministic);
        setTerminalAnswer(trace, answer, "RUNTIME_DETERMINISTIC");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void removeProviderAnswer(QualificationTestPacket packet) throws Exception {
        ObjectNode trace = trace(packet);
        setTerminalAnswer(trace, "4", "NONE");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void replaceGenerationWithFailure(
            QualificationTestPacket packet,
            String failure) throws Exception {
        ObjectNode trace = trace(packet);
        ObjectNode response = event(trace, "GENERATION_RESPONSE_CAPTURED");
        ObjectNode responseData = ((ObjectNode) response.path("data")).deepCopy();
        responseData.remove(List.of(
                "assistantHash", "assistantChars", "nativeToolCallCount",
                "capturedNativeToolProposalCount", "nativeToolProposalsTruncated",
                "textualToolCallPresent", "completion", "providerBacked", "foreground",
                "completedNormally", "normalized", "truncated"));
        responseData.put("failure", failure);
        response.put("type", "GENERATION_ATTEMPT_FAILED");
        response.set("data", responseData);

        ObjectNode completed = event(trace, "GENERATION_COMPLETED");
        ObjectNode completedData = ((ObjectNode) completed.path("data")).deepCopy();
        completedData.remove(List.of(
                "selectedAttemptId", "selectedAttemptOrdinal", "completion",
                "assistantHash", "providerTerminalEligible", "providerToolCallEligible"));
        completedData.put("failure", failure);
        completed.put("type", "GENERATION_FAILED");
        completed.set("data", completedData);

        Files.writeString(packet.runRoot().resolve("answer.txt"), "\n",
                StandardCharsets.UTF_8);
        setTerminalAnswer(trace, "", "NONE");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void replaceGenerationWithTimeout(
            QualificationTestPacket packet) throws Exception {
        replaceGenerationWithAbandonment(packet, "WALL_CLOCK_TIMEOUT");
    }

    private static void replaceGenerationWithAbandonment(
            QualificationTestPacket packet,
            String abandonment) throws Exception {
        ObjectNode trace = trace(packet);
        ObjectNode response = event(trace, "GENERATION_RESPONSE_CAPTURED");
        ObjectNode responseData = ((ObjectNode) response.path("data")).deepCopy();
        responseData.remove(List.of(
                "assistantHash", "assistantChars", "nativeToolCallCount",
                "capturedNativeToolProposalCount", "nativeToolProposalsTruncated",
                "textualToolCallPresent", "completion", "providerBacked", "foreground",
                "completedNormally", "normalized", "truncated"));
        responseData.put("abandonment", abandonment);
        response.put("type", "GENERATION_ATTEMPT_ABANDONED");
        response.set("data", responseData);

        ObjectNode completed = event(trace, "GENERATION_COMPLETED");
        ObjectNode completedData = ((ObjectNode) completed.path("data")).deepCopy();
        completedData.remove(List.of(
                "selectedAttemptId", "selectedAttemptOrdinal", "completion",
                "assistantHash", "providerTerminalEligible", "providerToolCallEligible"));
        completedData.put("abandonment", abandonment);
        completed.put("type", "GENERATION_ABANDONED");
        completed.set("data", completedData);

        Files.writeString(packet.runRoot().resolve("answer.txt"), "\n",
                StandardCharsets.UTF_8);
        setTerminalAnswer(trace, "", "NONE");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void markOutputCap(QualificationTestPacket packet) throws Exception {
        ObjectNode trace = trace(packet);
        ObjectNode response = event(trace, "GENERATION_RESPONSE_CAPTURED");
        ((ObjectNode) response.path("data"))
                .put("completion", "PROVIDER_LENGTH")
                .put("completedNormally", false)
                .put("truncated", true);
        ObjectNode completed = event(trace, "GENERATION_COMPLETED");
        ((ObjectNode) completed.path("data"))
                .put("completion", "PROVIDER_LENGTH")
                .put("providerTerminalEligible", false);
        setTerminalAnswer(trace, "4", "NONE");
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void appendBackgroundOutputCap(QualificationTestPacket packet) throws Exception {
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int insertion = eventIndex(events, "TERMINAL_ANSWER_SELECTED");
        String generationId = "trace-1-generation-2";
        String attemptId = generationId + "-attempt-1";
        String assistantHash = answerHash("background partial");

        events.insert(insertion++, ledgerEvent("GENERATION_STARTED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 6),
                Map.entry("generationId", generationId),
                Map.entry("source", "BACKGROUND_PROVIDER"),
                Map.entry("purpose", "BACKGROUND_MAINTENANCE"),
                Map.entry("toolAuthorityMode", "NONE"))));
        events.insert(insertion++, ledgerEvent("GENERATION_ATTEMPT_STARTED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 7),
                Map.entry("generationId", generationId),
                Map.entry("source", "BACKGROUND_PROVIDER"),
                Map.entry("purpose", "BACKGROUND_MAINTENANCE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", false),
                Map.entry("toolAuthorityMode", "NONE"))));
        events.insert(insertion++, ledgerEvent("GENERATION_REQUEST_DISPATCHED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 8),
                Map.entry("generationId", generationId),
                Map.entry("source", "BACKGROUND_PROVIDER"),
                Map.entry("purpose", "BACKGROUND_MAINTENANCE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", true),
                Map.entry("toolAuthorityMode", "NONE"),
                Map.entry("providerBodyHash", "sha256:" + "8".repeat(64)),
                Map.entry("dispatchedNativeTools", List.of()),
                Map.entry("toolAuthoritySurface",
                        toolAuthoritySurface("NONE", List.of())))));
        events.insert(insertion++, ledgerEvent("GENERATION_RESPONSE_CAPTURED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 9),
                Map.entry("generationId", generationId),
                Map.entry("source", "BACKGROUND_PROVIDER"),
                Map.entry("purpose", "BACKGROUND_MAINTENANCE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", true),
                Map.entry("toolAuthorityMode", "NONE"),
                Map.entry("assistantHash", assistantHash),
                Map.entry("assistantChars", "background partial".length()),
                Map.entry("nativeToolCallCount", 0),
                Map.entry("capturedNativeToolProposalCount", 0),
                Map.entry("nativeToolProposalsTruncated", false),
                Map.entry("textualToolCallPresent", false),
                Map.entry("completion", "PROVIDER_LENGTH"),
                Map.entry("providerBacked", true),
                Map.entry("foreground", false),
                Map.entry("completedNormally", false),
                Map.entry("normalized", false),
                Map.entry("truncated", false))));
        events.insert(insertion, ledgerEvent("GENERATION_COMPLETED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 10),
                Map.entry("generationId", generationId),
                Map.entry("source", "BACKGROUND_PROVIDER"),
                Map.entry("purpose", "BACKGROUND_MAINTENANCE"),
                Map.entry("toolAuthorityMode", "NONE"),
                Map.entry("selectedAttemptId", attemptId),
                Map.entry("selectedAttemptOrdinal", 1),
                Map.entry("completion", "PROVIDER_LENGTH"),
                Map.entry("assistantHash", assistantHash),
                Map.entry("providerTerminalEligible", false),
                Map.entry("providerToolCallEligible", false))));
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void appendCleanForegroundGeneration(
            QualificationTestPacket packet,
            String answer) throws Exception {
        ObjectNode trace = trace(packet);
        ArrayNode events = trace.withArray("events");
        int insertion = eventIndex(events, "TERMINAL_ANSWER_SELECTED");
        String generationId = "trace-1-generation-2";
        String attemptId = generationId + "-attempt-1";
        String assistantHash = answerHash(answer);

        events.insert(insertion++, ledgerEvent("GENERATION_STARTED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 6),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("toolAuthorityMode", "NONE"))));
        events.insert(insertion++, ledgerEvent("GENERATION_ATTEMPT_STARTED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 7),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", false),
                Map.entry("toolAuthorityMode", "NONE"))));
        events.insert(insertion++, ledgerEvent("GENERATION_REQUEST_DISPATCHED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 8),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", true),
                Map.entry("toolAuthorityMode", "NONE"),
                Map.entry("providerBodyHash", "sha256:" + "8".repeat(64)),
                Map.entry("dispatchedNativeTools", List.of()),
                Map.entry("toolAuthoritySurface",
                        toolAuthoritySurface("NONE", List.of())))));
        events.insert(insertion++, ledgerEvent("GENERATION_RESPONSE_CAPTURED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 9),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("attemptId", attemptId),
                Map.entry("attemptOrdinal", 1),
                Map.entry("attemptKind", "PRIMARY"),
                Map.entry("retryReason", "NONE"),
                Map.entry("requestDispatched", true),
                Map.entry("toolAuthorityMode", "NONE"),
                Map.entry("assistantHash", assistantHash),
                Map.entry("assistantChars", answer.length()),
                Map.entry("nativeToolCallCount", 0),
                Map.entry("capturedNativeToolProposalCount", 0),
                Map.entry("nativeToolProposalsTruncated", false),
                Map.entry("textualToolCallPresent", false),
                Map.entry("completion", "PROVIDER_EOS"),
                Map.entry("providerBacked", true),
                Map.entry("foreground", true),
                Map.entry("completedNormally", true),
                Map.entry("normalized", false),
                Map.entry("truncated", false))));
        events.insert(insertion, ledgerEvent("GENERATION_COMPLETED", Map.ofEntries(
                Map.entry("providerLedgerVersion", 1),
                Map.entry("ledgerSequence", 10),
                Map.entry("generationId", generationId),
                Map.entry("source", "FOREGROUND_PROVIDER"),
                Map.entry("purpose", "TURN_RESPONSE"),
                Map.entry("toolAuthorityMode", "NONE"),
                Map.entry("selectedAttemptId", attemptId),
                Map.entry("selectedAttemptOrdinal", 1),
                Map.entry("completion", "PROVIDER_EOS"),
                Map.entry("assistantHash", assistantHash),
                Map.entry("providerTerminalEligible", true),
                Map.entry("providerToolCallEligible", false))));

        setTerminalAnswer(trace, answer, "MODEL_PROVIDER");
        ObjectNode terminalData = (ObjectNode) event(trace, "TERMINAL_ANSWER_SELECTED").path("data");
        terminalData.put("sourceGenerationId", generationId);
        terminalData.put("sourceAttemptId", attemptId);
        Files.writeString(packet.runRoot().resolve("answer.txt"), answer + "\n",
                StandardCharsets.UTF_8);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static ObjectNode ledgerEvent(String type, Map<String, ?> data) {
        return (ObjectNode) QualificationJson.tree(Map.of(
                "type", type,
                "timestamp", "2026-07-10T00:00:01Z",
                "phase", "",
                "toolName", "",
                "data", data));
    }

    private static void setTerminalAnswer(
            ObjectNode trace,
            String answer,
            String origin) {
        String hash = answerHash(answer);
        ObjectNode terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        ObjectNode terminalData = (ObjectNode) terminal.path("data");
        terminalData.put("answerHash", hash);
        terminalData.put("answerChars", answer.length());
        terminalData.put("origin", origin);
        if (!"MODEL_PROVIDER".equals(origin)) {
            terminalData.remove(List.of("sourceGenerationId", "sourceAttemptId"));
        }
        ObjectNode routed = event(trace, "ROUTED_ANSWER_CAPTURED");
        ((ObjectNode) routed.path("data"))
                .put("answerHash", hash)
                .put("answerChars", answer.length());
    }

    private static void setToolSurface(
            QualificationTestPacket packet,
            List<String> tools) throws Exception {
        setToolSurface(packet, tools, tools);
    }

    private static void setToolSurface(
            QualificationTestPacket packet,
            List<String> nativeSurface,
            List<String> promptSurface) throws Exception {
        ObjectNode trace = trace(packet);
        ObjectNode surface = trace.putObject("toolSurface");
        ArrayNode nativeTools = surface.putArray("nativeTools");
        ArrayNode promptTools = surface.putArray("promptTools");
        nativeSurface.forEach(nativeTools::add);
        promptSurface.forEach(promptTools::add);
        surface.put("reason", "selected for protocol-v6 causal fixture");
        ObjectNode promptAudit = (ObjectNode) trace.path("promptAudit");
        ArrayNode auditNative = promptAudit.putArray("nativeTools");
        ArrayNode auditPrompt = promptAudit.putArray("promptTools");
        nativeSurface.forEach(auditNative::add);
        promptSurface.forEach(auditPrompt::add);
        String authorityMode = !nativeSurface.isEmpty()
                ? "PROVIDER_NATIVE"
                : !promptSurface.isEmpty() ? "PROVIDER_TEXT_JSON" : "NONE";
        for (var candidate : trace.withArray("events")) {
            String type = candidate.path("type").asText("");
            if (type.startsWith("GENERATION_")) {
                ((ObjectNode) candidate.path("data"))
                        .put("toolAuthorityMode", authorityMode);
            }
            if ("GENERATION_REQUEST_DISPATCHED".equals(type)) {
                ObjectNode dispatch = (ObjectNode) candidate.path("data");
                dispatch.put("providerBodyHash", "sha256:" + "8".repeat(64));
                ArrayNode dispatched = dispatch.putArray("dispatchedNativeTools");
                if ("PROVIDER_NATIVE".equals(authorityMode)) {
                    nativeSurface.forEach(dispatched::add);
                }
                List<String> selectedSurface = "PROVIDER_NATIVE".equals(authorityMode)
                        ? nativeSurface
                        : promptSurface;
                dispatch.set(
                        "toolAuthoritySurface",
                        QualificationJson.tree(toolAuthoritySurface(
                                authorityMode,
                                selectedSurface)));
            }
        }
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void setDispatchedNativeTools(
            QualificationTestPacket packet,
            List<String> tools) throws Exception {
        ObjectNode trace = trace(packet);
        ObjectNode dispatch = event(trace, "GENERATION_REQUEST_DISPATCHED");
        ObjectNode data = (ObjectNode) dispatch.path("data");
        data.put("providerBodyHash", "sha256:" + "8".repeat(64));
        ArrayNode dispatched = data.putArray("dispatchedNativeTools");
        tools.forEach(dispatched::add);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void setLegacyUnboundDispatch(
            QualificationTestPacket packet) throws Exception {
        ObjectNode trace = trace(packet);
        trace.put("schemaVersion", 3);
        for (var candidate : trace.withArray("events")) {
            String type = candidate.path("type").asText("");
            if (type.startsWith("GENERATION_")) {
                ((ObjectNode) candidate.path("data")).remove("toolAuthorityMode");
            }
            if ("GENERATION_REQUEST_DISPATCHED".equals(type)) {
                ((ObjectNode) candidate.path("data"))
                        .remove(List.of(
                                "providerBodyHash",
                                "dispatchedNativeTools",
                                "toolAuthoritySurface"));
            }
        }
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static Map<String, Object> toolAuthoritySurface(
            String toolAuthorityMode,
            List<String> selectedTools
    ) {
        boolean bound = !"NONE".equals(toolAuthorityMode);
        List<String> hashes = bound
                ? selectedTools.stream()
                        .map(ProviderResponseCapture::toolNameHash)
                        .toList()
                : List.of();
        return Map.of(
                "schemaVersion", 1,
                "bound", bound,
                "toolChoiceMode", bound ? "AUTO" : "NONE",
                "namedToolNameHash", ProviderResponseCapture.toolNameHash(""),
                "selectedToolNameHashes", hashes,
                "authorizedToolNameHashes", hashes);
    }

    private static String answerHash(String answer) {
        return "sha256:" + QualificationScenarioBank.sha256(
                answer.getBytes(StandardCharsets.UTF_8));
    }

    private static ObjectNode event(ObjectNode trace, String type) {
        for (var candidate : trace.withArray("events")) {
            if (type.equals(candidate.path("type").asText(""))) {
                return (ObjectNode) candidate;
            }
        }
        throw new IllegalArgumentException("missing trace event " + type);
    }

    private static int eventIndex(ArrayNode events, String type) {
        for (int index = 0; index < events.size(); index++) {
            if (type.equals(events.get(index).path("type").asText(""))) return index;
        }
        throw new IllegalArgumentException("missing trace event " + type);
    }

    private static void setVerificationStatus(
            QualificationTestPacket packet,
            String status) throws Exception {
        ObjectNode trace = trace(packet);
        ((ObjectNode) trace.path("verification")).put("status", status);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void setTraceTerminal(
            QualificationTestPacket packet,
            String terminal) throws Exception {
        ObjectNode trace = trace(packet);
        ((ObjectNode) trace.path("outcome")).put("classification", terminal);
        QualificationJson.write(packet.runRoot().resolve("trace.json"), trace);
    }

    private static void setTiming(
            QualificationTestPacket packet,
            String field,
            String value) throws Exception {
        Path path = packet.runRoot().resolve("timings.json");
        ObjectNode timings = (ObjectNode) QualificationJson.readTree(path);
        timings.put(field, value);
        QualificationJson.write(path, timings);
    }

    private static void setTiming(
            QualificationTestPacket packet,
            String field,
            boolean value) throws Exception {
        Path path = packet.runRoot().resolve("timings.json");
        ObjectNode timings = (ObjectNode) QualificationJson.readTree(path);
        timings.put(field, value);
        QualificationJson.write(path, timings);
    }

    private static void setResource(
            QualificationTestPacket packet,
            String field,
            long value) throws Exception {
        Path path = packet.runRoot().resolve("resources.json");
        ObjectNode resources = (ObjectNode) QualificationJson.readTree(path);
        resources.put(field, value);
        QualificationJson.write(path, resources);
    }

    private static void setGpuResourceEvidence(
            QualificationTestPacket packet,
            long totalMiB,
            long usedMiB) throws Exception {
        long mebibyte = 1024L * 1024L;
        Path machinePath = packet.root().resolve("machines/reference.json");
        ObjectNode machine = (ObjectNode) QualificationJson.readTree(machinePath);
        machine.put("vramBytes", Math.multiplyExact(totalMiB, mebibyte));
        QualificationJson.write(machinePath, machine);
        QualificationTestPacket.refreshMachineIdentity(packet);
        setResource(packet, "gpuVramBytes", Math.multiplyExact(totalMiB, mebibyte));
        setResource(packet, "gpuVramUsedBytes", Math.multiplyExact(usedMiB, mebibyte));
        writeResourceSample(packet, 32L << 30, 0L, usedMiB, 8L << 30, 0L);
    }

    private static void writeResourceSample(
            QualificationTestPacket packet,
            long availableRamBytes,
            long pagefileUsedMiB,
            long gpuUsedMiB,
            long javaWorkingSetBytes,
            long llamaWorkingSetBytes) throws Exception {
        Files.writeString(
                packet.runRoot().resolve("resource-samples.csv"),
                QualificationResourceSamples.HEADER + "\n"
                        + "2026-07-10T00:00:01Z,"
                        + availableRamBytes + ","
                        + pagefileUsedMiB + ","
                        + gpuUsedMiB + ","
                        + javaWorkingSetBytes + ","
                        + llamaWorkingSetBytes + "\n",
                StandardCharsets.UTF_8);
    }

    private static ObjectNode trace(QualificationTestPacket packet) throws Exception {
        return (ObjectNode) QualificationJson.readTree(packet.runRoot().resolve("trace.json"));
    }

    private static QualificationCausalFinalizer.MechanicalFacts facts(
            boolean evidence,
            boolean requirements,
            boolean terminal,
            boolean timedOut,
            boolean modelAnswer,
            boolean deterministicAnswer,
            boolean verifier,
            boolean provider,
            boolean hardware,
            boolean operator) {
        return new QualificationCausalFinalizer.MechanicalFacts(
                operator
                        ? QualificationRecords.ResponseOwnerContract.OPERATOR_OWNED
                        : QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                evidence
                        ? QualificationRecords.TrustGates.allPassed()
                        : new QualificationRecords.TrustGates(
                                false, false, false, false, false, false, false, false),
                requirements,
                terminal,
                timedOut,
                modelAnswer,
                deterministicAnswer,
                verifier,
                provider,
                hardware,
                operator);
    }

    private static QualificationCausalFinalizer.MechanicalFacts modelFacts(
            boolean requirementsPass,
            QualificationCausalFinalizer.DecisionEvidence decisionEvidence) {
        return new QualificationCausalFinalizer.MechanicalFacts(
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.TrustGates.allPassed(),
                requirementsPass, true, false, false,
                true, false, false,
                false, false, false, false, false,
                decisionEvidence);
    }

    private static QualificationCausalFinalizer.MechanicalFacts requirementFailureFacts(
            QualificationRecords.ResponseOwnerContract contract,
            QualificationRecords.AnswerOrigin origin,
            QualificationCausalFinalizer.RequirementDomain failedDomain) {
        return new QualificationCausalFinalizer.MechanicalFacts(
                contract,
                QualificationRecords.TrustGates.allPassed(),
                false, true, false, false,
                origin == QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                origin == QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                origin == QualificationRecords.AnswerOrigin.TOOL_DETERMINISTIC,
                false, false, false, false,
                origin == QualificationRecords.AnswerOrigin.OPERATOR,
                false,
                QualificationCausalFinalizer.DecisionEvidence.none(),
                Set.of(failedDomain));
    }

    private static QualificationRecords.RunResult runResult(
            QualificationCausalFinalizer.Document document) {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult base = QualificationTestPacket.runResult(
                catalog, catalog.admission().scenarios().getFirst(), 1, false, false);
        return new QualificationRecords.RunResult(
                base.schemaName(), QualificationRecords.RUN_SCHEMA_VERSION,
                base.campaignId(), base.scenarioBankSha256(), base.candidateAlias(),
                base.machineId(), base.engineLaneId(), base.configId(),
                document.scenarioId(), document.repetitionId(), base.repetition(),
                base.valid(), base.invalidReason(), document.failureClass(),
                document.answerOrigin(), document.responseOwnerContract(),
                document.causalOwner(), document.reasonCode(),
                document.comparativeScoreEligible(), document.causalLedger(),
                base.terminalState(), document.trustGates(), base.timedOut(),
                base.outputCapHit(), base.backendErrorCode(), base.malformedToolCalls(),
                base.toolEligibleGenerations(), base.unnecessaryToolCalls(),
                base.repairAttempts(), base.outputTokens(), base.promptTokens(),
                base.firstOutputMillis(), base.watchdogRetries(),
                base.totalTurnWallTimeMillis(), base.promptEvalMillis(),
                base.generationMillis(), base.promptTokensPerSecond(),
                base.generationTokensPerSecond(), base.processWorkingSetBytes(),
                base.systemRamBytes(), base.gpuVramUsedBytes(), base.gpuVramBytes(),
                document.evidenceComplete(), base.decisions(), base.toolCalls(),
                document.requirements());
    }

    private static void assertUnknownIncomplete(
            QualificationCausalFinalizer.Document document,
            QualificationRecords.CausalReason reason) {
        assertEquals(QualificationRecords.CausalOwner.UNKNOWN, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertEquals(reason, document.reasonCode());
        assertFalse(document.comparativeScoreEligible());
        assertTrue(document.causalLedger().stream()
                .noneMatch(QualificationRecords.CausalLedgerEntry::firstCause));
    }

    private static void assertMixedOwners(
            QualificationCausalFinalizer.Document document,
            Set<QualificationRecords.CausalOwner> expected) {
        assertEquals(QualificationRecords.CausalOwner.MIXED, document.causalOwner());
        assertEquals(QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                document.failureClass());
        assertFalse(document.comparativeScoreEligible());
        assertEquals(expected, document.causalLedger().stream()
                .filter(QualificationRecords.CausalLedgerEntry::firstCause)
                .map(QualificationRecords.CausalLedgerEntry::owner)
                .collect(java.util.stream.Collectors.toSet()));
    }

    private static QualificationTraceEvidence.Facts traceFacts(
            List<QualificationTraceEvidence.Call> calls,
            List<QualificationRecords.DecisionResult> decisions) {
        return new QualificationTraceEvidence.Facts(
                "trace-decision", "candidate", "READ", false, "READ_ONLY",
                "ANSWERED", "PASS", "PASSED", calls, decisions,
                false, false, false, false, "", false,
                QualificationProviderAttemptLedger.Ledger.empty(), false,
                protocolV6ToolSurface());
    }

    private static QualificationTraceEvidence.Facts traceFacts(
            List<QualificationTraceEvidence.Call> calls,
            List<QualificationRecords.DecisionResult> decisions,
            QualificationProviderAttemptLedger.Ledger providerAttempts) {
        return traceFacts(calls, decisions, providerAttempts, protocolV6ToolSurface());
    }

    private static QualificationTraceEvidence.Facts traceFacts(
            List<QualificationTraceEvidence.Call> calls,
            List<QualificationRecords.DecisionResult> decisions,
            QualificationProviderAttemptLedger.Ledger providerAttempts,
            Set<String> plannedNativeSurface) {
        return new QualificationTraceEvidence.Facts(
                "trace-decision", "candidate", "READ", false, "READ_ONLY",
                "ANSWERED", "PASS", "PASSED", calls, decisions,
                false, false, false, false, "", false, providerAttempts, false,
                plannedNativeSurface);
    }

    private static java.util.Set<String> protocolV6ToolSurface() {
        return java.util.stream.Stream.concat(
                        QualificationTestPacket.protocol6Catalog().admission().scenarios().stream(),
                        QualificationTestPacket.protocol6Catalog().full().scenarios().stream())
                .flatMap(scenario -> scenario.expectedDecisions().stream())
                .flatMap(decision -> decision.allowedTools().stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private static QualificationProviderAttemptLedger.Ledger selectedProviderLedger(
            boolean toolCallPresent) {
        return selectedProviderLedger(toolCallPresent, protocolV6ToolSurface());
    }

    private static QualificationProviderAttemptLedger.Ledger selectedProviderLedger(
            boolean toolCallPresent,
            Set<String> dispatchedNativeTools) {
        String generationId = "generation-decision";
        String attemptId = "attempt-decision";
        QualificationProviderAttemptLedger.Response response =
                new QualificationProviderAttemptLedger.Response(
                        2, 3L, generationId, attemptId, 1,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE,
                        "a".repeat(64), 6,
                        toolCallPresent ? 1 : 0,
                        toolCallPresent ? 1 : 0,
                        false, false,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        true, true, true, false, false);
        List<QualificationProviderAttemptLedger.Proposal> proposals = toolCallPresent
                ? List.of(new QualificationProviderAttemptLedger.Proposal(
                        1, "b".repeat(64), "c".repeat(64)))
                : List.of();
        QualificationProviderAttemptLedger.Attempt attempt =
                new QualificationProviderAttemptLedger.Attempt(
                        attemptId, 1,
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE,
                        true, false, "sha256:" + "8".repeat(64),
                        dispatchedNativeTools, response, proposals,
                        null, null, 1, 2);
        QualificationProviderAttemptLedger.Generation generation =
                new QualificationProviderAttemptLedger.Generation(
                        generationId,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        List.of(attempt), "GENERATION_COMPLETED",
                        attemptId, 1, true, toolCallPresent,
                        null, null, 0, 3);
        return new QualificationProviderAttemptLedger.Ledger(List.of(generation));
    }

    private static QualificationProviderAttemptLedger.Ledger twoGenerationProviderLedger(
            Set<String> firstDispatchedNativeTools,
            Set<String> secondDispatchedNativeTools) {
        return new QualificationProviderAttemptLedger.Ledger(List.of(
                providerGeneration(
                        "first", firstDispatchedNativeTools, 1L, 0, 3),
                providerGeneration(
                        "second", secondDispatchedNativeTools, 4L, 4, 7)));
    }

    private static QualificationProviderAttemptLedger.Generation providerGeneration(
            String suffix,
            Set<String> dispatchedNativeTools,
            long ledgerSequence,
            int startEventIndex,
            int terminalEventIndex) {
        String generationId = "generation-" + suffix;
        String attemptId = "attempt-" + suffix;
        QualificationProviderAttemptLedger.Response response =
                new QualificationProviderAttemptLedger.Response(
                        2, ledgerSequence, generationId, attemptId, 1,
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE,
                        "a".repeat(64), 6, 1, 1,
                        false, false,
                        ProviderResponseCapture.Completion.PROVIDER_EOS,
                        true, true, true, false, false);
        QualificationProviderAttemptLedger.Attempt attempt =
                new QualificationProviderAttemptLedger.Attempt(
                        attemptId, 1,
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE,
                        true, false, "sha256:" + "8".repeat(64),
                        dispatchedNativeTools, response,
                        List.of(new QualificationProviderAttemptLedger.Proposal(
                                1, "b".repeat(64), "c".repeat(64))),
                        null, null, startEventIndex + 1, startEventIndex + 2);
        return new QualificationProviderAttemptLedger.Generation(
                generationId,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Purpose.TURN_RESPONSE,
                List.of(attempt), "GENERATION_COMPLETED",
                attemptId, 1, true, true,
                null, null, startEventIndex, terminalEventIndex);
    }

    private record Case(
            String id,
            QualificationCausalFinalizer.MechanicalFacts facts,
            QualificationRecords.CausalOwner owner,
            QualificationRecords.FailureClass failureClass,
            boolean scoreEligible) {}

    private record MechanicalMixedCase(
            String id,
            QualificationCausalFinalizer.MechanicalFacts facts,
            QualificationRecords.CausalOwner firstOwner,
            QualificationRecords.CausalOwner secondOwner) {}
}
