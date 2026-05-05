package dev.talos.runtime.policy;

/** Current-turn evidence that must exist before answering. */
public enum EvidenceObligation {
    NONE,
    LIST_DIRECTORY_ONLY,
    READ_TARGET_REQUIRED,
    PROTECTED_READ_APPROVAL_REQUIRED,
    WORKSPACE_INSPECTION_REQUIRED,
    STATIC_WEB_DIAGNOSIS_REQUIRED,
    VERIFY_FROM_TRACE_OR_EVIDENCE,
    UNSUPPORTED_CAPABILITY_CHECK_REQUIRED
}
