package dev.talos.core.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.ingest.FileCapabilityPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionServiceTest {

    @Test
    void service_uses_neutral_sanitizer_for_text_redaction_but_keeps_private_document_policy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/extract/DocumentExtractionService.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.safety.ProtectedContentSanitizer;"), source);
        assertTrue(source.contains("import dev.talos.runtime.policy.PrivateDocumentPolicy;"), source);
        assertFalse(source.contains("import dev.talos.runtime.policy.ProtectedContentPolicy;"), source);
        assertFalse(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
        assertTrue(baseline.contains(
                        "core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionService.java|dev.talos.runtime.policy.PrivateDocumentPolicy"),
                baseline);
    }

    @Test
    void text_file_extraction_returns_sanitized_safe_text(@TempDir Path workspace) throws Exception {
        Path notes = workspace.resolve("notes.txt");
        Files.writeString(notes, "hello\nAPI_TOKEN=t267-token-should-not-appear\n");
        DocumentExtractionService service = new DocumentExtractionService(new Config(null));

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(notes, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertTrue(result.safeText().contains("hello"));
        assertTrue(result.safeText().contains("API_TOKEN=[redacted]"));
        assertFalse(result.safeText().contains("t267-token-should-not-appear"));
        assertEquals(DocumentExtractionIntent.READ, result.intent());
    }

    @Test
    void disabled_pdf_returns_structured_disabled_status_without_raw_text(@TempDir Path workspace) throws Exception {
        Path pdf = workspace.resolve("report.pdf");
        Files.write(pdf, new byte[] { '%', 'P', 'D', 'F' });
        DocumentExtractionService service = new DocumentExtractionService(extractionDisabled());

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(pdf, workspace));

        assertEquals(DocumentExtractionStatus.UNSUPPORTED_DISABLED, result.status());
        assertEquals(FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_DISABLED, result.capability());
        assertTrue(result.safeText().isBlank());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("not enabled")));
    }

    @Test
    void result_serialization_omits_raw_parser_text(@TempDir Path workspace) throws Exception {
        Path notes = workspace.resolve("notes.txt");
        Files.writeString(notes, "PRIVATE_MARKER = DO_NOT_LEAK_SERIALIZATION\n");
        DocumentExtractionService service = new DocumentExtractionService(new Config(null));

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(notes, workspace));
        String json = new ObjectMapper().writeValueAsString(result);

        assertFalse(json.contains("DO_NOT_LEAK_SERIALIZATION"), json);
        assertFalse(json.toLowerCase().contains("raw"), json);
        assertTrue(json.contains("[redacted]"), json);
    }

    @Test
    void image_without_ocr_reports_ocr_unavailable(@TempDir Path workspace) throws Exception {
        Path image = workspace.resolve("scan.png");
        Files.write(image, new byte[] { (byte) 0x89, 'P', 'N', 'G' });
        DocumentExtractionService service = new DocumentExtractionService(new Config(null));

        DocumentExtractionResult result = service.extract(DocumentExtractionRequest.read(image, workspace));

        assertEquals(DocumentExtractionStatus.OCR_UNAVAILABLE, result.status());
        assertEquals(FileCapabilityPolicy.Capability.OCR_REQUIRED_DISABLED, result.capability());
        assertTrue(result.warnings().stream().anyMatch(w -> w.message().contains("OCR")));
    }

    @Test
    void private_mode_document_extraction_is_not_model_handoff_by_default(@TempDir Path workspace) throws Exception {
        Path docx = workspace.resolve("medical-notes.docx");
        try (XWPFDocument doc = new XWPFDocument()) {
            doc.createParagraph().createRun().setText("Patient Name: Eleni Nikolaou");
            try (OutputStream out = Files.newOutputStream(docx)) {
                doc.write(out);
            }
        }

        DocumentExtractionResult result = new DocumentExtractionService(privateModeConfig())
                .extract(DocumentExtractionRequest.read(docx, workspace));

        assertEquals(DocumentExtractionStatus.SUCCESS, result.status());
        assertFalse(result.safeText().contains("Eleni Nikolaou"), result.safeText());
        assertTrue(result.safeText().contains("[redacted-private-document-canary]"), result.safeText());
        assertFalse(result.modelHandoffAllowed(),
                "ordinary extracted document text must default to local-display-only in private mode");
    }

    private static Config extractionDisabled() {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.FALSE);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    private static Config privateModeConfig() {
        Config cfg = new Config(null);
        cfg.data.put("privacy", new LinkedHashMap<>(Map.of("mode", "private")));
        return cfg;
    }
}
