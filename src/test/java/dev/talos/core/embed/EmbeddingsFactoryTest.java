package dev.talos.core.embed;
import dev.talos.core.Config;
import dev.talos.core.spi.Embeddings;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
class EmbeddingsFactoryTest {
    @Test
    void defaultConfigResolvesBgeM3() {
        Config cfg = new Config();
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertSame(EmbeddingProfile.BGE_M3, profile,
                "Default config should resolve to the BGE_M3 built-in profile");
    }
    @Test
    void legacyOllamaEmbedKeyResolvesBgeM3() {
        Config cfg = new Config();
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.computeIfAbsent("ollama", k -> new LinkedHashMap<>());
        ollama.put("embed", "bge-m3");
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
        assertEquals("ollama", profile.provider());
    }
    @Test
    void qwen3ModelNameResolvesBuiltInProfile() {
        Config cfg = new Config();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "vllm");
        cfg.data.put("embed", embedSection);
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertSame(EmbeddingProfile.QWEN3_EMBED_8B, profile);
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
        Embeddings emb = EmbeddingsFactory.forQuery(cfg);
        assertFalse(emb instanceof InstructionEmbeddings,
                "bge-m3 queries should not be wrapped with instruction prefix");
    }
    @Test
    void forDocumentDoesNotWrapForBgeM3() {
        Config cfg = localOnlyConfig();
        Embeddings emb = EmbeddingsFactory.forDocument(cfg);
        assertFalse(emb instanceof InstructionEmbeddings,
                "bge-m3 documents should not be wrapped with instruction prefix");
    }
    @Test
    void forQueryWrapsForInstructionAwareProfile() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "vllm");
        cfg.data.put("embed", embedSection);
        Embeddings emb = EmbeddingsFactory.forQuery(cfg);
        assertInstanceOf(InstructionEmbeddings.class, emb,
                "Instruction-aware model should wrap query embedder");
    }
    @Test
    void forDocumentDoesNotWrapForQwen3() {
        Config cfg = localOnlyConfig();
        Map<String, Object> embedSection = new LinkedHashMap<>();
        embedSection.put("model", "Qwen/Qwen3-Embedding-8B");
        embedSection.put("provider", "vllm");
        cfg.data.put("embed", embedSection);
        Embeddings emb = EmbeddingsFactory.forDocument(cfg);
        assertFalse(emb instanceof InstructionEmbeddings,
                "Qwen3 documents have no instruction, should not wrap");
    }
    @Test
    void defaultProfileCacheNamespaceMatchesLegacyIndexerKey() {
        Config cfg = new Config();
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        assertEquals("ollama/bge-m3", profile.cacheNamespace());
    }
    private static Config localOnlyConfig() {
        Config cfg = new Config();
        @SuppressWarnings("unchecked")
        Map<String, Object> ollama = (Map<String, Object>) cfg.data.computeIfAbsent("ollama", k -> new LinkedHashMap<>());
        ollama.put("host", "http://127.0.0.1:11434");
        return cfg;
    }
}