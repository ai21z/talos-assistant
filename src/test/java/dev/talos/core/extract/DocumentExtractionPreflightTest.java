package dev.talos.core.extract;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DocumentExtractionPreflightTest {

    @Test
    void image_ocr_preflight_reports_disabled_when_default_config_has_no_command() {
        Config cfg = new Config(null);

        DocumentExtractionPreflight.FamilyStatus status = DocumentExtractionPreflight.imageOcr(cfg);

        assertFalse(status.usable(), status.toString());
        assertTrue(status.summary().contains("disabled"), status.summary());
        assertTrue(status.detail().contains("not configured"), status.detail());
    }

    @Test
    void image_ocr_preflight_reports_missing_when_enabled_command_cannot_be_resolved() {
        Config cfg = imageOcrConfig("definitely-missing-talos-ocr-command-xyz");

        DocumentExtractionPreflight.FamilyStatus status = DocumentExtractionPreflight.imageOcr(cfg);

        assertFalse(status.usable(), status.toString());
        assertTrue(status.summary().contains("unavailable"), status.summary());
        assertTrue(status.detail().contains("not found"), status.detail());
    }

    @Test
    void image_ocr_preflight_reports_available_when_enabled_command_resolves_to_file() {
        Config cfg = imageOcrConfig(javaExecutable());

        DocumentExtractionPreflight.FamilyStatus status = DocumentExtractionPreflight.imageOcr(cfg);

        assertTrue(status.usable(), status.toString());
        assertTrue(status.summary().contains("available"), status.summary());
        assertTrue(status.detail().contains(javaExecutable()), status.detail());
    }

    @Test
    void render_lists_pdf_word_excel_and_image_ocr_statuses() {
        String rendered = DocumentExtractionPreflight.render(new Config(null));

        assertTrue(rendered.contains("PDF"), rendered);
        assertTrue(rendered.contains("Word"), rendered);
        assertTrue(rendered.contains("Excel"), rendered);
        assertTrue(rendered.contains("Image OCR"), rendered);
    }

    @Test
    void preflight_uses_neutral_sanitizer_instead_of_runtime_policy() throws Exception {
        String source = Files.readString(Path.of("src/main/java/dev/talos/core/extract/DocumentExtractionPreflight.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertTrue(source.contains("import dev.talos.safety.ProtectedContentSanitizer;"), source);
        assertFalse(source.contains("dev.talos.runtime.policy.ProtectedContentPolicy"), source);
        assertFalse(baseline.contains(
                "core-no-runtime|src/main/java/dev/talos/core/extract/DocumentExtractionPreflight.java|dev.talos.runtime.policy.ProtectedContentPolicy"),
                baseline);
    }

    private static Config imageOcrConfig(String command) {
        Config cfg = new Config(null);
        Map<String, Object> documentExtraction = new LinkedHashMap<>();
        documentExtraction.put("enabled", Boolean.TRUE);
        documentExtraction.put("pdf", new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));
        documentExtraction.put("word", new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));
        documentExtraction.put("excel", new LinkedHashMap<>(Map.of("enabled", Boolean.TRUE)));
        documentExtraction.put("image_ocr", new LinkedHashMap<>(Map.of(
                "enabled", Boolean.TRUE,
                "command", command,
                "args", List.of(),
                "timeout_ms", 10_000L)));
        cfg.data.put("document_extraction", documentExtraction);
        return cfg;
    }

    private static String javaExecutable() {
        String exe = System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", exe).toString();
    }
}
