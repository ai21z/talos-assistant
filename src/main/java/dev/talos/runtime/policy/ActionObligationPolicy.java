package dev.talos.runtime.policy;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;

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
            case FILE_CREATE, FILE_EDIT -> contract.mutationAllowed() && phase == ExecutionPhase.APPLY
                    ? ActionObligation.MUTATING_TOOL_REQUIRED
                    : ActionObligation.INSPECT_REQUIRED;
            case READ_ONLY_QA -> ActionObligation.NONE;
            case UNKNOWN -> ActionObligation.UNKNOWN;
        };
    }
}
