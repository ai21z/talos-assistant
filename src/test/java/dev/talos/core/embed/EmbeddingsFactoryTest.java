package dev.talos.core.embed;
import dev.talos.core.Config;
import dev.talos.spi.Embeddings;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class EmbeddingsFactoryTest {
    @Test
    void defaultConfigResolvesCompatEmbeddingProfile() {
        Config cfg = new Config();
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertEquals("compat", profile.provider());
        assertEquals("talos-embed", profile.model());
    }
    @Test
    void legacyOllamaEmbedKeyResolvesBgeM3() {
        Config cfg = new Config();
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.computeIfAbsent("ollama", k -> new LinkedHashMap<>());
        ollama.put("embed", "bge-m3");
        cfg.data.put("embed", new LinkedHashMap<>(Map.of("provider", "ollama")));
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertSame(EmbeddingProfile.BGE_M3, profile);
    }
    @Test
    void embedModelKeyTakesPrecedenceOverOllamaEmbed() {
        Config cfg = new Config();
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.computeIfAbsent("ollama", k -> new LinkedHashMap<>());
        ollama.put("embed", "bge-m3");
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "custom-embed");
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertEquals("custom-embed", profile.model());
        assertEquals("compat", profile.provider());
    }
    @Test
    void qwen3ModelNameResolvesBuiltInProfile() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "ollama");
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertSame(EmbeddingProfile.QWEN3_EMBED_8B, profile,
                "Qwen model with no overrides should return the built-in singleton");
        assertEquals("ollama", profile.provider());
    }

    @Test
    void qwen3WithProviderOverridePreservesConfigProvider() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "openai_compat");
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertNotSame(EmbeddingProfile.QWEN3_EMBED_8B, profile,
                "Overridden provider must produce a new profile, not the built-in singleton");
        assertEquals("openai_compat", profile.provider(),
                "Resolved profile must preserve the config provider override");
        assertEquals("Qwen/Qwen3-Embedding-8B", profile.model());
        // Other fields should inherit from built-in defaults
        assertEquals(1024, profile.dimensions());
        assertTrue(profile.instructionAware());
    }

    @Test
    void qwen3WithDimensionsOverride() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("dimensions", 2048);
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertNotSame(EmbeddingProfile.QWEN3_EMBED_8B, profile,
                "Overridden dimensions must produce a new profile");
        assertEquals(2048, profile.dimensions(),
                "Resolved profile must preserve the config dimensions override");
        assertEquals("compat", profile.provider(),
                "Non-overridden provider should default to compat");
        assertTrue(profile.instructionAware(),
                "Should inherit instruction-aware from built-in");
    }

    @Test
    void qwen3WithQueryInstructionOverride() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("query_instruction", "custom: search for relevant code\n");
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertNotSame(EmbeddingProfile.QWEN3_EMBED_8B, profile,
                "Overridden query instruction must produce a new profile");
        assertEquals("custom: search for relevant code\n", profile.queryInstruction(),
                "Resolved profile must preserve the config query_instruction override");
        assertTrue(profile.instructionAware());
        assertEquals(1024, profile.dimensions(),
                "Non-overridden dimensions should inherit built-in default");
    }

    @Test
    void qwen3WithMultipleOverridesPreservesAll() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "openai_compat");
        embedSection.put("dimensions", 4096);
        embedSection.put("query_instruction", "domain: ");
        embedSection.put("normalize", false);
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertNotSame(EmbeddingProfile.QWEN3_EMBED_8B, profile);
        assertEquals("openai_compat", profile.provider());
        assertEquals("Qwen/Qwen3-Embedding-8B", profile.model());
        assertEquals(4096, profile.dimensions());
        assertEquals("domain: ", profile.queryInstruction());
        assertFalse(profile.normalize());
        assertTrue(profile.instructionAware());
        assertEquals(32768, profile.maxInputTokens(),
                "Non-overridden maxInputTokens should inherit built-in default");
    }
    @Test
    void customModelBuildsDynamicProfile() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "my-embed-v1");
        embedSection.put("provider", "vllm");
        embedSection.put("dimensions", 768);
        embedSection.put("query_instruction", "search_query: ");
        embedSection.put("max_input_tokens", 4096);
        embedSection.put("normalize", false);
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertEquals("my-embed-v1", profile.model());
        assertEquals("vllm", profile.provider());
        assertEquals(768, profile.dimensions());
        assertTrue(profile.instructionAware());
        assertEquals("search_query: ", profile.queryInstruction());
        assertEquals(4096, profile.maxInputTokens());
        assertFalse(profile.normalize());
    }
    @Test
    void nullConfigThrows() {
        assertThrows(NullPointerException.class, () -> EmbeddingsFactory.profileFrom(null));
    }
    @Test
    void forQueryDoesNotWrapForBgeM3() {
        Config cfg = localOnlyConfig();
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "ollama",
                "model", "bge-m3")));
        Embeddings emb = EmbeddingsFactory.forQuery(cfg);
        assertFalse(emb instanceof InstructionEmbeddings,
                "bge-m3 queries should not be wrapped with instruction prefix");
    }
    @Test
    void forDocumentDoesNotWrapForBgeM3() {
        Config cfg = localOnlyConfig();
        cfg.data.put("embed", new LinkedHashMap<>(Map.of(
                "provider", "ollama",
                "model", "bge-m3")));
        Embeddings emb = EmbeddingsFactory.forDocument(cfg);
        assertFalse(emb instanceof InstructionEmbeddings,
                "bge-m3 documents should not be wrapped with instruction prefix");
    }
    @Test
    void forQueryWrapsForInstructionAwareProfile() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "custom-instr-model");
        embedSection.put("provider", "ollama");
        embedSection.put("query_instruction", "search: ");
        cfg.data.put("embed", embedSection);
        Embeddings emb = EmbeddingsFactory.forQuery(cfg);
        assertInstanceOf(InstructionEmbeddings.class, emb,
                "Instruction-aware model should wrap query embedder");
    }
    @Test
    void forDocumentDoesNotWrapWhenNoDocumentInstruction() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "custom-instr-model");
        embedSection.put("provider", "ollama");
        embedSection.put("query_instruction", "search: ");
        // No document_instruction
        cfg.data.put("embed", embedSection);
        Embeddings emb = EmbeddingsFactory.forDocument(cfg);
        assertFalse(emb instanceof InstructionEmbeddings,
                "Profile with no document instruction should not wrap documents");
    }
    @Test
    void defaultProfileCacheNamespaceUsesFingerprint() {
        Config cfg = new Config();
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertEquals(profile.fingerprint(), profile.cacheNamespace(),
                "Cache namespace must equal fingerprint for safe isolation");
    }
    // ── Provider selection ─────────────────────────────────────────────
    @Test
    void forQueryCreatesCompatProvider() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "compat-model");
        embedSection.put("provider", "compat");
        embedSection.put("host", "http://127.0.0.1:8080");
        cfg.data.put("embed", embedSection);
        Embeddings emb = EmbeddingsFactory.forQuery(cfg);
        assertInstanceOf(CompatEmbeddingsClient.class, emb);
    }

    @Test
    void disabledProviderConstructsClearDisabledEmbedder() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "none");
        embedSection.put("provider", "disabled");
        cfg.data.put("embed", embedSection);
        Embeddings emb = EmbeddingsFactory.forDocument(cfg);
        assertInstanceOf(DisabledEmbeddings.class, emb);
        var ex = assertThrows(UnsupportedOperationException.class, () -> emb.embed("hello"));
        assertTrue(ex.getMessage().contains("disabled"));
    }

    @Test
    void forDocumentThrowsForUnsupportedProviderWithoutOllamaOnlyClaim() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "some-model");
        embedSection.put("provider", "vllm");
        cfg.data.put("embed", embedSection);
        var ex = assertThrows(UnsupportedOperationException.class,
                () -> EmbeddingsFactory.forDocument(cfg));
        assertTrue(ex.getMessage().contains("vllm"));
        assertFalse(ex.getMessage().contains("Only 'ollama' is implemented"));
    }
    @Test
    void profileResolutionAloneDoesNotThrowForUnsupportedProvider() {
        // profileFrom is pure resolution — no transport construction
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "vllm");
        cfg.data.put("embed", embedSection);
        assertDoesNotThrow(() -> EmbeddingsFactory.profileFrom(cfg),
                "profileFrom should resolve without touching transport");
    }
    private static Config localOnlyConfig() {
        Config cfg = new Config();
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.computeIfAbsent("ollama", k -> new LinkedHashMap<>());
        ollama.put("host", "http://127.0.0.1:11434");
        return cfg;
    }
}
