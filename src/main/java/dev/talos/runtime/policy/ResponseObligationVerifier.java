package dev.talos.runtime.policy;

import java.util.Locale;
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
        if (obligation != ActionObligation.MUTATING_TOOL_REQUIRED) return false;
        return true;
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

    public static String deterministicRepairInspectionOnlyAnswer() {
        return "[Action obligation failed: repair/fix turn inspected files but did not change them.]\n\n"
                + "Talos required a write/edit tool call for this repair turn. The retry used only "
                + "read-only inspection tools, so no files were changed.";
    }
}
