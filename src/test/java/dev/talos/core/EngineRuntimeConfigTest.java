package dev.talos.core;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineRuntimeConfigTest {

    @Test
    void defaultConfigResolvesLlamaCppBackendAndModel() {
        Config cfg = new Config();

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("llama_cpp", runtime.backend());
        assertEquals("talos-agent", runtime.model());
        assertEquals("llama_cpp/talos-agent", runtime.displayModel());
        assertFalse(runtime.policyLabel().contains("Ollama"));
    }

    @Test
    void llmModelTakesPrecedenceOverBackendSpecificModel() {
        Config cfg = new Config();
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "default_backend", "llama_cpp",
                "model", "explicit-agent")));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of("model", "backend-agent")));

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("explicit-agent", runtime.model());
        assertEquals("llama_cpp/explicit-agent", runtime.displayModel());
    }

    @Test
    void llamaCppHfRepoCanSupplyDisplayModelWhenAliasIsUnset() {
        Config cfg = new Config();
        cfg.data.put("llm", new LinkedHashMap<>(Map.of("default_backend", "llama_cpp")));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of(
                "hf_repo", "ggml-org/gpt-oss-20b-GGUF")));

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("gpt-oss-20b-GGUF", runtime.model());
        assertEquals("llama_cpp/gpt-oss-20b-GGUF", runtime.displayModel());
    }

    @Test
    void explicitOllamaSelectionStillUsesLegacyOllamaConfig() {
        Config cfg = new Config();
        cfg.data.put("llm", new LinkedHashMap<>(Map.of("default_backend", "ollama")));
        cfg.data.put("ollama", new LinkedHashMap<>(Map.of(
                "host", "http://127.0.0.1:11434",
                "model", "qwen2.5-coder:14b",
                "allow_remote", false)));

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("ollama", runtime.backend());
        assertEquals("qwen2.5-coder:14b", runtime.model());
        assertEquals("ollama/qwen2.5-coder:14b", runtime.displayModel());
        assertEquals("http://127.0.0.1:11434", runtime.hostLabel());
        assertEquals("network on; local Ollama only", runtime.policyLabel());
    }

    @Test
    void embeddingSummaryReadsProviderAndModelFromEmbedBlock() {
        Config cfg = new Config();
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "compat",
                "model", "talos-embed")));

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("compat", runtime.embeddingProvider());
        assertEquals("talos-embed", runtime.embeddingModel());
        assertEquals("compat/talos-embed", runtime.embeddingLabel());
    }
}
