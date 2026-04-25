package dev.talos.runtime.failure;

import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;

import java.util.Comparator;
import java.util.Map;

public record FailurePolicy(
        int maxIterations,
        int maxSameToolFailures,
        int maxSamePathFailures,
        int maxNoProgressIterations,
        boolean rereadBeforeRetry,
        boolean downgradeToInspectOnDrift
) {
    public FailurePolicy {
        maxIterations = Math.max(1, maxIterations);
        maxSameToolFailures = Math.max(1, maxSameToolFailures);
        maxSamePathFailures = Math.max(1, maxSamePathFailures);
        maxNoProgressIterations = Math.max(1, maxNoProgressIterations);
    }

    public static FailurePolicy defaults(int maxIterations) {
        return new FailurePolicy(
                maxIterations,
                3,
                3,
                3,
                true,
                false
        );
    }

    public FailureDecision afterIteration(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (state == null || outcome == null) return FailureDecision.continueLoop();
        updateNoProgress(state, outcome);
        if (outcome.failuresThisIteration() <= 0) return FailureDecision.continueLoop();

        FailureDecision samePath = repeatedFailureDecision(
                state.failureCountsByPath,
                maxSamePathFailures,
                "path");
        if (samePath.shouldStop()) return withActionForProgress(state, samePath.reason());

        FailureDecision sameTool = repeatedFailureDecision(
                state.failureCountsByTool,
                maxSameToolFailures,
                "tool");
        if (sameTool.shouldStop()) return withActionForProgress(state, sameTool.reason());

        if (state.noProgressIterations >= maxNoProgressIterations) {
            return withActionForProgress(
                    state,
                    "failure policy stopped the tool loop after "
                            + state.noProgressIterations
                            + " consecutive no-progress iteration(s).");
        }

        return FailureDecision.continueLoop();
    }

    private static void updateNoProgress(
            LoopState state,
            ToolCallExecutionStage.IterationOutcome outcome
    ) {
        if (outcome.successesThisIteration() > 0 || outcome.mutationsThisIteration() > 0) {
            state.noProgressIterations = 0;
        } else if (outcome.failuresThisIteration() > 0) {
            state.noProgressIterations++;
        }
    }

    private static FailureDecision repeatedFailureDecision(
            Map<String, Integer> counts,
            int threshold,
            String label
    ) {
        if (counts == null || counts.isEmpty()) return FailureDecision.continueLoop();
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() >= threshold)
                .max(Comparator.comparingInt(Map.Entry::getValue))
                .map(entry -> FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "failure policy stopped the tool loop after "
                                + entry.getValue()
                                + " failed call(s) for "
                                + label
                                + " `"
                                + entry.getKey()
                                + "`."))
                .orElseGet(FailureDecision::continueLoop);
    }

    private static FailureDecision withActionForProgress(LoopState state, String reason) {
        FailureAction action = state.mutatingToolSuccesses > 0
                ? FailureAction.STOP_WITH_PARTIAL
                : FailureAction.ASK_USER;
        return FailureDecision.stop(action, reason);
    }
}
