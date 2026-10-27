package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCampaignContinuationPolicyTest {

    @Test
    void modelFailureContinuesOnlyWithMechanicallyMatchedProviderAnswer() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult result = QualificationTestPacket.runResult(
                        catalog, catalog.admission().scenarios().getFirst(), 1, false, false)
                .withFailure(
                        QualificationRecords.FailureClass.MODEL_FAILURE,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.CausalOwner.MODEL,
                        List.of(new QualificationRecords.CausalLedgerEntry(
                                1, QualificationRecords.CausalStage.UTILITY,
                                QualificationRecords.CausalOwner.MODEL,
                                QualificationRecords.CausalFact.CANONICAL_REQUIREMENTS_FAILED,
                                QualificationRecords.CausalReason.MODEL_RESPONSE_FAILED_REQUIREMENT,
                                "trace.json#PROVIDER_GENERATION_RECEIVED", true)));

        QualificationCampaignContinuationPolicy.Decision decision =
                QualificationCampaignContinuationPolicy.evaluate(result, true);

        assertFalse(decision.stop());
        assertEquals(List.of(), decision.reasons());
    }

    @Test
    void unprovenModelFailureStopsCampaign() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationRecords.RunResult result = QualificationTestPacket.runResult(
                        catalog, catalog.admission().scenarios().getFirst(), 1, false, false)
                .withFailure(
                        QualificationRecords.FailureClass.MODEL_FAILURE,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.CausalOwner.MODEL,
                        List.of(new QualificationRecords.CausalLedgerEntry(
                                1, QualificationRecords.CausalStage.UTILITY,
                                QualificationRecords.CausalOwner.MODEL,
                                QualificationRecords.CausalFact.CANONICAL_REQUIREMENTS_FAILED,
                                QualificationRecords.CausalReason.MODEL_RESPONSE_FAILED_REQUIREMENT,
                                "trace.json#PROVIDER_GENERATION_RECEIVED", true)));

        QualificationCampaignContinuationPolicy.Decision decision =
                QualificationCampaignContinuationPolicy.evaluate(result, false);

        assertTrue(decision.stop());
        assertTrue(decision.reasons().contains(
                "causal-evidence:MODEL_FAILURE_LACKS_MATCHED_MODEL_RESPONSE"));
    }

    @Test
    void validOperatorInterruptionDoesNotStopLaterScenarios() {
        QualificationCampaignContinuationPolicy.Decision decision = evaluate(
                true,
                true,
                QualificationRecords.TrustGates.allPassed(),
                QualificationRecords.FailureClass.OPERATOR_INTERRUPTION);

        assertFalse(decision.stop());
    }

    @Test
    void invalidOrIncompleteEvidenceStopsCampaign() {
        assertTrue(evaluate(false, true, QualificationRecords.TrustGates.allPassed(),
                QualificationRecords.FailureClass.MODEL_FAILURE).stop());
        assertTrue(evaluate(true, false, QualificationRecords.TrustGates.allPassed(),
                QualificationRecords.FailureClass.MODEL_FAILURE).stop());
    }

    @Test
    void everyFailedTrustGateIsNamedAndStopsCampaign() {
        QualificationRecords.TrustGates trust = new QualificationRecords.TrustGates(
                false, true, false, true, true, true, true, true);

        QualificationCampaignContinuationPolicy.Decision decision = evaluate(
                true, true, trust, QualificationRecords.FailureClass.MODEL_FAILURE);

        assertTrue(decision.stop());
        assertEquals(List.of(
                "trust-gate:approvalIntegrity",
                "trust-gate:completionTruth"), decision.reasons());
    }

    @Test
    void runtimeHardwareAndIncompleteEvidenceClassesStopCampaign() {
        for (QualificationRecords.FailureClass failureClass : List.of(
                QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT,
                QualificationRecords.FailureClass.HARDWARE_FAILURE,
                QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE)) {
            QualificationCampaignContinuationPolicy.Decision decision = evaluate(
                    true, true, QualificationRecords.TrustGates.allPassed(), failureClass);

            assertTrue(decision.stop(), failureClass.name());
            assertTrue(decision.reasons().contains("failure-class:" + failureClass.name()),
                    decision.reasons().toString());
        }
    }

    private static QualificationCampaignContinuationPolicy.Decision evaluate(
            boolean valid,
            boolean evidenceComplete,
            QualificationRecords.TrustGates trustGates,
            QualificationRecords.FailureClass failureClass) {
        return QualificationCampaignContinuationPolicy.evaluate(
                valid, evidenceComplete, trustGates, failureClass);
    }
}
