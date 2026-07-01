package dev.talos.tools;

/**
 * Structured verification status for file write/edit tool outcomes.
 *
 * <p>Represents the semantic result of post-write content verification,
 * enabling the runtime and model to distinguish between:
 * <ul>
 *   <li>{@link #PASS} - mutation succeeded, verification passed</li>
 *   <li>{@link #WARN} - mutation succeeded, verification found non-fatal issues</li>
 *   <li>{@link #FAIL} - mutation succeeded at filesystem level, but content is invalid</li>
 *   <li>{@link #INTEGRITY_FAIL} - mutation read-back failed or did not match approved bytes</li>
 *   <li>{@link #UNKNOWN} - mutation succeeded, no semantic validator available</li>
 * </ul>
 *
 * <p>Attached to {@link ToolResult} as optional metadata. Null for non-write tools.
 */
public enum VerificationStatus {

    /** File mutation succeeded and verification passed cleanly. */
    PASS,

    /** File mutation succeeded but verification found non-fatal issues (e.g., unclosed HTML tags). */
    WARN,

    /** File mutation succeeded at filesystem level but content is semantically invalid (e.g., broken JSON). */
    FAIL,

    /** File mutation could not prove approved bytes landed by read-back. */
    INTEGRITY_FAIL,

    /** File mutation succeeded; no semantic validator exists for this file type (read-back only). */
    UNKNOWN;

    /** Human-readable label for CLI display. */
    public String label() {
        return switch (this) {
            case PASS    -> "verified";
            case WARN    -> "warning";
            case FAIL    -> "verification failed";
            case INTEGRITY_FAIL -> "read-back integrity failed";
            case UNKNOWN -> "unverified";
        };
    }

    /** Returns true if the status indicates the content is acceptable (PASS or UNKNOWN). */
    public boolean acceptable() {
        return this == PASS || this == UNKNOWN;
    }
}

