package dev.talos.runtime.policy;

import dev.talos.core.Config;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.privacy.DocumentContentDecision;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PrivateDocumentPolicyTest {

    @TempDir
    Path workspace;

    @Test
    void decide_returns_single_private_document_handoff_metadata_value() {
        Config cfg = privateDocumentConfig(true, false, false, true);
        DocumentExtractionRequest request = DocumentExtractionRequest.read(
                workspace.resolve("private-notes.docx"),
                workspace);

        DocumentContentDecision decision = PrivateDocumentPolicy.decide(
                cfg,
                request,
                extractableDocx());

        assertTrue(decision.privateDocumentContent());
        assertTrue(decision.modelHandoffAllowed());
        assertFalse(decision.rawArtifactPersistenceAllowed());
        assertFalse(decision.ragIndexAllowed());
        assertEquals(
                "private mode treats extracted document text as local-display-only by default",
                decision.reason());
    }

    @Test
    void decide_preserves_developer_mode_document_defaults() {
        DocumentExtractionRequest request = DocumentExtractionRequest.read(
                workspace.resolve("developer-notes.docx"),
                workspace);

        DocumentContentDecision decision = PrivateDocumentPolicy.decide(
                new Config(null),
                request,
                extractableDocx());

        assertFalse(decision.privateDocumentContent());
        assertTrue(decision.modelHandoffAllowed());
        assertFalse(decision.rawArtifactPersistenceAllowed());
        assertTrue(decision.ragIndexAllowed());
        assertEquals("developer-mode extracted document text", decision.reason());
    }

    private static Config privateDocumentConfig(
            boolean allowSendToModel,
            boolean persistRawArtifacts,
            boolean allowRagIndexing,
            boolean ragEnabledInPrivateMode) {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "rag", new LinkedHashMap<>(Map.of(
                        "enabled_in_private_mode",
                        ragEnabledInPrivateMode)),
                "document_extraction", new LinkedHashMap<>(Map.of(
                        "allow_send_to_model",
                        allowSendToModel,
                        "persist_raw_artifacts",
                        persistRawArtifacts,
                        "allow_rag_indexing",
                        allowRagIndexing)))));
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
