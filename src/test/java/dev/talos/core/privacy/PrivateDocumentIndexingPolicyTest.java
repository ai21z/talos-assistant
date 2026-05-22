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

class PrivateDocumentIndexingPolicyTest {

    @TempDir
    Path workspace;

    @Test
    void private_mode_blocks_extracted_document_indexing_unless_rag_and_document_opt_in_are_enabled() {
        DocumentExtractionRequest request = DocumentExtractionRequest.index(
                workspace.resolve("medical-notes.docx"),
                workspace);

        assertFalse(PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(
                privateRagConfig(true, false),
                request,
                extractableDocx()));
        assertFalse(PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(
                privateRagConfig(false, true),
                request,
                extractableDocx()));
        assertTrue(PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(
                privateRagConfig(true, true),
                request,
                extractableDocx()));
        assertEquals(
                "private mode treats extracted document text as local-display-only by default",
                PrivateDocumentIndexingPolicy.decisionReason(
                        privateRagConfig(true, false),
                        request,
                        extractableDocx()));
    }

    @Test
    void developer_mode_allows_extracted_document_indexing_by_default() {
        DocumentExtractionRequest request = DocumentExtractionRequest.index(
                workspace.resolve("developer-notes.docx"),
                workspace);

        assertTrue(PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(
                new Config(null),
                request,
                extractableDocx()));
        assertEquals(
                "developer-mode extracted document text",
                PrivateDocumentIndexingPolicy.decisionReason(new Config(null), request, extractableDocx()));
    }

    @Test
    void protected_workspace_paths_are_never_indexable() {
        DocumentExtractionRequest request = DocumentExtractionRequest.index(
                workspace.resolve(".env"),
                workspace);

        assertFalse(PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(
                new Config(null),
                request,
                extractableDocx()));
        assertEquals(
                "protected path content",
                PrivateDocumentIndexingPolicy.decisionReason(new Config(null), request, extractableDocx()));
    }

    @Test
    void null_request_is_not_indexable() {
        assertFalse(PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(
                new Config(null),
                null,
                extractableDocx()));
    }

    private static Config privateRagConfig(boolean ragEnabledInPrivateMode, boolean allowPrivateDocumentRagIndexing) {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of(
                "mode", "private",
                "rag", new LinkedHashMap<>(Map.of(
                        "enabled_in_private_mode",
                        ragEnabledInPrivateMode)),
                "document_extraction", new LinkedHashMap<>(Map.of(
                        "allow_send_to_model",
                        false,
                        "persist_raw_artifacts",
                        false,
                        "allow_rag_indexing",
                        allowPrivateDocumentRagIndexing)))));
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
