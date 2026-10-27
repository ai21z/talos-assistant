package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskResolution;

/** Deterministically maps a current turn to the action shape Talos must enforce. */
public final class ActionObligationPolicy {
    private ActionObligationPolicy() {}

    public static ActionObligation derive(TaskContract contract, ExecutionPhase phase) {
        return derive(contract, phase, false);
    }

    public static ActionObligation derive(
            TaskResolution resolution,
            ExecutionPhase phase
    ) {
        if (resolution == null) {
            return derive((TaskContract) null, phase);
        }
        if (resolution.blocksUnscopedMutation()) {
            return ActionObligation.NONE;
        }
        if (!resolution.workspaceOperationPlan()
                        .authorizedOperations().isEmpty()
                && resolution.contract().mutationAllowed()
                && phase == ExecutionPhase.APPLY) {
            return ActionObligation.WORKSPACE_OPERATION_REQUIRED;
        }
        return derive(
                resolution.contract(),
                phase,
                resolution.blocksWorkspaceOperations());
    }

    private static ActionObligation derive(
            TaskContract contract,
            ExecutionPhase phase,
            boolean workspaceOperationBlocked
    ) {
        if (contract == null || contract.type() == null) return ActionObligation.UNKNOWN;
        return switch (contract.type()) {
            case SMALL_TALK -> ActionObligation.DIRECT_ANSWER_ONLY;
            case DIRECTORY_LISTING -> ActionObligation.LIST_DIR_ONLY;
            case WORKSPACE_EXPLAIN, DIAGNOSE_ONLY -> ActionObligation.INSPECT_REQUIRED;
            case VERIFY_ONLY -> ActionObligation.VERIFY_FROM_EVIDENCE;
            case CHECKPOINT_RESTORE -> ActionObligation.DIRECT_ANSWER_ONLY;
            case FILE_CREATE, FILE_EDIT ->
                    fileMutationObligation(
                            contract,
                            phase,
                            workspaceOperationBlocked);
            case READ_ONLY_QA -> ActionObligation.NONE;
            case UNKNOWN -> ActionObligation.UNKNOWN;
        };
    }

    private static ActionObligation fileMutationObligation(
            TaskContract contract,
            ExecutionPhase phase,
            boolean workspaceOperationBlocked
    ) {
        if (!contract.mutationAllowed() || phase != ExecutionPhase.APPLY) {
            return ActionObligation.INSPECT_REQUIRED;
        }
        if ("explicit-review-and-fix-request".equals(contract.classificationReason())) {
            return ActionObligation.CONDITIONAL_REVIEW_FIX;
        }
        return ActionObligation.MUTATING_TOOL_REQUIRED;
    }
}
