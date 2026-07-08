package dev.talos.runtime.toolcall;

import dev.talos.tools.ToolCall;

import java.util.Set;

final class EditFilePreApprovalGuard {
    enum Kind {
        FULL_REWRITE_REPAIR_REQUIRED,
        STALE_REREAD_REQUIRED,
        DUPLICATE_FAILED_EDIT,
        NONE
    }

    record Decision(
            Kind kind,
            String diagnostic,
            String normalizedPath,
            boolean emptyEditArguments,
            String callSignature
    ) {}

    private EditFilePreApprovalGuard() {}

    static Decision decision(
            ToolCall call,
            LoopState state,
            String pathHint,
            boolean strict,
            Set<String> staleRereadRequiredAtStart,
            Set<String> fullRewriteRepairTargets
    ) {
        if (call == null || strict || !"talos.edit_file".equals(call.canonicalToolName())) return null;
        String normalizedPath = normalizePath(pathHint);
        if (fullRewriteRepairTargets != null && fullRewriteRepairTargets.contains(normalizedPath)) {
            return new Decision(
                    Kind.FULL_REWRITE_REPAIR_REQUIRED,
                    fullRewriteRepairRequiredDiagnostic(pathHint),
                    normalizedPath,
                    false,
                    "");
        }
        if (staleRereadRequiredAtStart != null && staleRereadRequiredAtStart.contains(normalizedPath)) {
            return new Decision(
                    Kind.STALE_REREAD_REQUIRED,
                    staleEditRereadRequiredDiagnostic(pathHint),
                    normalizedPath,
                    false,
                    "");
        }
        if (state == null) return null;
        if (state.editFailuresByPath.getOrDefault(normalizedPath, 0) >= 2) {
            return new Decision(
                    Kind.DUPLICATE_FAILED_EDIT,
                    repeatedPathFailureDiagnostic(pathHint),
                    normalizedPath,
                    false,
                    ToolCallSupport.buildCallSignature(call));
        }
        String callSignature = ToolCallSupport.buildCallSignature(call);
        if (!state.failedCallSignatures.contains(callSignature)) return null;
        boolean emptyEditArguments = ToolCallSupport.hasEmptyEditArguments(call);
        String diagnostic = emptyEditArguments
                ? emptyEditArgumentDiagnostic(pathHint, wasPathReadThisTurn(state, pathHint))
                : "This exact edit was already attempted and failed. "
                        + "Call talos.read_file to see the file's current state, "
                        + "then provide the exact raw content (without line-number prefixes) in old_string. "
                        + "Alternatively, use talos.write_file to replace the entire file content.";
        return new Decision(
                Kind.DUPLICATE_FAILED_EDIT,
                diagnostic,
                normalizedPath,
                emptyEditArguments,
                callSignature);
    }

    private static String repeatedPathFailureDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "talos.edit_file has already failed repeatedly for " + target
                + " in this turn. Do not keep guessing old_string. Call talos.read_file "
                + "to ground the current bytes, or use talos.write_file with the complete updated file content. "
                + "No approval was requested and no file was changed.";
    }

    private static boolean wasPathReadThisTurn(LoopState state, String pathHint) {
        return state != null
                && pathHint != null
                && state.pathsReadThisTurn.contains(normalizePath(pathHint));
    }

    private static String emptyEditArgumentDiagnostic(String pathHint, boolean pathWasRead) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        String prefix = pathWasRead
                ? "Repeated empty or missing talos.edit_file arguments for " + target + " after the file was read. "
                : "Repeated empty or missing talos.edit_file arguments for " + target + ". ";
        return prefix
                + "`old_string` was empty or `new_string` was missing, so no approval was requested "
                + "and no file was changed. Copy the exact `old_string` from the latest "
                + "talos.read_file result and provide the intended `new_string`, or stop "
                + "and explain why the edit cannot be formed.";
    }

    private static String staleEditRereadRequiredDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "A previous edit changed " + target
                + ", then another edit for the same file failed because old_string was not found. "
                + "Call talos.read_file for " + target
                + " in a separate follow-up step before attempting another talos.edit_file. "
                + "No approval was requested and no additional file change was made.";
    }

    private static String fullRewriteRepairRequiredDiagnostic(String pathHint) {
        String target = pathHint == null || pathHint.isBlank()
                ? "the target file"
                : "`" + pathHint + "`";
        return "Static verification repair requires a complete talos.write_file replacement for "
                + target + ". This talos.edit_file call was not executed, no approval was requested, "
                + "and no file was changed. Use talos.write_file with the full corrected file content "
                + "for this small web file.";
    }

    private static String normalizePath(String pathHint) {
        return ToolCallSupport.normalizePath(pathHint == null ? "" : pathHint);
    }
}
