package dev.talos.core;

import dev.talos.runtime.policy.ProtectedReadScopePolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigPrivacyDefaultsTest {

    @Test
    void config_ensure_defaults_excludes_env_and_secrets() {
        Config cfg = new Config(null);

        List<String> excludes = excludes(cfg);

        assertTrue(excludes.contains("**/.env"));
        assertTrue(excludes.contains("**/.env.*"));
        assertTrue(excludes.contains("**/*.env"));
        assertTrue(excludes.contains("**/secrets/**"));
        assertTrue(excludes.contains("**/protected/**"));
        assertTrue(excludes.contains("**/.ssh/**"));
        assertTrue(excludes.contains("**/.aws/**"));
        assertTrue(excludes.contains("**/.azure/**"));
        assertTrue(excludes.contains("**/.gnupg/**"));
        assertTrue(excludes.contains("**/.config/gcloud/**"));
    }

    @Test
    void config_ensure_defaults_excludes_non_extractable_unsupported_formats() {
        Config cfg = new Config(null);

        List<String> excludes = excludes(cfg);

        for (String pattern : List.of(
                "**/*.doc", "**/*.ppt", "**/*.pptx",
                "**/*.zip", "**/*.tar", "**/*.gz", "**/*.tgz", "**/*.7z", "**/*.rar",
                "**/*.exe", "**/*.dll", "**/*.so", "**/*.dylib", "**/*.class",
                "**/*.jar", "**/*.war", "**/*.ear", "**/*.bin", "**/*.dat")) {
            assertTrue(excludes.contains(pattern), pattern + " missing from " + excludes);
        }
    }

    @Test
    void config_ensure_defaults_include_extractable_document_formats() {
        Config cfg = new Config(null);

        List<String> includes = includes(cfg);
        List<String> excludes = excludes(cfg);

        for (String pattern : List.of(
                "**/*.pdf", "**/*.docx", "**/*.xls", "**/*.xlsx",
                "**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.bmp",
                "**/*.webp", "**/*.tif", "**/*.tiff")) {
            assertTrue(includes.contains(pattern), pattern + " missing from " + includes);
            assertFalse(excludes.contains(pattern), pattern + " should be governed by FileCapabilityPolicy, not excluded by glob");
        }
    }

    @Test
    void document_extraction_defaults_enable_pdf_word_excel_but_not_ocr() {
        Config cfg = new Config(null);

        @SuppressWarnings("unchecked")
        Map<String, Object> extraction = (Map<String, Object>) cfg.data.get("document_extraction");

        assertTrue(Boolean.TRUE.equals(extraction.get("enabled")));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) extraction.get("pdf")).get("enabled")));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) extraction.get("word")).get("enabled")));
        assertTrue(Boolean.TRUE.equals(((Map<?, ?>) extraction.get("excel")).get("enabled")));
        assertFalse(Boolean.TRUE.equals(((Map<?, ?>) extraction.get("image_ocr")).get("enabled")));
        assertTrue(((Map<?, ?>) extraction.get("image_ocr")).containsKey("command"));
    }

    @Test
    void config_defaults_match_resource_default_config_for_privacy() {
        Config cfg = new Config(null);

        List<String> excludes = excludes(cfg);

        for (String pattern : List.of(
                "**/.vscode/**", "**/.external assistant/**", "**/.gradle/**", "**/.mvn/**",
                "**/node_modules/**", "**/dist/**", "**/prompts/**", "**/META-INF/**")) {
            assertTrue(excludes.contains(pattern), pattern + " missing from fallback excludes");
        }
    }

    @Test
    void missing_user_config_still_gets_safe_rag_excludes() {
        Config cfg = new Config(java.nio.file.Path.of("missing-config-that-does-not-exist.yaml"));

        assertTrue(excludes(cfg).contains("**/*.env"));
        assertTrue(excludes(cfg).contains("**/secrets/**"));
        assertTrue(includes(cfg).contains("**/*.pdf"));
        assertFalse(excludes(cfg).contains("**/*.pdf"));
    }

    @Test
    void private_mode_defaults_present_when_config_missing() {
        Config cfg = new Config(null);

        assertFalse(ProtectedReadScopePolicy.privateMode(cfg));
        assertTrue(ProtectedReadScopePolicy.sendApprovedProtectedReadToModel(cfg));
        assertFalse(ProtectedReadScopePolicy.persistRawArtifacts(cfg));
        ProtectedReadScopePolicy.setPrivateMode(cfg, true);
        assertTrue(ProtectedReadScopePolicy.privateMode(cfg));
        assertFalse(ProtectedReadScopePolicy.ragEnabledInPrivateMode(cfg));
    }

    @SuppressWarnings("unchecked")
    private static List<String> excludes(Config cfg) {
        Map<String, Object> rag = (Map<String, Object>) cfg.data.get("rag");
        return (List<String>) rag.get("excludes");
    }

    @SuppressWarnings("unchecked")
    private static List<String> includes(Config cfg) {
        Map<String, Object> rag = (Map<String, Object>) cfg.data.get("rag");
        return (List<String>) rag.get("includes");
    }
}
