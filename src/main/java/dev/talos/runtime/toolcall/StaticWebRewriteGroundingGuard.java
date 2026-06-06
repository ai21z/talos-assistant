package dev.talos.runtime.toolcall;

import dev.talos.runtime.capability.StaticWebCapabilityProfile;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.ToolCall;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

final class StaticWebRewriteGroundingGuard {
    private StaticWebRewriteGroundingGuard() {}

    static String diagnostic(
            ToolCall call,
            LoopState state,
            TaskContract contract,
            String pathHint
    ) {
        if (call == null || state == null || contract == null || pathHint == null || pathHint.isBlank()) {
            return null;
        }
        if (!"write_file".equals(ToolAliasPolicy.localCanonicalName(call.toolName()))) {
            return null;
        }
        String path = ToolCallSupport.normalizePath(pathHint);
        if (!StaticWebCapabilityProfile.isSmallWebFile(path)) return null;
        if (!contract.mutationAllowed() || !contract.verificationRequired()) return null;
        if (contract.type() != TaskType.FILE_EDIT && contract.type() != TaskType.FILE_CREATE) return null;
        if (!contract.expectedTargets().stream()
                .map(ToolCallSupport::normalizePath)
                .anyMatch(path::equalsIgnoreCase)) {
            return null;
        }
        if (!looksLikeStaticWebRedesign(contract.originalUserRequest())) return null;
        if (!existingWorkspaceFile(state.workspace, path)) return null;
        if (state.pathsReadThisTurn.contains(path.toLowerCase(Locale.ROOT))
                || state.pathsReadThisTurn.contains(path)) {
            return null;
        }
        return "Static-web full-file rewrite must be grounded before approval: read "
                + path
                + " before rewriting it, then call talos.write_file with the complete updated file content. "
                + "No approval was requested and no file was changed.";
    }

    private static boolean existingWorkspaceFile(Path workspace, String path) {
        if (workspace == null || path == null || path.isBlank()) return false;
        try {
            Path resolved = workspace.resolve(path).normalize();
            return resolved.startsWith(workspace.normalize()) && Files.isRegularFile(resolved);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private static boolean looksLikeStaticWebRedesign(String request) {
        if (request == null || request.isBlank()) return false;
        String lower = request.toLowerCase(Locale.ROOT);
        return lower.contains("look better")
                || lower.contains("looks better")
                || lower.contains("make it better")
                || lower.contains("more modern")
                || lower.contains("redesign")
                || lower.contains("rewrite")
                || lower.contains("tailwind")
                || lower.contains("according to my intent")
                || lower.contains("still bad");
    }
}
