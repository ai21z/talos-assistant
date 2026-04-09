package dev.talos.cli.modes;

/**
 * Checks whether a PascalCase identifier exists in the indexed workspace.
 * Used by {@link PromptRouter} to resolve bare code identifiers.
 * Implementations must be thread-safe and return {@code false} gracefully on errors.
 */
@FunctionalInterface
public interface WorkspaceSymbolChecker {

    /**
     * Returns {@code true} if the symbol matches a file or type in the workspace index.
     */
    boolean existsInWorkspace(String symbol);

    /** Invalidates cached lookups (e.g. after {@code :reindex}). No-op by default. */
    default void invalidateCache() { /* no-op by default */ }
}
