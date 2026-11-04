package dev.talos.runtime.expectation;

/** Verification result for a deterministic task expectation. */
public enum ExpectationVerificationStatus {
    PASSED,
    FAILED,
    /** The evidence could neither prove nor refute the expectation, so it was not graded (P17). */
    NOT_ASSERTED
}
