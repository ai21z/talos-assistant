package dev.talos.runtime.toolcall;

import dev.talos.core.util.UiChrome;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

/**
 * Owns loop-state bookkeeping for successful and failed-with-effects mutations.
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
        if (state == null || call == null || result == null
                || (!result.success() && !result.mutationEffects().hasEffects())) {
            return NONE;
        }
        if (!ToolCallSupport.isMutatingTool(call.toolName())) {
            return NONE;
        }

        state.mutationSinceStart = true;
        if (!result.success()) {
            for (String affected : result.mutationEffects().affectedPaths()) {
                state.pathsMutatedSinceRead.add(ToolCallSupport.normalizePath(affected));
            }
            ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
            // Do not clear recovery guards or claim a successful mutation merely
            // because an effect occurred before failure.
            return new Result(true, "");
        }
        state.mutatingToolSuccesses++;
        recordMutationSuccess(state, pathHint);

        String summary = ToolCallSupport.firstSentenceSummary(result.output());
        String formattedSummary = summary.isBlank() ? "" : UiChrome.CHECK_PREFIX + summary;
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
        // T1003: the approved write settles the invalid-edit history for
        // this path. Stale strikes would otherwise feed a later stop that
        // falsely claims no approval was requested and no file was changed,
        // and a lingering recovery target would keep blocking legitimate
        // surgical edits of the just-rewritten file.
        state.emptyEditArgumentFailuresByPath.remove(path);
        state.editRecoveryFullRewriteTargets.remove(path);
    }
}
