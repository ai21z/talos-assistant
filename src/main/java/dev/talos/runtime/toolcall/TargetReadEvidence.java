package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.tools.ToolAliasPolicy;
import dev.talos.tools.WorkspacePathIdentity;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;

/**
 * Decides whether a requested target was read this turn, whichever spelling the read used. A target counts as
 * read when its normalized text is in the read-path set, when a successful read recorded the same text, or when
 * a successful read's raw recorded path names the same workspace file. The normalized read-path set is never
 * given to identity, because on POSIX its rewritten backslashes could name another file.
 */
public final class TargetReadEvidence {
    private TargetReadEvidence() {}

    public static boolean successfullyRead(
            String target,
            Collection<String> normalizedReadPaths,
            List<ToolCallLoop.ToolOutcome> outcomes,
            Path workspace
    ) {
        String normalizedTarget = ToolCallSupport.normalizePath(target == null ? "" : target);
        if (normalizedTarget.isBlank()) return false;
        if (normalizedReadPaths != null && normalizedReadPaths.contains(normalizedTarget)) return true;
        if (outcomes == null) return false;
        for (ToolCallLoop.ToolOutcome outcome : outcomes) {
            if (outcome == null || !outcome.success() || outcome.denied() || !isRead(outcome)) continue;
            if (names(target, outcome.pathHint(), workspace)) return true;
        }
        return false;
    }

    /** A recorded path names the target by normalized text or as the same workspace file. */
    public static boolean names(String target, String recordedPath, Path workspace) {
        if (target == null || recordedPath == null || recordedPath.isBlank()) return false;
        String normalizedTarget = ToolCallSupport.normalizePath(target);
        return !normalizedTarget.isBlank()
                && (normalizedTarget.equals(ToolCallSupport.normalizePath(recordedPath))
                || WorkspacePathIdentity.matches(workspace, target, recordedPath));
    }

    public static boolean isRead(ToolCallLoop.ToolOutcome outcome) {
        return outcome != null && "read_file".equals(ToolAliasPolicy.localCanonicalName(outcome.toolName()));
    }
}
