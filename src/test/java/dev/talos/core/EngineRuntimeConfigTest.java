package dev.talos.core;

import org.junit.jupiter.api.Test;

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
        assertEquals("network on; local engine only (llama_cpp)", runtime.policyLabel());
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
}
