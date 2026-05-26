package dev.talos.runtime.toolcall;

import java.util.List;
import java.util.Optional;

final class ToolRepromptSourceEvidenceRepairDecision {
    private ToolRepromptSourceEvidenceRepairDecision() {
    }

    static Optional<Boolean> tryHandle(LoopState state, String userTask) {
        Optional<SourceEvidenceExactRepairPlanner.Plan> sourceEvidenceRepair =
                SourceEvidenceExactRepairPlanner.nextPlan(
                        state,
                        ToolRepromptRequestBuilder.currentNativeToolSpecs(state),
                        userTask);
        if (sourceEvidenceRepair.isEmpty()) {
            return Optional.empty();
        }
        SourceEvidenceExactRepairPlanner.Plan repair = sourceEvidenceRepair.get();
        state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of(repair.path())));
        state.sourceEvidenceExactRepairPromptedKeys.add(repair.key());
        return Optional.of(ToolRepromptChatExecutor.execute(state, repair.messages(), repair.tools(), repair.controls(),
                "source-evidence exact compact repair"));
    }
}
