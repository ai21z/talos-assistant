package dev.talos.core.privacy;

import java.util.Objects;

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

    public enum Use { LOCAL_DISPLAY, MODEL_CONTEXT, RAW_ARTIFACT, RAG_INDEX }

    /** Both source policies are retained without promoting a handoff into persistence permission. */
    public static DocumentContentDecision forDerivedPair(
            DocumentContentDecision left, DocumentContentDecision right) {
        Objects.requireNonNull(left, "left content decision");
        Objects.requireNonNull(right, "right content decision");
        return new DocumentContentDecision(
                left.privateDocumentContent || right.privateDocumentContent,
                left.modelHandoffAllowed && right.modelHandoffAllowed,
                left.rawArtifactPersistenceAllowed && right.rawArtifactPersistenceAllowed,
                left.ragIndexAllowed && right.ragIndexAllowed,
                "both source content policies apply to derived content");
    }

    public boolean allows(Use use) {
        return switch (Objects.requireNonNull(use, "content use")) {
            case LOCAL_DISPLAY -> true;
            case MODEL_CONTEXT -> modelHandoffAllowed;
            case RAW_ARTIFACT -> rawArtifactPersistenceAllowed;
            case RAG_INDEX -> ragIndexAllowed;
        };
    }
}
