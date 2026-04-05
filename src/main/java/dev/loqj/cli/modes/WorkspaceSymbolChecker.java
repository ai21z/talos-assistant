package dev.loqj.cli.modes;

/**
 * Checks whether a symbol (typically a PascalCase identifier) exists in the
 * indexed workspace. Used by {@link PromptRouter} to resolve bare code
 * identifiers without requiring question context.
 *
 * <p>This is a narrow injection seam — the router depends only on this
 * interface, never on Lucene or the index implementation directly.
 * Implementations must be safe for concurrent access.
 *
 * <p><b>Contract:</b> implementations should return {@code false} gracefully
 * when the index does not exist, is empty, or cannot be read. A false return
 * merely means the symbol is not confirmed — it does not mean the input is
 * invalid.
 *
 * @see PromptRouter
 */
@FunctionalInterface
public interface WorkspaceSymbolChecker {

    /**
     * Returns {@code true} if the given symbol name corresponds to a file
     * or type known to exist in the indexed workspace.
     *
     * <p>For example, if the workspace contains {@code RagService.java},
     * then {@code existsInWorkspace("RagService")} should return {@code true}.
     *
     * @param symbol the PascalCase identifier to look up (e.g. "RagService")
     * @return true if found in the workspace index, false otherwise
     */
    boolean existsInWorkspace(String symbol);

    /**
     * Invalidates any cached lookup results.
     *
     * <p>Called after {@code :reindex} to ensure subsequent lookups reflect
     * the updated index. Implementations that do not cache may leave this
     * as a no-op.
     */
    default void invalidateCache() { /* no-op by default */ }
}
