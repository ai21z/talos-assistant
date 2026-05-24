package dev.talos.runtime.policy;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;

/** Derives action-obligation failure facts from explicit runtime state and tool-loop stop evidence. */
public record ActionObligationFailureAssessment(
        boolean failed,
        boolean explicitActionObligationFailure,
        boolean pendingActionObligationFailure,
        boolean failurePolicyStoppedWithoutMutation
) {
    public static ActionObligationFailureAssessment assess(
            boolean explicitActionObligationFailure,
            ToolCallLoop.LoopResult loopResult,
            TaskContract contract,
            int extraMutationSuccesses
    ) {
        boolean pendingActionObligationFailure = pendingActionObligationFailure(loopResult);
        boolean failurePolicyStoppedWithoutMutation = failurePolicyStoppedWithoutMutation(
                loopResult,
                contract,
                extraMutationSuccesses);
        return new ActionObligationFailureAssessment(
                explicitActionObligationFailure
                        || pendingActionObligationFailure
                        || failurePolicyStoppedWithoutMutation,
                explicitActionObligationFailure,
                pendingActionObligationFailure,
                failurePolicyStoppedWithoutMutation);
    }

    private static boolean failurePolicyStoppedWithoutMutation(
            ToolCallLoop.LoopResult loopResult,
            TaskContract contract,
            int extraMutationSuccesses
    ) {
        if (loopResult == null || loopResult.failureDecision() == null) return false;
        if (!loopResult.failureDecision().shouldStop()) return false;
        if (contract == null || !contract.mutationRequested()) return false;
        if (hasDeniedMutation(loopResult)) return false;
        return loopResult.mutatingToolSuccesses() + Math.max(0, extraMutationSuccesses) <= 0;
    }

    private static boolean pendingActionObligationFailure(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.failureDecision() == null) return false;
        if (!loopResult.failureDecision().shouldStop()) return false;
        String reason = loopResult.failureDecision().reason();
        if (reason != null && reason.startsWith("Pending action obligation ")) return true;
        String answer = loopResult.finalAnswer();
        return answer != null && answer.startsWith("[Action obligation failed:");
    }

    private static boolean hasDeniedMutation(ToolCallLoop.LoopResult loopResult) {
        if (loopResult == null || loopResult.toolOutcomes() == null) return false;
        return loopResult.toolOutcomes().stream()
                .anyMatch(outcome -> outcome.mutating() && outcome.denied());
    }
}
