package dev.talos.runtime.verification;

/** Origin of a task verification result used by outcome classification. */
public enum TaskVerificationEvidenceSource {
    POST_APPLY_STATIC,
    EMBEDDED_ASSISTANT_TEXT,
    NOT_RUN
}
