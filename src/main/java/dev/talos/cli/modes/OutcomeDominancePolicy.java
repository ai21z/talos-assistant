package dev.talos.cli.modes;

import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.task.TaskContract;

final class OutcomeDominancePolicy {
    private OutcomeDominancePolicy() {
    }

    record Facts(
            TaskContract contract,
            boolean invalidMutationArguments,
            boolean malformedProtocolDebris,
            boolean readOnlyDeniedMutation,
            boolean failedActionObligation,
            boolean commandFailed,
            boolean commandDenied,
            boolean commandSucceeded,
            boolean deniedMutation,
            boolean deniedProtectedRead,
            boolean partialMutation,
            boolean falseMutationClaim,
            boolean inspectUnderCompleted,
            boolean ungroundedAdvisory,
            boolean unsupportedCapabilityLimited,
            boolean missingEvidence,
            boolean protectedReadApprovalMissing,
            boolean approvedProtectedReadPostcondition,
            ExecutionOutcome.VerificationStatus verificationStatus
    ) {
        Facts {
            verificationStatus = verificationStatus == null
                    ? ExecutionOutcome.VerificationStatus.NOT_RUN
                    : verificationStatus;
        }

        Facts(
                TaskContract contract,
                boolean invalidMutationArguments,
                boolean malformedProtocolDebris,
                boolean readOnlyDeniedMutation,
                boolean failedActionObligation,
                boolean deniedMutation,
                boolean deniedProtectedRead,
                boolean partialMutation,
                boolean falseMutationClaim,
                boolean inspectUnderCompleted,
                boolean ungroundedAdvisory,
                boolean missingEvidence,
                boolean protectedReadApprovalMissing,
                ExecutionOutcome.VerificationStatus verificationStatus
        ) {
            this(
                    contract,
                    invalidMutationArguments,
                    malformedProtocolDebris,
                    readOnlyDeniedMutation,
                    failedActionObligation,
                    false,
                    false,
                    false,
                    deniedMutation,
                    deniedProtectedRead,
                    partialMutation,
                    falseMutationClaim,
                    inspectUnderCompleted,
                    ungroundedAdvisory,
                    false,
                    missingEvidence,
                    protectedReadApprovalMissing,
                    false,
                    verificationStatus);
        }

        Facts(
                TaskContract contract,
                boolean invalidMutationArguments,
                boolean malformedProtocolDebris,
                boolean readOnlyDeniedMutation,
                boolean failedActionObligation,
                boolean commandFailed,
                boolean commandDenied,
                boolean commandSucceeded,
                boolean deniedMutation,
                boolean deniedProtectedRead,
                boolean partialMutation,
                boolean falseMutationClaim,
                boolean inspectUnderCompleted,
                boolean ungroundedAdvisory,
                boolean missingEvidence,
                boolean protectedReadApprovalMissing,
                ExecutionOutcome.VerificationStatus verificationStatus
        ) {
            this(
                    contract,
                    invalidMutationArguments,
                    malformedProtocolDebris,
                    readOnlyDeniedMutation,
                    failedActionObligation,
                    commandFailed,
                    commandDenied,
                    commandSucceeded,
                    deniedMutation,
                    deniedProtectedRead,
                    partialMutation,
                    falseMutationClaim,
                    inspectUnderCompleted,
                    ungroundedAdvisory,
                    false,
                    missingEvidence,
                    protectedReadApprovalMissing,
                    false,
                    verificationStatus);
        }
    }

    record Decision(
            ExecutionOutcome.CompletionStatus completionStatus,
            TaskCompletionStatus taskCompletionStatus,
            boolean blockedByPolicy
    ) {
    }

    static Decision decide(Facts facts) {
        if (facts == null) {
            facts = new Facts(
                    null,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    false,
                    ExecutionOutcome.VerificationStatus.NOT_RUN);
        }

        if (facts.malformedProtocolDebris() || facts.invalidMutationArguments()) {
            return failed();
        }
        if (facts.commandDenied()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.BLOCKED,
                    TaskCompletionStatus.BLOCKED_BY_APPROVAL,
                    false);
        }
        if (facts.commandFailed()) {
            return failed();
        }
        if (facts.readOnlyDeniedMutation() || facts.failedActionObligation()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.BLOCKED,
                    TaskCompletionStatus.BLOCKED_BY_POLICY,
                    true);
        }
        if (facts.deniedMutation() || facts.deniedProtectedRead()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.BLOCKED,
                    TaskCompletionStatus.BLOCKED_BY_APPROVAL,
                    false);
        }
        if (facts.protectedReadApprovalMissing()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.BLOCKED,
                    TaskCompletionStatus.BLOCKED_BY_POLICY,
                    true);
        }
        if (facts.partialMutation()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.PARTIAL,
                    TaskCompletionStatus.PARTIAL,
                    false);
        }
        if (facts.verificationStatus() == ExecutionOutcome.VerificationStatus.FAILED) {
            return failed();
        }
        if (facts.commandSucceeded() && facts.contract() != null && facts.contract().verificationRequired()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.COMPLETE,
                    TaskCompletionStatus.COMPLETED_VERIFIED,
                    false);
        }
        // For non-mutating verify/status turns, evidence sufficiency is decided by the
        // evidence gate. NOT_RUN only means no post-apply mutation verifier was relevant.
        if (facts.unsupportedCapabilityLimited()
                || facts.missingEvidence()
                || facts.falseMutationClaim()
                || facts.inspectUnderCompleted()
                || facts.ungroundedAdvisory()
                || facts.approvedProtectedReadPostcondition()) {
            return advisory();
        }
        if (facts.verificationStatus() == ExecutionOutcome.VerificationStatus.PASSED) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.COMPLETE,
                    TaskCompletionStatus.COMPLETED_VERIFIED,
                    false);
        }
        if (facts.contract() != null && !facts.contract().mutationRequested()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.COMPLETE,
                    TaskCompletionStatus.READ_ONLY_ANSWERED,
                    false);
        }
        return new Decision(
                ExecutionOutcome.CompletionStatus.COMPLETE,
                TaskCompletionStatus.COMPLETED_UNVERIFIED,
                false);
    }

    private static Decision failed() {
        return new Decision(
                ExecutionOutcome.CompletionStatus.FAILED,
                TaskCompletionStatus.FAILED,
                false);
    }

    private static Decision advisory() {
        return new Decision(
                ExecutionOutcome.CompletionStatus.ADVISORY_ONLY,
                TaskCompletionStatus.ADVISORY_ONLY,
                false);
    }

}
