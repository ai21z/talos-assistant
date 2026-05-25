package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

/**
 * Owns loop-state bookkeeping for successful workspace mutations.
 */
final class ToolMutationStateAccounting {
    static final Result NONE = new Result(false, "");

    private ToolMutationStateAccounting() {}

    record Result(boolean mutationRecorded, String mutationSummary) {
        Result {
            mutationSummary = mutationSummary == null ? "" : mutationSummary;
        }

        boolean hasMutationSummary() {
            return !mutationSummary.isBlank();
        }
    }

    static Result recordSuccessfulMutation(
            LoopState state,
            ToolCall call,
            String pathHint,
            ToolResult result
    ) {
        if (state == null || call == null || result == null || !result.success()) {
            return NONE;
        }
        if (!ToolCallSupport.isMutatingTool(call.toolName())) {
            return NONE;
        }

        state.mutationSinceStart = true;
        state.mutatingToolSuccesses++;
        recordMutationSuccess(state, pathHint);

        String summary = ToolCallSupport.firstSentenceSummary(result.output());
        String formattedSummary = summary.isBlank() ? "" : "✓ " + summary;
        if (!formattedSummary.isBlank()) {
            state.pendingMutationSummaries.add(formattedSummary);
        }
        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
        return new Result(true, formattedSummary);
    }

    private static void recordMutationSuccess(LoopState state, String pathHint) {
        if (pathHint == null || pathHint.isBlank()) return;
        String path = ToolCallSupport.normalizePath(pathHint);
        state.pathsMutatedSinceRead.add(path);
        state.staticWebFullRewriteRequiredTargets.remove(path);
    }
}
