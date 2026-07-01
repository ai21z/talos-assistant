package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;

final class ToolOutcomeFactory {
    private static final int LIST_DIR_EVIDENCE_SUMMARY_CHARS = 4_000;

    private ToolOutcomeFactory() {}

    static ToolCallLoop.ToolOutcome failedEditPreApproval(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            EditFilePreApprovalGuard.Decision decision
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                false,
                true,
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_PARAMS,
                null,
                null,
                editPreApprovalReason(decision));
    }

    static ToolCallLoop.ToolOutcome failedPreExecutionMutation(
            ToolCall call,
            String pathHint,
            String diagnosticError,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolFailureReason failureReason
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                false,
                true,
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_PARAMS,
                workspaceOperationPlan,
                null,
                failureReason);
    }

    static ToolCallLoop.ToolOutcome failedPreExecutionRead(
            ToolCall call,
            String pathHint,
            String diagnosticError
    ) {
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                false,
                false,
                false,
                "",
                diagnosticError,
                null,
                ToolError.INVALID_PARAMS);
    }

    static ToolCallLoop.ToolOutcome executed(
            ToolCall call,
            String pathHint,
            ToolResult result,
            ToolExecutionFailureClassifier.Classification classification,
            WorkspaceOperationPlan workspaceOperationPlan,
            ToolMutationEvidence mutationEvidence
    ) {
        boolean success = result != null && result.success();
        return new ToolCallLoop.ToolOutcome(
                toolName(call),
                pathHint,
                success,
                call != null && ToolCallSupport.isMutatingTool(call.toolName()),
                classification != null && classification.denied(),
                success ? toolOutcomeSummary(toolName(call), result.output()) : "",
                success ? "" : errorMessage(result),
                result == null ? null : result.verification(),
                result == null || result.error() == null ? "" : result.error().code(),
                workspaceOperationPlan,
                mutationEvidence,
                result == null || result.error() == null
                        ? ToolFailureReason.NONE
                        : result.error().reason());
    }

    /** Maps the edit pre-approval guard's typed verdict to a failure reason (T758). */
    private static ToolFailureReason editPreApprovalReason(EditFilePreApprovalGuard.Decision decision) {
        if (decision == null) return ToolFailureReason.NONE;
        if (decision.emptyEditArguments()) return ToolFailureReason.EDIT_EMPTY_ARGUMENTS;
        return switch (decision.kind()) {
            case FULL_REWRITE_REPAIR_REQUIRED -> ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED;
            case STALE_REREAD_REQUIRED -> ToolFailureReason.EDIT_STALE_REREAD_REQUIRED;
            case DUPLICATE_FAILED_EDIT -> ToolFailureReason.EDIT_DUPLICATE_FAILED;
            case NONE -> ToolFailureReason.NONE;
        };
    }

    private static String toolOutcomeSummary(String toolName, String output) {
        if (!"talos.list_dir".equals(toolName)) {
            return ToolCallSupport.firstSentenceSummary(output);
        }
        String value = output == null ? "" : output.strip();
        if (value.length() <= LIST_DIR_EVIDENCE_SUMMARY_CHARS) {
            return value;
        }
        return value.substring(0, LIST_DIR_EVIDENCE_SUMMARY_CHARS)
                + "\n... (tool outcome summary truncated)";
    }

    private static String toolName(ToolCall call) {
        return call == null ? "" : call.toolName();
    }

    private static String errorMessage(ToolResult result) {
        return result == null ? "" : result.errorMessage();
    }
}
