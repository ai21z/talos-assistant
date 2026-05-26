package dev.talos.runtime.toolcall;

import java.util.List;
import java.util.Optional;

final class ToolRepromptTargetReadbackRepairDecision {
    private ToolRepromptTargetReadbackRepairDecision() {
    }

    static Optional<Boolean> tryHandle(LoopState state, String userTask) {
        Optional<TargetReadbackCompactRepairPlanner.Plan> appendLineRepair =
                TargetReadbackCompactRepairPlanner.nextAppendLinePlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (appendLineRepair.isPresent()) {
            TargetReadbackCompactRepairPlanner.Plan repair = appendLineRepair.get();
            state.setPendingActionObligation(
                    PendingActionObligation.appendLineTargets(List.of(repair.path())));
            state.appendLineRepairPromptedPaths.add(repair.promptedPathKey());
            return Optional.of(ToolRepromptChatExecutor.execute(
                    state, repair.messages(), repair.tools(), repair.controls(), repair.retryName()));
        }

        Optional<TargetReadbackCompactRepairPlanner.Plan> oldStringMissRepair =
                TargetReadbackCompactRepairPlanner.nextOldStringMissPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (oldStringMissRepair.isEmpty()) {
            return Optional.empty();
        }
        TargetReadbackCompactRepairPlanner.Plan repair = oldStringMissRepair.get();
        state.setPendingActionObligation(
                PendingActionObligation.oldStringMissTargets(List.of(repair.path())));
        state.oldStringMissRepairPromptedPaths.add(repair.promptedPathKey());
        return Optional.of(ToolRepromptChatExecutor.execute(
                state, repair.messages(), repair.tools(), repair.controls(), repair.retryName()));
    }
}
