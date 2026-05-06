package dev.talos.runtime.policy;

/** Current-turn action obligation derived from task contract and phase. */
public enum ActionObligation {
    DIRECT_ANSWER_ONLY,
    LIST_DIR_ONLY,
    INSPECT_REQUIRED,
    CONDITIONAL_REVIEW_FIX,
    MUTATING_TOOL_REQUIRED,
    VERIFY_FROM_EVIDENCE,
    REPAIR_FROM_VERIFIER_FINDINGS,
    NONE,
    UNKNOWN
}
