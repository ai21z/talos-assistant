package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;

import java.util.Optional;

final class ToolMutationEvidenceBudgetGate {
    private ToolMutationEvidenceBudgetGate() {
    }

    static Optional<Boolean> tryContinueOrStop(LoopState state, int readOnlyToolBudget) {
        if (!mutationReadOnlyBudgetExceeded(state, readOnlyToolBudget)) {
            return Optional.empty();
        }
        return ToolRepromptContextBudgetHandler.handleReadOnlyMutationEvidenceBudget(
                state,
                readOnlyInspectionAttemptCount(state));
    }

    private static boolean mutationReadOnlyBudgetExceeded(LoopState state, int readOnlyToolBudget) {
        if (state == null || state.toolNames.isEmpty()) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.mutationRequested()) return false;
        if (WorkspaceOperationIntent.detect(contract).isPresent()) return false;
        if (state.mutationSinceStart || state.mutatingToolSuccesses > 0) return false;
        if (state.failedCalls > 0) return false;
        if (!readOnlyProgressOnly(state)) return false;
        if (!CompactMutationContinuationPlanner.hasMutationTargets(state, contract)) return false;
        return readOnlyInspectionAttemptCount(state) >= readOnlyToolBudget;
    }

    private static int readOnlyInspectionAttemptCount(LoopState state) {
        if (state == null) return 0;
        return Math.max(0, state.toolNames.size()) + Math.max(0, state.cushionFiresRedundantRead);
    }

    private static boolean readOnlyProgressOnly(LoopState state) {
        if (state == null || state.toolOutcomes.isEmpty()) return false;
        for (ToolCallLoop.ToolOutcome outcome : state.toolOutcomes) {
            if (outcome == null || !outcome.success()) return false;
            if (!ToolCallSupport.isReadOnlyTool(outcome.toolName()) || outcome.mutating()) {
                return false;
            }
        }
        return true;
    }
}
