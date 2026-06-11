package dev.talos.tools;

/**
 * Typed, closed-vocabulary reason codes for tool failures (T758).
 *
 * <p>Carried from the failure producer through {@link ToolError} and the
 * runtime's ToolOutcome so classifiers (repair planners, outcome renderers,
 * truthfulness guards) switch on the reason instead of sniffing
 * human-readable message prose — the prose becomes free to change without
 * silently disabling repair or outcome policy.
 *
 * <p>Only failure families that a classifier consumes get a constant; a
 * producer without a downstream classifier keeps {@link #NONE}. An
 * unmatched reason classifies exactly like an unmatched message did before:
 * generic failure (fail-closed default).
 */
public enum ToolFailureReason {
    /** Default: no typed reason attached. */
    NONE,

    /** The user declined the approval prompt. */
    USER_APPROVAL_DENIED,
    /** The declarative permission policy denied the call (not the user). */
    PERMISSION_POLICY_DENIED,

    /** Pre-approval: a path parameter could not be resolved. */
    PRE_APPROVAL_PATH_INVALID,
    /** Pre-approval: a path parameter failed the sandbox allow check. */
    PRE_APPROVAL_PATH_NOT_ALLOWED,
    /** Pre-approval: target outside the current expected target set. */
    PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
    /** Pre-approval: target explicitly excluded by the user's request. */
    PRE_APPROVAL_TARGET_FORBIDDEN,

    /** Edit: old_string not found in the target file. */
    EDIT_OLD_STRING_NOT_FOUND,
    /** Edit: old_string matched more than once. */
    EDIT_OLD_STRING_AMBIGUOUS,
    /** Edit: old_string/new_string missing or empty. */
    EDIT_EMPTY_ARGUMENTS,
    /** Edit redirected: repair requires a complete write_file replacement. */
    EDIT_FULL_REWRITE_REQUIRED,
    /** Edit blocked: target changed since last read; re-read required. */
    EDIT_STALE_REREAD_REQUIRED,
    /** Edit blocked: identical to an already-failed edit this turn. */
    EDIT_DUPLICATE_FAILED,

    /** Write blocked: append-line write_file would not preserve content. */
    WRITE_APPEND_LINE_PRESERVATION,

    /** Command: profile run exceeded its timeout. */
    COMMAND_TIMEOUT
}
