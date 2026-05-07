package dev.talos.runtime.policy;

import dev.talos.spi.EngineException;

import java.util.Locale;
import java.util.List;
import java.util.Set;

/** Validates whether a model response satisfied the current turn obligation. */
public final class ResponseObligationVerifier {
    private static final Set<String> MUTATION_DEFLECTION_MARKERS = Set.of(
            "unable to create or modify files",
            "cannot create or modify files",
            "can't create or modify files",
            "do not have access to the underlying file system",
            "don't have access to the underlying file system",
            "no access to the underlying file system",
            "do not have direct access to your file system",
            "don't have direct access to your file system",
            "cannot modify files within your workspace",
            "can't modify files within your workspace",
            "cannot create files within your workspace",
            "can't create files within your workspace",
            "i can provide code snippets",
            "i can provide you with code snippets",
            "you can manually create",
            "you can create the files manually"
    );

    private ResponseObligationVerifier() {}

    public static boolean unsatisfiedNoToolResponse(ActionObligation obligation, String answer) {
        return obligation == ActionObligation.MUTATING_TOOL_REQUIRED
                || obligation == ActionObligation.CONDITIONAL_REVIEW_FIX
                || obligation == ActionObligation.WORKSPACE_OPERATION_REQUIRED;
    }

    public static boolean containsMutationCapabilityDeflection(String answer) {
        if (answer == null || answer.isBlank()) return false;
        String lower = answer.toLowerCase(Locale.ROOT);
        for (String marker : MUTATION_DEFLECTION_MARKERS) {
            if (lower.contains(marker)) return true;
        }
        return false;
    }

    public static String retryFailureSummary(String answer) {
        return retryFailureSummary(ActionObligation.MUTATING_TOOL_REQUIRED, answer);
    }

    public static String retryFailureSummary(ActionObligation obligation, String answer) {
        if (obligation == ActionObligation.CONDITIONAL_REVIEW_FIX) {
            return "[Action obligation check: the previous model response did not satisfy "
                    + "the conditional review-and-fix obligation. Inspection-only no-change "
                    + "requires evidence-backed no blocker; a concrete repair claim requires "
                    + "a write/edit tool call.]";
        }
        if (obligation == ActionObligation.WORKSPACE_OPERATION_REQUIRED) {
            return "[Action obligation check: the previous model response did not issue "
                    + "the required workspace operation tool call.]";
        }
        if (containsMutationCapabilityDeflection(answer)) {
            return "[Action obligation check: the previous model response denied workspace file access, "
                    + "but the runtime exposed write/edit tools for this turn. That denial was not accepted.]";
        }
        return "[Action obligation check: the previous model response did not issue required write/edit tool calls.]";
    }

    public static String deterministicNoActionAnswer() {
        return "[Action obligation failed: no file was changed in this turn.]\n\n"
                + "Talos can apply approved file changes in this workspace, but the model did not issue "
                + "the required write/edit tool calls on this turn, so no files were changed.";
    }

    public static String deterministicNoActionAnswer(ActionObligation obligation) {
        if (obligation == ActionObligation.WORKSPACE_OPERATION_REQUIRED) {
            return "[Action obligation failed: no workspace operation was performed in this turn.]\n\n"
                    + "Talos exposed a dedicated workspace operation tool for this request, but the model did not "
                    + "issue the required tool call, so the requested workspace operation was not applied.";
        }
        return deterministicNoActionAnswer();
    }

    public static String deterministicContextBudgetRetrySkippedAnswer(
            String retryName,
            EngineException.ContextBudgetExceeded budget
    ) {
        return "[Action obligation failed: retry could not fit in the context budget.]\n\n"
                + "Talos stopped the "
                + safeRetryName(retryName)
                + " before another model continuation because "
                + contextBudgetRetrySkippedDetail(budget)
                + "\nNo files were changed by this retry.";
    }

    public static String contextBudgetRetrySkippedDetail(EngineException.ContextBudgetExceeded budget) {
        if (budget == null) {
            return "the retry request exceeded the local context budget.";
        }
        return "the retry request exceeded the local context budget "
                + "(estimated " + budget.estimatedTokens()
                + " input tokens, budget " + budget.inputBudgetTokens()
                + ", context window " + budget.contextWindowTokens()
                + ").";
    }

    public static String deterministicRepairInspectionOnlyAnswer() {
        return "[Action obligation failed: repair/fix turn inspected files but did not change them.]\n\n"
                + "Talos required a write/edit tool call for this repair turn. The repair attempt used "
                + "only read-only inspection tools, so no files were changed.";
    }

    public static String deterministicStaticRepairWrongToolAnswer(List<String> targets) {
        return deterministicStaticRepairWrongToolAnswer(targets, false);
    }

    public static String deterministicStaticRepairWrongToolAnswer(
            List<String> targets,
            boolean partialMutation
    ) {
        String targetText = targets == null || targets.isEmpty()
                ? "the static repair target"
                : String.join(", ", targets);
        String mutationText = partialMutation
                ? "Some files may have changed before this failure, but the required repair target "
                + "was not completed."
                : "No approval was requested and no file was changed.";
        return "[Action obligation failed: static repair used the wrong mutation tool.]\n\n"
                + "Static verification repair required complete talos.write_file replacement for "
                + targetText + ", but the retry used talos.edit_file. " + mutationText;
    }

    private static String safeRetryName(String retryName) {
        if (retryName == null || retryName.isBlank()) return "retry";
        return retryName.strip();
    }
}
