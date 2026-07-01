package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolResult;

/**
 * Pure classifier for failed tool execution results.
 *
 * <p>This class does not mutate loop state and does not choose repair policy.
 * It only centralizes error-code and exact-message-prefix interpretation so
 * later accounting code can consume a stable classification.
 */
final class ToolExecutionFailureClassifier {
    private static final Classification NOT_FAILED =
            new Classification(false, false, false, false, false, false, "", false);

    private ToolExecutionFailureClassifier() {}

    record Classification(
            boolean failed,
            boolean denied,
            boolean mutatingDenied,
            boolean userApprovalDenial,
            boolean preApprovalPathPolicyBlock,
            boolean expectedTargetScopeBlock,
            String unsupportedReadPath,
            boolean oldStringNotFound
    ) {
        Classification {
            unsupportedReadPath = unsupportedReadPath == null ? "" : unsupportedReadPath;
        }
    }

    static Classification classify(ToolCall call, ToolResult result, String pathHint) {
        if (result == null || result.success()) {
            return NOT_FAILED;
        }
        ToolError error = result.error();
        boolean failed = true;
        boolean denied = error != null && ToolError.DENIED.equals(error.code());
        boolean mutating = call != null && ToolCallSupport.isMutatingTool(call.toolName());
        boolean invalidParams = error != null && ToolError.INVALID_PARAMS.equals(error.code());
        // T758: classification switches on the typed ToolFailureReason carried
        // by the error - never on message prose, which is free to change.
        ToolFailureReason reason = error == null ? ToolFailureReason.NONE : error.reason();
        boolean userApprovalDenial = denied
                && reason == ToolFailureReason.USER_APPROVAL_DENIED;
        boolean preApprovalPathPolicyBlock = invalidParams
                && (reason == ToolFailureReason.PRE_APPROVAL_PATH_NOT_ALLOWED
                || reason == ToolFailureReason.PRE_APPROVAL_PATH_INVALID
                || reason == ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED);
        boolean expectedTargetScopeBlock = invalidParams
                && reason == ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED;
        String unsupportedReadPath = unsupportedReadPath(call, error, pathHint);
        boolean oldStringNotFound = invalidParams
                && reason == ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND;

        return new Classification(
                failed,
                denied,
                denied && mutating,
                userApprovalDenial,
                preApprovalPathPolicyBlock,
                expectedTargetScopeBlock,
                unsupportedReadPath,
                oldStringNotFound);
    }

    private static String unsupportedReadPath(ToolCall call, ToolError error, String pathHint) {
        if (error == null || !ToolError.UNSUPPORTED_FORMAT.equals(error.code())) return "";
        if (!ReadEvidenceStateAccounting.isReadFileTool(call)) return "";
        if (pathHint == null || pathHint.isBlank()) return "";
        return ToolCallSupport.normalizePath(pathHint);
    }
}
