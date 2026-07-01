package dev.talos.cli.launcher;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TopLevelStatusCmdTest {

    @Test
    void verboseEngineStatusIsBackendNeutralForDefaultLlamaCpp() {
        String output = TopLevelStatusCmd.renderEngineStatus(new Config(null));

        assertTrue(output.contains("Backend     : llama_cpp"));
        assertTrue(output.contains("Chat model  : talos-agent"));
        assertTrue(output.contains("Embeddings  : disabled/none"));
        assertFalse(output.contains("Ollama host"));
    }

    @Test
    void verboseEngineStatusMentionsOllamaOnlyWhenSelected() {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of("default_backend", "ollama")));
        cfg.data.put("ollama", new LinkedHashMap<>(Map.of(
                "host", "http://127.0.0.1:11434",
                "model", "qwen2.5-coder:14b",
                "embed", "bge-m3")));

        String output = TopLevelStatusCmd.renderEngineStatus(cfg);

        assertTrue(output.contains("Backend     : ollama"));
        assertTrue(output.contains("Ollama host : http://127.0.0.1:11434"));
    }
}
