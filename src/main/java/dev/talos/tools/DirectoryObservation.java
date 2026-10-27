package dev.talos.tools;

/** Observation-time directory facts, produced by the directory tool rather than answer prose. */
public record DirectoryObservation(
        String resolvedTarget,
        int maxDepth,
        int returnedEntries,
        boolean complete,
        boolean includesHiddenEntries
) {
    public DirectoryObservation {
        if (resolvedTarget == null || resolvedTarget.isBlank() || maxDepth < 1 || returnedEntries < 0) {
            throw new IllegalArgumentException("Invalid directory observation");
        }
    }
}
