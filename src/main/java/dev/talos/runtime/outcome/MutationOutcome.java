package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;

import java.util.List;

/**
 * The mutation outcome of one turn.
 *
 * @param status         the mutation status
 * @param successful     the successful mutating outcomes, in turn order
 * @param failed         the unresolved mutating outcomes whose execution failed or cannot be proven not to have run
 * @param denied         every denied mutating outcome, which decides the recorded approval status
 * @param extraSuccesses successes outside the tool loop
 * @param notRun         the unresolved proposals that never reached the tool registry, denials included, in turn
 *                       order (LA-F7)
 */
public record MutationOutcome(
        MutationOutcomeStatus status,
        List<ToolCallLoop.ToolOutcome> successful,
        List<ToolCallLoop.ToolOutcome> failed,
        List<ToolCallLoop.ToolOutcome> denied,
        int extraSuccesses,
        List<ToolCallLoop.ToolOutcome> notRun
) {
    public MutationOutcome {
        status = status == null ? MutationOutcomeStatus.NOT_REQUESTED : status;
        successful = successful == null ? List.of() : List.copyOf(successful);
        failed = failed == null ? List.of() : List.copyOf(failed);
        denied = denied == null ? List.of() : List.copyOf(denied);
        extraSuccesses = Math.max(0, extraSuccesses);
        notRun = notRun == null ? List.of() : List.copyOf(notRun);
    }

    /** Compatibility shape without the never-ran split: the denials are the proposals that did not run. */
    public MutationOutcome(
            MutationOutcomeStatus status,
            List<ToolCallLoop.ToolOutcome> successful,
            List<ToolCallLoop.ToolOutcome> failed,
            List<ToolCallLoop.ToolOutcome> denied,
            int extraSuccesses
    ) {
        this(status, successful, failed, denied, extraSuccesses, denied);
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
        return failed.size() + notRun.size();
    }

}
