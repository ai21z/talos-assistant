package dev.talos.runtime;

/**
 * Tri-state outcome of an approval prompt.
 *
 * <p>Wraps the binary {@link ApprovalGate#approve} contract so that a gate
 * can distinguish "yes, once" from "yes, and remember for the session" from
 * "no". The remember decision is surfaced to {@link ApprovalPolicy} so that
 * subsequent similar in-workspace edits can be auto-approved for the rest
 * of the session.
 *
 * <p>Destructive operations must never auto-approve regardless of prior
 * remembered approvals - the policy enforces that, not the enum.
 */
public enum ApprovalResponse {

    /** One-time approval - do not remember. */
    APPROVED,

    /** Approved AND remember: auto-approve similar in-workspace edits for the session. */
    APPROVED_REMEMBER,

    /** Denied / cancelled / EOF. */
    DENIED;

    /** @return true for both {@link #APPROVED} and {@link #APPROVED_REMEMBER}. */
    public boolean isApproved() {
        return this == APPROVED || this == APPROVED_REMEMBER;
    }
}

