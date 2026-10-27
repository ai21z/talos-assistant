package dev.talos.runtime.context;

/** Sanitized project-memory source admitted before final request preparation. */
public record ProjectMemorySource(
        ProjectMemoryTier tier,
        ProjectMemoryTrust trust,
        String pathHint,
        String content,
        String contentHash,
        int chars,
        int bytes,
        int lines,
        int estimatedTokens,
        boolean truncated
) {
    public ProjectMemorySource {
        tier = tier == null ? ProjectMemoryTier.WORKSPACE_ROOT : tier;
        trust = trust == null ? ProjectMemoryTrust.WORKSPACE_PROVIDED : trust;
        pathHint = pathHint == null ? "" : pathHint;
        content = content == null ? "" : content;
        contentHash = contentHash == null ? "" : contentHash;
        chars = Math.max(0, chars);
        bytes = Math.max(0, bytes);
        lines = Math.max(0, lines);
        estimatedTokens = Math.max(0, estimatedTokens);
    }

    ProjectMemoryDecision decision(String action, String reason) {
        return new ProjectMemoryDecision(
                tier,
                trust,
                pathHint,
                action,
                reason,
                contentHash,
                chars,
                bytes,
                lines,
                estimatedTokens,
                truncated);
    }

    public dev.talos.core.context.ContextItem contextItem() {
        return dev.talos.core.context.ContextItem.fromText(
                dev.talos.core.context.ContextItemSource.PROJECT_MEMORY,
                trust == ProjectMemoryTrust.USER_OWNED
                        ? dev.talos.core.context.ExecutionBoundary.LOCAL_USER_CONFIGURATION
                        : dev.talos.core.context.ExecutionBoundary.LOCAL_WORKSPACE,
                dev.talos.core.context.ContextPrivacyClass.NORMAL,
                pathHint, content, estimatedTokens);
    }
}
