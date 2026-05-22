package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.privacy.DocumentContentDecision;
import dev.talos.core.privacy.PrivateDocumentContentPolicy;

/** Runtime privacy policy for extracted document content. */
public final class PrivateDocumentPolicy {
    private PrivateDocumentPolicy() {}

    public static boolean isExtractedDocument(FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.isExtractedDocument(info);
    }

    public static DocumentContentDecision decide(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.decide(cfg, request, info);
    }

    public static boolean privateDocumentContent(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.privateDocumentContent(cfg, request, info);
    }

    public static boolean modelHandoffAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.modelHandoffAllowed(cfg, request, info);
    }

    public static boolean rawArtifactPersistenceAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.rawArtifactPersistenceAllowed(cfg, request, info);
    }

    public static boolean ragIndexAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.ragIndexAllowed(cfg, request, info);
    }

    public static String decisionReason(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentContentPolicy.decisionReason(cfg, request, info);
    }

    public static String modelHandoffNote(Config cfg) {
        if (privateDocumentModelHandoffOptIn(cfg)) {
            return "Private document extraction scope: SEND_TO_MODEL_CONTEXT. Extracted document text may be sent to model context for this turn. Raw persistence remains redacted unless explicitly enabled by maintainer config.";
        }
        return "Private document extraction scope: LOCAL_DISPLAY_ONLY. Extracted document text was read locally but withheld from model context and persisted artifacts.";
    }

    public static boolean privateDocumentModelHandoffOptIn(Config cfg) {
        return PrivateDocumentContentPolicy.privateDocumentModelHandoffOptIn(cfg);
    }

    public static boolean privateDocumentRawArtifactPersistenceOptIn(Config cfg) {
        return PrivateDocumentContentPolicy.privateDocumentRawArtifactPersistenceOptIn(cfg);
    }

    public static boolean privateDocumentRagIndexingOptIn(Config cfg) {
        return PrivateDocumentContentPolicy.privateDocumentRagIndexingOptIn(cfg);
    }
}
