package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;

/**
 * Classifies known tool outcome failure shapes used by recovery and
 * truthfulness logic.
 *
 * <p>T758: every shape switches on the typed {@link ToolFailureReason}
 * carried by the outcome - never on error-message prose, which is free to
 * change without affecting repair or outcome classification.
 */
public final class ToolOutcomeFailureShape {
    private ToolOutcomeFailureShape() {}

    public static boolean invalidEmptyEditArguments(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.edit_file")) return false;
        return outcome.failureReason() == ToolFailureReason.EDIT_EMPTY_ARGUMENTS;
    }

    public static boolean fullRewriteRepairRedirect(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.edit_file")) return false;
        return outcome.failureReason() == ToolFailureReason.EDIT_FULL_REWRITE_REQUIRED;
    }

    public static boolean oldStringNotFoundEditFailure(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.edit_file")) return false;
        return outcome.failureReason() == ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND;
    }

    public static boolean appendLinePreservationFailure(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.write_file")) return false;
        return outcome.failureReason() == ToolFailureReason.WRITE_APPEND_LINE_PRESERVATION;
    }

    public static boolean expectedTargetScopeFailure(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, null)) return false;
        return outcome.failureReason() == ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED;
    }

    private static boolean invalidParamsMutationFailure(ToolCallLoop.ToolOutcome outcome, String toolName) {
        if (outcome == null) return false;
        if (toolName != null && !toolName.equals(outcome.toolName())) return false;
        if (!outcome.mutating() || outcome.success() || outcome.denied()) return false;
        return ToolError.INVALID_PARAMS.equals(outcome.errorCode());
    }
}
