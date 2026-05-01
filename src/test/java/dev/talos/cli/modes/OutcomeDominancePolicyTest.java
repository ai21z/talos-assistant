package dev.talos.cli.modes;

import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OutcomeDominancePolicyTest {

    @Test
    void malformedProtocolDebrisFails() {
        var decision = decide(readOnlyContract(),
                false, true, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.FAILED, decision.taskCompletionStatus());
    }

    @Test
    void invalidMutationArgumentsFail() {
        var decision = decide(mutationContract(),
                true, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.FAILED, decision.taskCompletionStatus());
    }

    @Test
    void readOnlyDeniedMutationBlocksByPolicy() {
        var decision = decide(readOnlyContract(),
                false, false, true, true, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, decision.taskCompletionStatus());
        assertTrue(decision.blockedByPolicy());
    }

    @Test
    void failedActionObligationBlocksByPolicy() {
        var decision = decideWithFailedActionObligation(mutationContract());

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, decision.taskCompletionStatus());
        assertTrue(decision.blockedByPolicy());
    }

    @Test
    void deniedMutationBlocksByApproval() {
        var decision = decide(mutationContract(),
                false, false, false, true, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, decision.taskCompletionStatus());
        assertFalse(decision.blockedByPolicy());
    }

    @Test
    void deniedProtectedReadDominatesMissingEvidence() {
        var decision = decide(readOnlyContract(),
                false, false, false, false, true,
                false, false, false, false, true,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_APPROVAL, decision.taskCompletionStatus());
        assertFalse(decision.blockedByPolicy());
    }

    @Test
    void partialMutationDominatesVerificationFailure() {
        var decision = decide(mutationContract(),
                false, false, false, false, false,
                true, false, false, false, false,
                ExecutionOutcome.VerificationStatus.FAILED);

        assertEquals(ExecutionOutcome.CompletionStatus.PARTIAL, decision.completionStatus());
        assertEquals(TaskCompletionStatus.PARTIAL, decision.taskCompletionStatus());
    }

    @Test
    void verificationFailureFailsOtherwiseCompleteMutation() {
        var decision = decide(mutationContract(),
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.FAILED);

        assertEquals(ExecutionOutcome.CompletionStatus.FAILED, decision.completionStatus());
        assertEquals(TaskCompletionStatus.FAILED, decision.taskCompletionStatus());
    }

    @Test
    void missingEvidenceIsAdvisory() {
        var decision = decide(readOnlyContract(),
                false, false, false, false, false,
                false, false, false, false, true,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, decision.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, decision.taskCompletionStatus());
    }

    @Test
    void falseMutationClaimIsAdvisory() {
        var decision = decide(mutationContract(),
                false, false, false, false, false,
                false, true, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, decision.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, decision.taskCompletionStatus());
    }

    @Test
    void inspectUnderCompletionIsAdvisory() {
        var decision = decide(readOnlyContract(),
                false, false, false, false, false,
                false, false, true, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, decision.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, decision.taskCompletionStatus());
    }

    @Test
    void ungroundedAnswerIsAdvisory() {
        var decision = decide(readOnlyContract(),
                false, false, false, false, false,
                false, false, false, true, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, decision.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, decision.taskCompletionStatus());
    }

    @Test
    void verifiedMutationCompletesVerified() {
        var decision = decide(mutationContract(),
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.PASSED);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, decision.completionStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, decision.taskCompletionStatus());
    }

    @Test
    void readOnlyFulfilledMapsToReadOnlyAnsweredUnlessVerifierPassed() {
        var readOnly = decide(readOnlyContract(),
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);
        var verified = decide(readOnlyContract(),
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.PASSED);

        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, readOnly.taskCompletionStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_VERIFIED, verified.taskCompletionStatus());
    }

    @Test
    void verificationRequiredReadOnlyCannotCompleteWhenVerifierDidNotRun() {
        var decision = decide(verifyOnlyContract(),
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, decision.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, decision.taskCompletionStatus());
    }

    @Test
    void unverifiedMutationCompletesUnverified() {
        var decision = decide(mutationContract(),
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, decision.completionStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, decision.taskCompletionStatus());
    }

    @Test
    void nullContractKeepsUnverifiedFallback() {
        var decision = decide(null,
                false, false, false, false, false,
                false, false, false, false, false,
                ExecutionOutcome.VerificationStatus.NOT_RUN);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, decision.completionStatus());
        assertEquals(TaskCompletionStatus.COMPLETED_UNVERIFIED, decision.taskCompletionStatus());
    }

    private static OutcomeDominancePolicy.Decision decide(
            TaskContract contract,
            boolean invalidMutationArguments,
            boolean malformedProtocolDebris,
            boolean readOnlyDeniedMutation,
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean ungroundedAdvisory,
            boolean missingEvidence,
            ExecutionOutcome.VerificationStatus verificationStatus
    ) {
        return OutcomeDominancePolicy.decide(new OutcomeDominancePolicy.Facts(
                contract,
                invalidMutationArguments,
                malformedProtocolDebris,
                readOnlyDeniedMutation,
                false,
                deniedMutation,
                deniedProtectedRead,
                partialMutation,
                falseMutationClaim,
                inspectUnderCompleted,
                ungroundedAdvisory,
                missingEvidence,
                false,
                verificationStatus));
    }

    private static OutcomeDominancePolicy.Decision decideWithFailedActionObligation(TaskContract contract) {
        return OutcomeDominancePolicy.decide(new OutcomeDominancePolicy.Facts(
                contract,
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
                true,
                false,
                ExecutionOutcome.VerificationStatus.NOT_RUN));
    }

    private static TaskContract readOnlyContract() {
        return new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                "Read the workspace.");
    }

    private static TaskContract verifyOnlyContract() {
        return new TaskContract(
                TaskType.VERIFY_ONLY,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                "Is this BMI page working now?");
    }

    private static TaskContract mutationContract() {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                "Edit index.html.");
    }
}
