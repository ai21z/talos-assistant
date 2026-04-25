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
        List<ToolCallLoop.ToolOutcome> mutating = loopResult == null
                ? List.of()
                : loopResult.toolOutcomes().stream()
                        .filter(ToolCallLoop.ToolOutcome::mutating)
                        .toList();

        List<ToolCallLoop.ToolOutcome> successful = mutating.stream()
                .filter(ToolCallLoop.ToolOutcome::success)
                .toList();
        List<ToolCallLoop.ToolOutcome> denied = mutating.stream()
                .filter(ToolCallLoop.ToolOutcome::denied)
                .toList();
        List<ToolCallLoop.ToolOutcome> failed = mutating.stream()
                .filter(outcome -> !outcome.success() && !outcome.denied())
                .toList();

        int totalSuccesses = successful.size() + Math.max(0, extraSuccesses);
        MutationOutcomeStatus status = classify(contract, mutating, totalSuccesses, failed, denied);
        return new MutationOutcome(status, successful, failed, denied, extraSuccesses);
    }

    public int successCount() {
        return successful.size() + extraSuccesses;
    }

    public int failureCount() {
        return failed.size() + denied.size();
    }

    private static MutationOutcomeStatus classify(
            TaskContract contract,
            List<ToolCallLoop.ToolOutcome> mutating,
            int totalSuccesses,
            List<ToolCallLoop.ToolOutcome> failed,
            List<ToolCallLoop.ToolOutcome> denied
    ) {
        boolean mutationRequested = contract != null && contract.mutationRequested();
        if (mutating.isEmpty() && totalSuccesses == 0) {
            return mutationRequested
                    ? MutationOutcomeStatus.NOT_ATTEMPTED
                    : MutationOutcomeStatus.NOT_REQUESTED;
        }
        if (!denied.isEmpty() && totalSuccesses == 0) {
            return MutationOutcomeStatus.DENIED;
        }
        if (totalSuccesses > 0 && (failed.size() + denied.size()) > 0) {
            return MutationOutcomeStatus.PARTIAL;
        }
        if (totalSuccesses > 0) {
            return MutationOutcomeStatus.SUCCEEDED;
        }
        return MutationOutcomeStatus.FAILED;
    }
}
