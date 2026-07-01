package dev.talos.runtime.outcome;

public enum TaskCompletionStatus {
    COMPLETED_VERIFIED,
    COMPLETED_UNVERIFIED,
    READ_ONLY_ANSWERED,
    PARTIAL,
    BLOCKED_BY_APPROVAL,
    BLOCKED_BY_POLICY,
    ADVISORY_ONLY,
    FAILED
}
