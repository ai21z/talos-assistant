package dev.talos.core.ingest;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FileCapabilityPolicyV3Test {

    @Test
    void pdf_disabled_reports_extractable_but_disabled() {
        Config cfg = extractionDisabled();

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("report.pdf"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_DISABLED, info.capability());
        assertTrue(info.extractable());
        assertFalse(info.enabled());
        assertEquals(FileCapabilityPolicy.ExtractionOutcome.UNSUPPORTED_DISABLED, info.defaultOutcome());
    }

    @Test
    void pdf_enabled_allows_extraction_policy_without_plain_text_fallback() {
        Config cfg = new Config(null);
        enable(cfg, "pdf");

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("report.pdf"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.EXTRACTABLE_TEXT_ENABLED, info.capability());
        assertTrue(info.extractable());
        assertTrue(info.enabled());
        assertEquals(FileCapabilityPolicy.ExtractionOutcome.NOT_ATTEMPTED, info.defaultOutcome());
        assertTrue(UnsupportedDocumentFormats.isUnsupported(Path.of("report.pdf")),
                "legacy callers must keep refusing PDFs until they route through the extraction service");
    }

    @Test
    void image_without_ocr_reports_ocr_required_disabled() {
        Config cfg = new Config(null);

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("scan.png"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.OCR_REQUIRED_DISABLED, info.capability());
        assertEquals(FileCapabilityPolicy.ExtractionOutcome.OCR_UNAVAILABLE, info.defaultOutcome());
    }

    @Test
    void image_with_ocr_enabled_reports_ocr_enabled() {
        Config cfg = new Config(null);
        enable(cfg, "image_ocr");

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("scan.png"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.OCR_ENABLED, info.capability());
        assertTrue(info.enabled());
    }

    @Test
    void legacy_doc_remains_deferred_even_when_word_docx_is_enabled() {
        Config cfg = new Config(null);

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("legacy.doc"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.DEFERRED_UNSUPPORTED, info.capability());
        assertFalse(info.extractable());
        assertFalse(info.enabled());
        assertEquals(FileCapabilityPolicy.ExtractionOutcome.DEFERRED_UNSUPPORTED, info.defaultOutcome());
    }

    @Test
    void pptx_remains_deferred_unsupported_for_beta() {
        Config cfg = new Config(null);

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("deck.pptx"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.DEFERRED_UNSUPPORTED, info.capability());
        assertFalse(info.extractable());
        assertEquals(FileCapabilityPolicy.ExtractionOutcome.DEFERRED_UNSUPPORTED, info.defaultOutcome());
    }

    @Test
    void archive_remains_unsupported_and_not_recursed() {
        Config cfg = new Config(null);

        FileCapabilityPolicy.FormatInfo info = FileCapabilityPolicy.describe(Path.of("archive.zip"), cfg).orElseThrow();

        assertEquals(FileCapabilityPolicy.Capability.ARCHIVE_UNSUPPORTED, info.capability());
        assertFalse(info.extractable());
        assertEquals(FileCapabilityPolicy.ExtractionOutcome.UNSUPPORTED_ARCHIVE, info.defaultOutcome());
    }

    private static void enable(Config cfg, String family) {
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        Map<String, Object> familyCfg = new LinkedHashMap<>();
        familyCfg.put("enabled", Boolean.TRUE);
        documentExtraction.put(family, familyCfg);
        cfg.data.put("document_extraction", documentExtraction);
    }

    private static Config extractionDisabled() {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.FALSE);
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }
}
