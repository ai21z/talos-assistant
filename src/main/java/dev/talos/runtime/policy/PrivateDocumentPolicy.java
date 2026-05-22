package dev.talos.runtime.policy;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionIntent;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.privacy.DocumentContentDecision;
import dev.talos.core.privacy.PrivateDocumentIndexingPolicy;

import java.nio.file.Path;
import java.util.Map;

/** Runtime privacy policy for extracted document content. */
public final class PrivateDocumentPolicy {
    private PrivateDocumentPolicy() {}

    public static boolean isExtractedDocument(FileCapabilityPolicy.FormatInfo info) {
        if (info == null) return false;
        return info.capability() == FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED
                || info.capability() == FileCapabilityPolicy.Capability.OCR_ENABLED;
    }

    public static DocumentContentDecision decide(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return new DocumentContentDecision(
                privateDocumentContent(cfg, request, info),
                modelHandoffAllowed(cfg, request, info),
                rawArtifactPersistenceAllowed(cfg, request, info),
                ragIndexAllowed(cfg, request, info),
                decisionReason(cfg, request, info));
    }

    public static boolean privateDocumentContent(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request != null && ProtectedContentPolicy.isProtectedPath(request.workspaceRoot(), request.path())) {
            return true;
        }
        return isExtractedDocument(info) && ProtectedReadScopePolicy.privateMode(cfg);
    }

    public static boolean modelHandoffAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request == null || request.intent() == DocumentExtractionIntent.LOCAL_DISPLAY) {
            return false;
        }
        Path workspaceRoot = request.workspaceRoot();
        Path path = request.path();
        if (ProtectedContentPolicy.isProtectedPath(workspaceRoot, path)) {
            return ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg);
        }
        if (isExtractedDocument(info) && ProtectedReadScopePolicy.privateMode(cfg)) {
            return allowPrivateDocumentModelHandoff(cfg);
        }
        return true;
    }

    public static boolean rawArtifactPersistenceAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request == null) return false;
        if (ProtectedContentPolicy.isProtectedPath(request.workspaceRoot(), request.path())) {
            return ProtectedReadScopePolicy.persistRawArtifacts(cfg);
        }
        if (isExtractedDocument(info) && ProtectedReadScopePolicy.privateMode(cfg)) {
            return allowPrivateDocumentRawArtifacts(cfg);
        }
        return false;
    }

    public static boolean ragIndexAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(cfg, request, info);
    }

    public static String decisionReason(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        return PrivateDocumentIndexingPolicy.decisionReason(cfg, request, info);
    }

    public static String modelHandoffNote(Config cfg) {
        if (allowPrivateDocumentModelHandoff(cfg)) {
            return "Private document extraction scope: SEND_TO_MODEL_CONTEXT. Extracted document text may be sent to model context for this turn. Raw persistence remains redacted unless explicitly enabled by maintainer config.";
        }
        return "Private document extraction scope: LOCAL_DISPLAY_ONLY. Extracted document text was read locally but withheld from model context and persisted artifacts.";
    }

    public static boolean privateDocumentModelHandoffOptIn(Config cfg) {
        return allowPrivateDocumentModelHandoff(cfg);
    }

    public static boolean privateDocumentRawArtifactPersistenceOptIn(Config cfg) {
        return allowPrivateDocumentRawArtifacts(cfg);
    }

    public static boolean privateDocumentRagIndexingOptIn(Config cfg) {
        return allowPrivateDocumentRagIndexing(cfg);
    }

    private static boolean allowPrivateDocumentModelHandoff(Config cfg) {
        Map<String, Object> documentPrivacy = documentPrivacy(cfg);
        return CfgUtil.boolAt(documentPrivacy, "allow_send_to_model", false);
    }

    private static boolean allowPrivateDocumentRawArtifacts(Config cfg) {
        Map<String, Object> documentPrivacy = documentPrivacy(cfg);
        return CfgUtil.boolAt(documentPrivacy, "persist_raw_artifacts", false);
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
