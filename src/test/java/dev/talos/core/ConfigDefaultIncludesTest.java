package dev.talos.core;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigDefaultIncludesTest {

    @Test
    void defaultRagIncludesContainLightweightTableFiles() {
        Config config = new Config();

        @SuppressWarnings("unchecked")
        Map<String, Object> rag = (Map<String, Object>) config.data.get("rag");
        @SuppressWarnings("unchecked")
        List<String> includes = (List<String>) rag.get("includes");

        assertTrue(includes.contains("**/*.csv"));
        assertTrue(includes.contains("**/*.tsv"));
    }
}
