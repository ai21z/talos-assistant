package dev.talos.core;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class EngineRuntimeConfigTest {

    @Test
    void defaultConfigResolvesLlamaCppBackendAndModel() {
        Config cfg = new Config(null);

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("llama_cpp", runtime.backend());
        assertEquals("talos-agent", runtime.model());
        assertEquals("llama_cpp/talos-agent", runtime.displayModel());
        assertFalse(runtime.policyLabel().contains("Ollama"));
    }

    @Test
    void llmModelTakesPrecedenceOverBackendSpecificModel() {
        Config cfg = new Config(null);
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
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of("default_backend", "llama_cpp")));
        cfg.data.put("engines", Map.of("llama_cpp", Map.of(
                "hf_repo", "ggml-org/gpt-oss-20b-GGUF")));

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("gpt-oss-20b-GGUF", runtime.model());
        assertEquals("llama_cpp/gpt-oss-20b-GGUF", runtime.displayModel());
    }

    @Test
    void explicitOllamaSelectionStillUsesLegacyOllamaConfig() {
        Config cfg = new Config(null);
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
        Config cfg = new Config(null);
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "compat",
                "model", "custom-embed")));

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(cfg);

        assertEquals("compat", runtime.embeddingProvider());
        assertEquals("custom-embed", runtime.embeddingModel());
        assertEquals("compat/custom-embed", runtime.embeddingLabel());
    }

    @Test
    void defaultEmbeddingSummaryIsDisabled() {
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(new Config(null));

        assertEquals("disabled", runtime.embeddingProvider());
        assertEquals("none", runtime.embeddingModel());
        assertEquals("disabled/none", runtime.embeddingLabel());
    }

    /**
     * The status label states the remote Ollama decision with the same parser
     * the Ollama provider uses, so it never says "local" while remote is allowed.
     */
    @ParameterizedTest(name = "allow_remote = {0}")
    @ValueSource(strings = {"true", "yes", "on", "1", "false", "off", "maybe"})
    void remoteOllamaLabelFollowsTheProviderParser(String allowRemote) {
        Config cfg = new Config(null);
        cfg.data.put("llm", new LinkedHashMap<>(Map.of("default_backend", "ollama")));
        cfg.data.put("ollama", new LinkedHashMap<>(Map.of(
                "host", "http://10.0.0.5:11434",
                "model", "qwen2.5-coder:14b",
                "allow_remote", allowRemote)));

        String label = EngineRuntimeConfig.from(cfg).policyLabel();

        boolean providerAllowsRemote = cfg.view().ollama().allowRemote();
        assertEquals(providerAllowsRemote ? "network on; remote Ollama allowed" : "network on; local Ollama only",
                label);
    }
}
