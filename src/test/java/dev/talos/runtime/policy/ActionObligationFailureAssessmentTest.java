package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ActionObligationFailureAssessmentTest {

    @Test
    void explicitActionObligationFailureMarksAssessmentFailedWithoutLoopEvidence() {
        ActionObligationFailureAssessment assessment =
                ActionObligationFailureAssessment.assess(true, null, mutationContract(), 0);

        assertTrue(assessment.failed());
        assertTrue(assessment.explicitActionObligationFailure());
        assertFalse(assessment.pendingActionObligationFailure());
        assertFalse(assessment.failurePolicyStoppedWithoutMutation());
    }

    @Test
    void pendingActionObligationFailureIsDetectedFromFailureReason() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                "final answer",
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "Pending action obligation EXPECTED_TARGET_PROGRESS was ignored."),
                1,
                List.of());

        ActionObligationFailureAssessment assessment =
                ActionObligationFailureAssessment.assess(false, loopResult, mutationContract(), 0);

        assertTrue(assessment.failed());
        assertTrue(assessment.pendingActionObligationFailure());
        assertFalse(assessment.failurePolicyStoppedWithoutMutation());
    }

    @Test
    void pendingActionObligationFailureIsDetectedFromFinalAnswer() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                "[Action obligation failed: pending expected target progress was not satisfied.]",
                FailureDecision.stop(FailureAction.ASK_USER, "model returned prose"),
                1,
                List.of());

        ActionObligationFailureAssessment assessment =
                ActionObligationFailureAssessment.assess(false, loopResult, mutationContract(), 0);

        assertTrue(assessment.failed());
        assertTrue(assessment.pendingActionObligationFailure());
    }

    @Test
    void failurePolicyStopWithoutMutationRequiresMutationRequest() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                "[Tool loop stopped by failure policy: repeated tool failures.]",
                FailureDecision.stop(FailureAction.STOP_WITH_PARTIAL, "repeated tool failures"),
                0,
                List.of());

        ActionObligationFailureAssessment mutationAssessment =
                ActionObligationFailureAssessment.assess(false, loopResult, mutationContract(), 0);
        ActionObligationFailureAssessment readOnlyAssessment =
                ActionObligationFailureAssessment.assess(false, loopResult, readOnlyContract(), 0);

        assertTrue(mutationAssessment.failed());
        assertTrue(mutationAssessment.failurePolicyStoppedWithoutMutation());
        assertFalse(readOnlyAssessment.failed());
        assertFalse(readOnlyAssessment.failurePolicyStoppedWithoutMutation());
    }

    @Test
    void mutationEvidenceSuppressesFailurePolicyStopWithoutMutation() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                "[Tool loop stopped by failure policy: repeated tool failures.]",
                FailureDecision.stop(FailureAction.STOP_WITH_PARTIAL, "repeated tool failures"),
                0,
                List.of());

        ActionObligationFailureAssessment assessment =
                ActionObligationFailureAssessment.assess(false, loopResult, mutationContract(), 1);

        assertFalse(assessment.failed());
        assertFalse(assessment.failurePolicyStoppedWithoutMutation());
    }

    @Test
    void deniedMutationSuppressesFailurePolicyStopWithoutMutation() {
        ToolCallLoop.LoopResult loopResult = loopResult(
                "[Tool loop stopped by failure policy: repeated tool failures.]",
                FailureDecision.stop(FailureAction.STOP_WITH_PARTIAL, "repeated tool failures"),
                0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.edit_file",
                        "index.html",
                        false,
                        true,
                        true,
                        "",
                        "User denied mutation.")));

        ActionObligationFailureAssessment assessment =
                ActionObligationFailureAssessment.assess(false, loopResult, mutationContract(), 0);

        assertFalse(assessment.failed());
        assertFalse(assessment.failurePolicyStoppedWithoutMutation());
    }

    private static ToolCallLoop.LoopResult loopResult(
            String answer,
            FailureDecision failureDecision,
            int mutatingToolSuccesses,
            List<ToolCallLoop.ToolOutcome> outcomes
    ) {
        return new ToolCallLoop.LoopResult(
                answer,
                1,
                outcomes.size(),
                List.of(),
                List.of(),
                0,
                0,
                false,
                mutatingToolSuccesses,
                List.of(),
                0,
                0,
                0,
                0,
                failureDecision,
                outcomes);
    }

    private static TaskContract mutationContract() {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                "edit index.html");
    }

    private static TaskContract readOnlyContract() {
        return new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("README.md"),
                Set.of(),
                "read README.md");
    }
}
