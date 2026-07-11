package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationCampaignContinuationPolicyTest {

    @Test
    void validModelFailureDoesNotStopLaterScenarios() {
        QualificationCampaignContinuationPolicy.Decision decision = evaluate(
                true,
                true,
                QualificationRecords.TrustGates.allPassed(),
                QualificationRecords.FailureClass.MODEL_FAILURE);

        assertFalse(decision.stop());
        assertEquals(List.of(), decision.reasons());
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
