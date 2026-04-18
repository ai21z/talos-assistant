package dev.talos.runtime;

/**
 * Gate for sensitive operations that require user approval before proceeding.
 *
 * <p>This is a first-class architectural concept in Talos (see AD-08).
 * V1 uses {@link NoOpApprovalGate} which always approves. Future implementations
 * will prompt the user via CLI or enforce policy rules.
 *
 * <p>Examples of operations that should eventually require approval:
 * sending email, uploading files, submitting forms, deleting content,
 * confirming a purchase or booking.
 */
public interface ApprovalGate {

    /**
     * Request approval for a sensitive operation.
     *
     * @param description short human-readable description of the operation
     * @param detail      optional longer detail (may be null)
     * @return true if approved, false if denied/cancelled
     */
    boolean approve(String description, String detail);

    /**
     * Tri-state approval — lets a gate distinguish "yes, once" from
     * "yes, and remember for the session" from "no".
     *
     * <p>Default implementation delegates to {@link #approve(String, String)}
     * and maps the boolean to {@link ApprovalResponse#APPROVED} /
     * {@link ApprovalResponse#DENIED} — so existing gates keep working.
     * Gates that want to surface a "remember" option (see
     * {@link CliApprovalGate}) should override this method.
     *
     * @param description short human-readable description of the operation
     * @param detail      optional longer detail (may be null)
     * @return the approval response
     */
    default ApprovalResponse approveFull(String description, String detail) {
        return approve(description, detail) ? ApprovalResponse.APPROVED : ApprovalResponse.DENIED;
    }
}
