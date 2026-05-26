package dev.talos.runtime.toolcall;

import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

import java.util.List;

/**
 * Converts failed-tool classifications into iteration-local loop signals.
 *
 * <p>This owner does not classify raw errors and does not record aggregate
 * failure counts. It only translates an already-classified failed tool result
 * into the booleans/list consumed by the current iteration outcome.
 */
final class ToolFailureIterationSignals {
    private static final Result NONE = new Result(false, false, false, List.of());

    private ToolFailureIterationSignals() {}

    record Result(
            boolean mutatingDenied,
            boolean approvalDenied,
            boolean pathPolicyBlocked,
            List<String> unsupportedReadPaths
    ) {
        Result {
            unsupportedReadPaths = unsupportedReadPaths == null
                    ? List.of()
                    : List.copyOf(unsupportedReadPaths);
        }

        boolean hasUnsupportedReadPaths() {
            return !unsupportedReadPaths.isEmpty();
        }
    }

    static Result from(
            LoopState state,
            ToolCall call,
            ToolExecutionFailureClassifier.Classification classification,
            ToolResult result
    ) {
        if (classification == null || !classification.failed()) {
            return NONE;
        }

        boolean mutating = call != null && ToolCallSupport.isMutatingTool(call.toolName());
        boolean mutatingDenied = classification.mutatingDenied();
        boolean approvalDenied = classification.userApprovalDenial() && mutating;
        boolean pathPolicyBlocked = classification.preApprovalPathPolicyBlock() && mutating;
        if (pathPolicyBlocked && classification.expectedTargetScopeBlock() && state != null) {
            state.failureDecision = FailureDecision.stop(
                    FailureAction.ASK_USER,
                    result == null ? "" : result.errorMessage());
        }

        List<String> unsupportedReadPaths = classification.unsupportedReadPath().isBlank()
                ? List.of()
                : List.of(classification.unsupportedReadPath());
        return new Result(mutatingDenied, approvalDenied, pathPolicyBlocked, unsupportedReadPaths);
    }
}
