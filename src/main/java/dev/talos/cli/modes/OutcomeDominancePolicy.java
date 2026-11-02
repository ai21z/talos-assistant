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
            ExecutionOutcome.VerificationStatus verificationStatus,
            boolean requestBoundProof
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
                    verificationStatus,
                    false);
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
                    verificationStatus,
                    false);
        }
    }

    record Decision(
            ExecutionOutcome.CompletionStatus completionStatus,
            TaskCompletionStatus taskCompletionStatus,
            boolean blockedByPolicy
    ) {
    }

    static Decision withReadEvidencePolicyBlock(Decision decision, boolean blocked) {
        if (!blocked || decision == null
                || (decision.completionStatus() != ExecutionOutcome.CompletionStatus.COMPLETE
                && decision.completionStatus() != ExecutionOutcome.CompletionStatus.ADVISORY_ONLY)) return decision;
        return new Decision(ExecutionOutcome.CompletionStatus.BLOCKED, TaskCompletionStatus.BLOCKED_BY_POLICY, true);
    }

    /** How the runtime ended the tool loop, as a status fact rather than answer wording. */
    enum RuntimeStop { NONE, AUTHORITY_REJECTED, OTHER }

    /**
     * A loop the runtime stopped is not a completed answer (MICA runtime-owned facts). Listed
     * read-only limitations keep their own classification, and a turn that changed files is
     * classified by its mutation and verification facts instead.
     */
    static RuntimeStop runtimeStop(dev.talos.runtime.ToolCallLoop.LoopResult loopResult,
                                   boolean readOnlyLimited, int extraMutationSuccesses) {
        if (loopResult == null || readOnlyLimited || !loopResult.failureDecision().shouldStop()) return RuntimeStop.NONE;
        if (loopResult.mutatingToolSuccesses() > 0 || extraMutationSuccesses > 0) return RuntimeStop.NONE;
        return dev.talos.runtime.toolcall.ToolCallRepromptStage.AUTHORITY_REJECTED_REASON
                .equals(loopResult.failureDecision().reason())
                ? RuntimeStop.AUTHORITY_REJECTED
                : RuntimeStop.OTHER;
    }

    /** Only a COMPLETE is downgraded. Every other status already states that the turn did not complete. */
    static Decision withRuntimeStop(Decision decision, RuntimeStop stop) {
        if (decision == null || stop == null || stop == RuntimeStop.NONE
                || decision.completionStatus() != ExecutionOutcome.CompletionStatus.COMPLETE) return decision;
        return stop == RuntimeStop.AUTHORITY_REJECTED
                ? new Decision(ExecutionOutcome.CompletionStatus.BLOCKED, TaskCompletionStatus.BLOCKED_BY_POLICY, true)
                : advisory();
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
                    ExecutionOutcome.VerificationStatus.NOT_RUN,
                    false);
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
        // Only proof bound to the request completes as verified. A checks pass completes unverified with its own
        // wording (MICA invariant I3, decision D5).
        if (facts.verificationStatus() == ExecutionOutcome.VerificationStatus.PASSED && facts.requestBoundProof()) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.COMPLETE,
                    TaskCompletionStatus.COMPLETED_VERIFIED,
                    false);
        }
        if (facts.verificationStatus() == ExecutionOutcome.VerificationStatus.PASSED) {
            return new Decision(
                    ExecutionOutcome.CompletionStatus.COMPLETE,
                    TaskCompletionStatus.COMPLETED_UNVERIFIED,
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
