package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolError;

import java.util.Locale;

/** Classifies known tool outcome failure shapes used by recovery and truthfulness logic. */
public final class ToolOutcomeFailureShape {
    private ToolOutcomeFailureShape() {}

    public static boolean invalidEmptyEditArguments(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.edit_file")) return false;
        String lower = lowerErrorMessage(outcome);
        boolean oldStringProblem = lower.contains("old_string")
                && (lower.contains("empty")
                || lower.contains("non-empty")
                || lower.contains("present"));
        boolean newStringProblem = lower.contains("new_string")
                && lower.contains("missing required parameter");
        return oldStringProblem || newStringProblem;
    }

    public static boolean fullRewriteRepairRedirect(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.edit_file")) return false;
        return lowerErrorMessage(outcome)
                .contains("static verification repair requires a complete talos.write_file replacement");
    }

    public static boolean oldStringNotFoundEditFailure(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.edit_file")) return false;
        return lowerErrorMessage(outcome).contains("old_string not found");
    }

    public static boolean appendLinePreservationFailure(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, "talos.write_file")) return false;
        return lowerErrorMessage(outcome).contains("append-line write_file");
    }

    public static boolean expectedTargetScopeFailure(ToolCallLoop.ToolOutcome outcome) {
        if (!invalidParamsMutationFailure(outcome, null)) return false;
        return lowerErrorMessage(outcome).contains("target outside expected targets before approval");
    }

    private static boolean invalidParamsMutationFailure(ToolCallLoop.ToolOutcome outcome, String toolName) {
        if (outcome == null) return false;
        if (toolName != null && !toolName.equals(outcome.toolName())) return false;
        if (!outcome.mutating() || outcome.success() || outcome.denied()) return false;
        return ToolError.INVALID_PARAMS.equals(outcome.errorCode());
    }

    private static String lowerErrorMessage(ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null || outcome.errorMessage() == null) return "";
        return outcome.errorMessage().toLowerCase(Locale.ROOT);
    }
}
