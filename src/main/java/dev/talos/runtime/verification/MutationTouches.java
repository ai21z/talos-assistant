package dev.talos.runtime.verification;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.List;

/**
 * The target identity of structured mutations, shared by the replacement prior and the exact edit observation point
 * (P18, P19). A mutation touches a target through its path or through its mutation effects.
 */
final class MutationTouches {

    private MutationTouches() {}

    /** The mutation names the target as text, or names the same workspace file under another spelling. */
    static boolean sameTarget(Path root, String pathHint, String mutatedPath) {
        return normalizePath(mutatedPath).equals(pathHint)
                || WorkspacePathIdentity.matches(root, pathHint, mutatedPath);
    }

    static boolean touchesTarget(Path root, String pathHint, ToolCallLoop.ToolOutcome outcome) {
        if (outcome == null) return false;
        if (sameTarget(root, pathHint, outcome.pathHint())) return true;
        for (String affected : outcome.mutationEffects().affectedPaths()) {
            if (sameTarget(root, pathHint, affected)) return true;
        }
        return false;
    }

    /** The position of the first successful mutation after the given one that touches the target, or -1. */
    static int nextTouch(
            Path root,
            String pathHint,
            List<ToolCallLoop.ToolOutcome> outcomes,
            int position
    ) {
        if (outcomes == null) return -1;
        for (int index = position + 1; index < outcomes.size(); index++) {
            ToolCallLoop.ToolOutcome later = outcomes.get(index);
            if (later != null && later.success() && later.mutating() && touchesTarget(root, pathHint, later)) {
                return index;
            }
        }
        return -1;
    }

    static String normalizePath(String path) {
        String normalized = path == null ? "" : path.strip().replace('\\', '/');
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized;
    }
}
