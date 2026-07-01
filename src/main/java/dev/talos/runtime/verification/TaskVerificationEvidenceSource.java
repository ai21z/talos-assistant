package dev.talos.runtime.verification;

/** Origin of a task verification result used by outcome classification. */
public enum TaskVerificationEvidenceSource {
    POST_APPLY_STATIC,
    DOCUMENT_EXTRACTION_TOOL_RESULT,
    EMBEDDED_ASSISTANT_TEXT,
    NOT_RUN
}
