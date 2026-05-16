package dev.talos.core.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.ingest.FileCapabilityPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionServiceTest {

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

    private static Config extractionDisabled() {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.FALSE);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }
}
