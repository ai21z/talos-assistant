package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolResult;

/**
 * Owns repair-state bookkeeping produced by failed edit_file attempts.
 */
final class EditFailureRepairStateAccounting {
    private EditFailureRepairStateAccounting() {}

    record Result(ToolResult toolResult) {}

    static void recordPreApprovalDecision(
            LoopState state,
            EditFilePreApprovalGuard.Decision decision,
            String pathHint
    ) {
        if (state == null || decision == null) return;
        if (decision.kind() == EditFilePreApprovalGuard.Kind.STALE_REREAD_REQUIRED) {
            state.staleEditRereadIgnoredPath = decision.normalizedPath();
        }
        if (decision.emptyEditArguments()) {
            recordEmptyEditArgumentFailure(state, pathHint);
        }
    }

    static Result recordFailedEditResult(
            LoopState state,
            ToolCall call,
            ToolExecutionFailureClassifier.Classification classification,
            String pathHint,
            ToolResult result,
            boolean strict
    ) {
        if (state == null || call == null || result == null || result.success()) {
            return new Result(result);
        }
        if (!"talos.edit_file".equals(call.toolName())) {
            return new Result(result);
        }

        state.failedCallSignatures.add(ToolCallSupport.buildCallSignature(call));
        boolean oldStringNotFound = classification != null && classification.oldStringNotFound();
        if (oldStringNotFound && wasMutatedSinceRead(state, pathHint)) {
            recordStaleEditFailure(state, pathHint);
        }
        if (oldStringNotFound && shouldRecoverStaticWebEditFailureWithFullRewrite(state, pathHint)) {
            recordStaticWebFullRewriteRequired(state, pathHint);
        }
        if (ToolCallSupport.hasEmptyEditArguments(call)) {
            recordEmptyEditArgumentFailure(state, pathHint);
        }

        ToolResult adjusted = result;
        if (!strict && pathHint != null) {
            int failCount = state.editFailuresByPath.merge(
                    ToolCallSupport.normalizePath(pathHint), 1, Integer::sum);
            if (failCount >= 2) {
                state.cushionFiresE1Suggestion++;
                adjusted = ToolResult.fail(ToolError.invalidParams(
                        result.errorMessage()
                                + "\nSuggestion: edit_file has failed on this file multiple times. "
                                + "Consider using talos.write_file with the complete updated file content instead."));
            }
        }
        return new Result(adjusted);
    }

    private static void recordEmptyEditArgumentFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.emptyEditArgumentFailuresByPath.merge(
                normalizePath(pathHint), 1, Integer::sum);
    }

    private static void recordStaleEditFailure(LoopState state, String pathHint) {
        if (state == null || pathHint == null || pathHint.isBlank()) return;
        state.staleEditFailuresByPath.merge(normalizePath(pathHint), 1, Integer::sum);
    }

    private static boolean wasMutatedSinceRead(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsMutatedSinceRead.contains(normalizePath(pathHint));
    }

    private static boolean shouldRecoverStaticWebEditFailureWithFullRewrite(
            LoopState state,
            String pathHint
    ) {
        if (state == null || pathHint == null || pathHint.isBlank()) return false;
        String path = normalizePath(pathHint);
        if (!StaticWebCapabilityProfile.isSmallWebFile(path)) return false;
        if (!state.pathsReadThisTurn.contains(path)) return false;
        TaskContract contract = TaskContractResolver.fromMessages(state.messages);
        if (contract == null || !contract.mutationAllowed() || !contract.verificationRequired()) {
            return false;
        }
        String userTask = ToolCallSupport.latestUserRequestIn(state.messages);
        if (!looksLikeStaticWebWork(userTask)) return false;
        if (contract.expectedTargets().isEmpty()) return true;
        return contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .anyMatch(StaticWebCapabilityProfile::isSmallWebFile);
    }

    private static boolean looksLikeStaticWebWork(String userTask) {
        if (userTask == null || userTask.isBlank()) return false;
        String lower = userTask.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("static web")
                || lower.contains("browser")
                || lower.contains("button")
                || lower.contains("html")
                || lower.contains("javascript")
                || lower.contains("script.js")
                || lower.contains("styles.css");
    }

    private static void recordStaticWebFullRewriteRequired(LoopState state, String pathHint) {
        String path = normalizePath(pathHint);
        if (path.isBlank()) return;
        if (state.staticWebFullRewriteRequiredTargets.add(path)) {
            LocalTurnTraceCapture.recordRepair(
                    "PLANNED",
                    "static-web-edit-rewrite target=" + path
                            + " reason=old_string-not-found-after-read");
        }
    }

    private static String normalizePath(String pathHint) {
        return ToolCallSupport.normalizePath(pathHint == null ? "" : pathHint);
    }
}
