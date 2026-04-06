package dev.talos.tools;

/**
 * Risk classification for tool operations.
 *
 * <p>Used by the {@link dev.talos.runtime.ApprovalGate} to decide whether
 * user confirmation is required before executing a tool.
 *
 * <ul>
 *   <li>{@link #READ_ONLY} — no side effects; always auto-approved</li>
 *   <li>{@link #WRITE} — modifies files or state; requires approval</li>
 *   <li>{@link #DESTRUCTIVE} — deletes data or has irreversible effects; requires approval</li>
 * </ul>
 */
public enum ToolRiskLevel {

    /** No side effects. Safe to execute without user confirmation. */
    READ_ONLY,

    /** Modifies workspace files or persistent state. Requires user approval. */
    WRITE,

    /** Deletes data or has potentially irreversible effects. Requires user approval. */
    DESTRUCTIVE;

    /** Returns true if this risk level requires user approval before execution. */
    public boolean requiresApproval() {
        return this != READ_ONLY;
    }
}

