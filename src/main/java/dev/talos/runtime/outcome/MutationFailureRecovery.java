package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Classifies tool failures that are artifacts of an already satisfied mutation.
 */
public final class MutationFailureRecovery {
    private MutationFailureRecovery() {}

    public static boolean isRecoveredDuplicateWorkspaceOperationFailure(
            ToolCallLoop.ToolOutcome failure,
            List<ToolCallLoop.ToolOutcome> orderedMutatingOutcomes
    ) {
        if (failure == null || orderedMutatingOutcomes == null || orderedMutatingOutcomes.isEmpty()) return false;
        if (!failure.mutating() || failure.success() || failure.denied()) return false;
        WorkspaceOperationPlan failedPlan = failure.workspaceOperationPlan();
        if (failedPlan == null || failedPlan.pathEffects().isEmpty()) return false;
        if (!looksLikeDuplicateWorkspaceOperationFailure(failure)) return false;

        for (ToolCallLoop.ToolOutcome outcome : orderedMutatingOutcomes) {
            if (outcome == failure) return false;
            if (outcome == null || !outcome.mutating() || !outcome.success()) continue;
            if (sameWorkspaceOperationPlan(failedPlan, outcome.workspaceOperationPlan())) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeDuplicateWorkspaceOperationFailure(ToolCallLoop.ToolOutcome failure) {
        String message = failure.errorMessage() == null
                ? ""
                : failure.errorMessage().toLowerCase(Locale.ROOT);
        return message.contains("destination already exists")
                || message.contains("source not found")
                || message.contains("already exists");
    }

    private static boolean sameWorkspaceOperationPlan(
            WorkspaceOperationPlan left,
            WorkspaceOperationPlan right
    ) {
        if (left == null || right == null) return false;
        return left.operationKind() == right.operationKind()
                && Objects.equals(left.pathEffects(), right.pathEffects())
                && left.overwritePolicy() == right.overwritePolicy()
                && left.recursive() == right.recursive();
    }
}
