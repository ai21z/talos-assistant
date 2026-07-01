package dev.talos.core.index;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagDefaultConfigPrivacyTest {

    @Test
    void default_rag_config_excludes_protected_paths() throws Exception {
        String config = defaultConfigText();
        String includes = section(config, "  includes:", "  excludes:");
        String excludes = section(config, "  excludes:", "  top_k:");

        assertFalse(includes.contains("- \"**/*.env\""));
        assertTrue(excludes.contains("- \"**/.env\""));
        assertTrue(excludes.contains("- \"**/.env.*\""));
        assertTrue(excludes.contains("- \"**/*.env\""));
        assertTrue(excludes.contains("- \"**/secrets/**\""));
        assertTrue(excludes.contains("- \"**/protected/**\""));
        assertTrue(excludes.contains("- \"**/.ssh/**\""));
        assertTrue(excludes.contains("- \"**/.aws/**\""));
        assertTrue(excludes.contains("- \"**/.azure/**\""));
        assertTrue(excludes.contains("- \"**/.gnupg/**\""));
        assertTrue(excludes.contains("- \"**/.config/gcloud/**\""));
    }

    private static String defaultConfigText() throws Exception {
        try (InputStream in = RagDefaultConfigPrivacyTest.class.getClassLoader()
                .getResourceAsStream("config/default-config.yaml")) {
            assertNotNull(in);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String section(String text, String startMarker, String endMarker) {
        int start = text.indexOf(startMarker);
        int end = text.indexOf(endMarker, start + startMarker.length());
        assertTrue(start >= 0, startMarker);
        assertTrue(end > start, endMarker);
        return text.substring(start, end);
    }
}
