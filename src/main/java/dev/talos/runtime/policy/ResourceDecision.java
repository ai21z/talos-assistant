package dev.talos.runtime.policy;

/** Workspace-relative resource classification used by permission policy. */
public record ResourceDecision(
        String rawPath,
        String relativePath,
        boolean hasPath,
        boolean insideWorkspace,
        boolean workspaceEscape,
        boolean protectedPath,
        String protectedKind
) {
    public ResourceDecision {
        rawPath = rawPath == null ? "" : rawPath;
        relativePath = relativePath == null ? "" : relativePath;
        protectedKind = protectedKind == null ? "" : protectedKind;
    }

    public static ResourceDecision noPath() {
        return new ResourceDecision("", "", false, true, false, false, "");
    }
}
