package dev.talos.runtime.verification;

/** Structured status for post-apply static task verification. */
public enum TaskVerificationStatus {
    NOT_RUN,
    READBACK_ONLY,
    PASSED,
    FAILED,
    UNAVAILABLE
}
