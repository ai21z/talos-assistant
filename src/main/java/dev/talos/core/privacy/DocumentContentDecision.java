package dev.talos.core.privacy;

/** Privacy decision for extracted document content before tool/runtime adaptation. */
public record DocumentContentDecision(
        boolean privateDocumentContent,
        boolean modelHandoffAllowed,
        boolean rawArtifactPersistenceAllowed,
        boolean ragIndexAllowed,
        String reason) {
    public DocumentContentDecision {
        reason = reason == null ? "" : reason;
    }
}
