package dev.talos.core.privacy;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionIntent;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.safety.ProtectedWorkspacePaths;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/** Core ownership for private extracted-document content handoff decisions. */
public final class PrivateDocumentContentPolicy {
    private PrivateDocumentContentPolicy() {}

    private enum ProtectedReadScope {
        LOCAL_DISPLAY_ONLY,
        SEND_TO_MODEL_CONTEXT
    }

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
        if (request != null && protectedPath(request.workspaceRoot(), request.path())) {
            return true;
        }
        return isExtractedDocument(info) && PrivacyConfigFacts.privateMode(cfg);
    }

    public static boolean modelHandoffAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request == null || request.intent() == DocumentExtractionIntent.LOCAL_DISPLAY) {
            return false;
        }
        if (protectedPath(request.workspaceRoot(), request.path())) {
            return sendApprovedProtectedReadToModel(cfg);
        }
        if (isExtractedDocument(info) && PrivacyConfigFacts.privateMode(cfg)) {
            return privateDocumentModelHandoffOptIn(cfg);
        }
        return true;
    }

    public static boolean rawArtifactPersistenceAllowed(
            Config cfg,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo info) {
        if (request == null) return false;
        if (protectedPath(request.workspaceRoot(), request.path())) {
            return protectedReadRawArtifactPersistenceOptIn(cfg);
        }
        if (isExtractedDocument(info) && PrivacyConfigFacts.privateMode(cfg)) {
            return privateDocumentRawArtifactPersistenceOptIn(cfg);
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

    public static boolean privateDocumentModelHandoffOptIn(Config cfg) {
        return CfgUtil.boolAt(documentPrivacy(cfg), "allow_send_to_model", false);
    }

    public static boolean privateDocumentRawArtifactPersistenceOptIn(Config cfg) {
        return CfgUtil.boolAt(documentPrivacy(cfg), "persist_raw_artifacts", false);
    }

    public static boolean privateDocumentRagIndexingOptIn(Config cfg) {
        return CfgUtil.boolAt(documentPrivacy(cfg), "allow_rag_indexing", false);
    }

    private static boolean protectedPath(Path workspaceRoot, Path path) {
        return ProtectedWorkspacePaths.isProtectedPath(workspaceRoot, path);
    }

    private static boolean sendApprovedProtectedReadToModel(Config cfg) {
        if (protectedReadDefaultScope(cfg) != ProtectedReadScope.SEND_TO_MODEL_CONTEXT) {
            return false;
        }
        if (!PrivacyConfigFacts.privateMode(cfg)) {
            return true;
        }
        return CfgUtil.boolAt(protectedReadPrivacy(cfg), "allow_send_to_model", false);
    }

    private static boolean protectedReadRawArtifactPersistenceOptIn(Config cfg) {
        return CfgUtil.boolAt(protectedReadPrivacy(cfg), "persist_raw_artifacts", false);
    }

    private static ProtectedReadScope protectedReadDefaultScope(Config cfg) {
        Object configured = protectedReadPrivacy(cfg).get("default_scope");
        if (configured != null) {
            String value = String.valueOf(configured).strip().toUpperCase(Locale.ROOT);
            if ("SEND_TO_MODEL_CONTEXT".equals(value)) return ProtectedReadScope.SEND_TO_MODEL_CONTEXT;
            if ("LOCAL_DISPLAY_ONLY".equals(value)) return ProtectedReadScope.LOCAL_DISPLAY_ONLY;
        }
        return PrivacyConfigFacts.privateMode(cfg)
                ? ProtectedReadScope.LOCAL_DISPLAY_ONLY
                : ProtectedReadScope.SEND_TO_MODEL_CONTEXT;
    }

    private static Map<String, Object> protectedReadPrivacy(Config cfg) {
        return CfgUtil.map(privacy(cfg).get("protected_read"));
    }

    private static Map<String, Object> documentPrivacy(Config cfg) {
        return CfgUtil.map(privacy(cfg).get("document_extraction"));
    }

    private static Map<String, Object> privacy(Config cfg) {
        if (cfg == null) return Map.of();
        return CfgUtil.map(cfg.data.get("privacy"));
    }
}
