package dev.talos.core.privacy;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateDocumentContentPolicyTest {

    @TempDir
    Path workspace;

    @Test
    void private_mode_extracted_documents_are_local_display_only_without_document_opt_ins() {
        DocumentExtractionRequest request = DocumentExtractionRequest.read(
                workspace.resolve("medical-notes.docx"),
                workspace);

        DocumentContentDecision decision = PrivateDocumentContentPolicy.decide(
                config(true, false, false, false, false),
                request,
                extractableDocx());

        assertTrue(decision.privateDocumentContent());
        assertFalse(decision.modelHandoffAllowed());
        assertFalse(decision.rawArtifactPersistenceAllowed());
        assertFalse(decision.ragIndexAllowed());
        assertEquals(
                "private mode treats extracted document text as local-display-only by default",
                decision.reason());
    }

    @Test
    void protected_workspace_documents_follow_protected_read_scope_not_document_extraction_opt_ins() {
        DocumentExtractionRequest request = DocumentExtractionRequest.read(
                workspace.resolve(".env"),
                workspace);

        DocumentContentDecision decision = PrivateDocumentContentPolicy.decide(
                config(false, true, false, true, true),
                request,
                extractableDocx());

        assertTrue(decision.privateDocumentContent());
        assertTrue(decision.modelHandoffAllowed());
        assertTrue(decision.rawArtifactPersistenceAllowed());
        assertFalse(decision.ragIndexAllowed());
        assertEquals("protected path content", decision.reason());
    }

    @Test
    void developer_mode_non_protected_documents_keep_existing_handoff_defaults() {
        DocumentExtractionRequest request = DocumentExtractionRequest.read(
                workspace.resolve("developer-notes.docx"),
                workspace);

        DocumentContentDecision decision = PrivateDocumentContentPolicy.decide(
                new Config(null),
                request,
                extractableDocx());

        assertFalse(decision.privateDocumentContent());
        assertTrue(decision.modelHandoffAllowed());
        assertFalse(decision.rawArtifactPersistenceAllowed());
        assertTrue(decision.ragIndexAllowed());
        assertEquals("developer-mode extracted document text", decision.reason());
    }

    @Test
    void local_display_requests_never_send_extracted_text_to_model() {
        DocumentExtractionRequest request = new DocumentExtractionRequest(
                workspace.resolve("developer-notes.docx"),
                workspace,
                dev.talos.core.extract.DocumentExtractionIntent.LOCAL_DISPLAY);

        DocumentContentDecision decision = PrivateDocumentContentPolicy.decide(
                new Config(null),
                request,
                extractableDocx());

        assertFalse(decision.modelHandoffAllowed());
    }

    private static Config config(
            boolean privateMode,
            boolean documentSendToModel,
            boolean documentPersistRawArtifacts,
            boolean protectedReadSendToModel,
            boolean protectedReadPersistRawArtifacts) {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", privateMode ? "private" : "developer",
                "rag", new LinkedHashMap<>(Map.of(
                        "enabled_in_private_mode",
                        Boolean.FALSE)),
                "protected_read", new LinkedHashMap<>(Map.of(
                        "default_scope",
                        "SEND_TO_MODEL_CONTEXT",
                        "allow_send_to_model",
                        protectedReadSendToModel,
                        "persist_raw_artifacts",
                        protectedReadPersistRawArtifacts)),
                "document_extraction", new LinkedHashMap<>(Map.of(
                        "allow_send_to_model",
                        documentSendToModel,
                        "persist_raw_artifacts",
                        documentPersistRawArtifacts,
                        "allow_rag_indexing",
                        Boolean.FALSE)))));
        return cfg;
    }

    private static FileCapabilityPolicy.FormatInfo extractableDocx() {
        return new FileCapabilityPolicy.FormatInfo(
                "docx",
                "Microsoft Word .docx",
                "Word document",
                FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED,
                true,
                true,
                FileCapabilityPolicy.ExtractionOutcome.NOT_ATTEMPTED);
    }
}
