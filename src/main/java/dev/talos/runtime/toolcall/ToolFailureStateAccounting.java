package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;

/**
 * Owns loop-state bookkeeping for failed tool executions.
 */
final class ToolFailureStateAccounting {
    static final Result NONE = new Result(false);

    private ToolFailureStateAccounting() {}

    record Result(boolean failureRecorded) {}

    static Result recordFailure(LoopState state, ToolCall call, String pathHint) {
        return recordFailureCounts(state, call, pathHint);
    }

    static Result recordFailure(
            LoopState state,
            ToolCall call,
            ToolExecutionFailureClassifier.Classification classification,
            String pathHint,
            boolean isEditFile
    ) {
        Result result = recordFailureCounts(state, call, pathHint);
        if (!result.failureRecorded()) {
            return result;
        }
        if (classification != null
                && shouldClearSuccessfulReadCallsAfterFailure(state, call, classification, pathHint, isEditFile)) {
            ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
        }
        return result;
    }

    private static Result recordFailureCounts(LoopState state, ToolCall call, String pathHint) {
        if (state == null || call == null) return NONE;

        state.failedCalls++;
        if (call.toolName() != null && !call.toolName().isBlank()) {
            state.failureCountsByTool.merge(call.toolName(), 1, Integer::sum);
        }
        if (pathHint != null && !pathHint.isBlank()) {
            state.failureCountsByPath.merge(ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
        }
        return new Result(true);
    }

    private static boolean shouldClearSuccessfulReadCallsAfterFailure(
            LoopState state,
            ToolCall call,
            ToolExecutionFailureClassifier.Classification classification,
            String pathHint,
            boolean isEditFile
    ) {
        if (call == null || !ToolCallSupport.isMutatingTool(call.toolName())) return false;
        if (classification.expectedTargetScopeBlock()) {
            return false;
        }
        if (isEditFile
                && classification.oldStringNotFound()
                && wasPathReadThisTurn(state, pathHint)
                && !wasMutatedSinceRead(state, pathHint)) {
            return false;
        }
        return true;
    }

    private static boolean wasPathReadThisTurn(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsReadThisTurn.contains(ToolCallSupport.normalizePath(pathHint));
    }

    private static boolean wasMutatedSinceRead(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsMutatedSinceRead.contains(ToolCallSupport.normalizePath(pathHint));
    }
}
