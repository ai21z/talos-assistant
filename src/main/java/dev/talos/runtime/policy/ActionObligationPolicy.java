package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.expectation.TaskExpectationResolver;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.workspace.WorkspaceOperationIntent;

/** Deterministically maps a current turn to the action shape Talos must enforce. */
public final class ActionObligationPolicy {
    private ActionObligationPolicy() {}

    public static ActionObligation derive(TaskContract contract, ExecutionPhase phase) {
        if (contract == null || contract.type() == null) return ActionObligation.UNKNOWN;
        return switch (contract.type()) {
            case SMALL_TALK -> ActionObligation.DIRECT_ANSWER_ONLY;
            case DIRECTORY_LISTING -> ActionObligation.LIST_DIR_ONLY;
            case WORKSPACE_EXPLAIN, DIAGNOSE_ONLY -> ActionObligation.INSPECT_REQUIRED;
            case VERIFY_ONLY -> ActionObligation.VERIFY_FROM_EVIDENCE;
            case CHECKPOINT_RESTORE -> ActionObligation.DIRECT_ANSWER_ONLY;
            case FILE_CREATE, FILE_EDIT -> fileMutationObligation(contract, phase);
            case READ_ONLY_QA -> ActionObligation.NONE;
            case UNKNOWN -> ActionObligation.UNKNOWN;
        };
    }

    private static ActionObligation fileMutationObligation(TaskContract contract, ExecutionPhase phase) {
        if (!contract.mutationAllowed() || phase != ExecutionPhase.APPLY) {
            return ActionObligation.INSPECT_REQUIRED;
        }
        if ("explicit-review-and-fix-request".equals(contract.classificationReason())) {
            return ActionObligation.CONDITIONAL_REVIEW_FIX;
        }
        if (WorkspaceOperationIntent.detect(contract).isPresent()
                && TaskExpectationResolver.resolve(contract).isEmpty()) {
            return ActionObligation.WORKSPACE_OPERATION_REQUIRED;
        }
        return ActionObligation.MUTATING_TOOL_REQUIRED;
    }
}
