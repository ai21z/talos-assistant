package dev.talos.core.privacy;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.safety.ProtectedWorkspacePaths;

import java.util.Map;

/** RAG indexing policy for extracted document text. */
public final class PrivateDocumentIndexingPolicy {
    private PrivateDocumentIndexingPolicy() {}

    public static boolean mayIndexExtractedDocument(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request == null) return false;
        if (ProtectedWorkspacePaths.isProtectedPath(request.workspaceRoot(), request.path())) {
            return false;
        }
        if (isExtractedDocument(info) && PrivacyConfigFacts.privateMode(cfg)) {
            return PrivacyConfigFacts.ragEnabledInPrivateMode(cfg)
                    && allowPrivateDocumentRagIndexing(cfg);
        }
        return true;
    }

    public static String decisionReason(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request != null && ProtectedWorkspacePaths.isProtectedPath(request.workspaceRoot(), request.path())) {
            return "protected path content";
        }
        if (isExtractedDocument(info) && PrivacyConfigFacts.privateMode(cfg)) {
            return "private mode treats extracted document text as local-display-only by default";
        }
        if (isExtractedDocument(info)) {
            return "developer-mode extracted document text";
        }
        return "normal workspace content";
    }

    private static boolean isExtractedDocument(FileCapabilityPolicy.FormatInfo info) {
        if (info == null) return false;
        return info.capability() == FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED
                || info.capability() == FileCapabilityPolicy.Capability.OCR_ENABLED;
    }

    private static boolean allowPrivateDocumentRagIndexing(Config cfg) {
        Map<String, Object> documentPrivacy = documentPrivacy(cfg);
        return CfgUtil.boolAt(documentPrivacy, "allow_rag_indexing", false);
    }

    private static Map<String, Object> documentPrivacy(Config cfg) {
        Map<String, Object> privacy = cfg == null ? Map.of() : CfgUtil.map(cfg.data.get("privacy"));
        return CfgUtil.map(privacy.get("document_extraction"));
    }
}
