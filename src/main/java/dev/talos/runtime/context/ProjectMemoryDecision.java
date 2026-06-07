package dev.talos.runtime.context;

/** Redacted audit decision for one project-memory candidate. */
public record ProjectMemoryDecision(
        ProjectMemoryTier tier,
        ProjectMemoryTrust trust,
        String pathHint,
        String action,
        String decisionReason,
        String contentHash,
        int chars,
        int bytes,
        int lines,
        int estimatedTokens,
        boolean truncated
) {
    public ProjectMemoryDecision {
        tier = tier == null ? ProjectMemoryTier.WORKSPACE_ROOT : tier;
        trust = trust == null ? ProjectMemoryTrust.WORKSPACE_PROVIDED : trust;
        pathHint = pathHint == null ? "" : pathHint;
        action = action == null || action.isBlank() ? "WITHHELD_FROM_MODEL" : action;
        decisionReason = decisionReason == null || decisionReason.isBlank() ? "UNSPECIFIED" : decisionReason;
        contentHash = contentHash == null ? "" : contentHash;
        chars = Math.max(0, chars);
        bytes = Math.max(0, bytes);
        lines = Math.max(0, lines);
        estimatedTokens = Math.max(0, estimatedTokens);
    }
}
