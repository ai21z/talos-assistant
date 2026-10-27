package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;

import java.util.List;

public record MutationOutcome(
        MutationOutcomeStatus status,
        List<ToolCallLoop.ToolOutcome> successful,
        List<ToolCallLoop.ToolOutcome> failed,
        List<ToolCallLoop.ToolOutcome> denied,
        int extraSuccesses
) {
    public MutationOutcome {
        status = status == null ? MutationOutcomeStatus.NOT_REQUESTED : status;
        successful = successful == null ? List.of() : List.copyOf(successful);
        failed = failed == null ? List.of() : List.copyOf(failed);
        denied = denied == null ? List.of() : List.copyOf(denied);
        extraSuccesses = Math.max(0, extraSuccesses);
    }

    public static MutationOutcome from(
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            int extraSuccesses
    ) {
        return MutationRecoveryAssessment.provisional(
                contract,
                null,
                loopResult,
                extraSuccesses).mutationOutcome();
    }

    public int successCount() {
        return successful.size() + extraSuccesses;
    }

    public int failureCount() {
        return failed.size() + denied.size();
    }

}
