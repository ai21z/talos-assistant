package dev.talos.spi;

import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class ModelEngineCompositionTest {

    @Test
    void modelEngine_extends_chat_and_embedding_interfaces() {
        assertTrue(ChatModelEngine.class.isAssignableFrom(ModelEngine.class));
        assertTrue(EmbeddingEngine.class.isAssignableFrom(ModelEngine.class));
    }

    @Test
    void composed_engine_is_usable_through_narrower_views() throws Exception {
        ModelEngine engine = new StubEngine();

        ChatModelEngine chat = engine;
        EmbeddingEngine embed = engine;

        String chatOut = chat.chat(new ChatRequest(
                "stub", "model", "sys", "usr", List.of(), Duration.ofSeconds(1)));
        EmbeddingResult embedOut = embed.embed(List.of("a", "b"));

        assertEquals("ok", chatOut);
        assertEquals(2, embedOut.vectors().size());
    }

    @Test
    void capabilityFactoriesDefaultProviderControlFlagsToFalse() {
        Capabilities caps = Capabilities.of(true, true, false, 1024, true);

        assertTrue(caps.nativeTools());
        assertFalse(caps.requiredToolChoice());
        assertFalse(caps.namedToolChoice());
        assertFalse(caps.jsonObjectResponse());
        assertFalse(caps.jsonSchemaResponse());
        assertFalse(caps.serverModelCatalog());
        assertFalse(caps.managedProcess());
    }

    @Test
    void capabilityFullFactoryReportsProviderControlFlags() {
        Capabilities caps = Capabilities.of(
                true,
                true,
                true,
                32768,
                true,
                true,
                true,
                true,
                true,
                true,
                true);

        assertTrue(caps.nativeTools());
        assertTrue(caps.requiredToolChoice());
        assertTrue(caps.namedToolChoice());
        assertTrue(caps.jsonObjectResponse());
        assertTrue(caps.jsonSchemaResponse());
        assertTrue(caps.serverModelCatalog());
        assertTrue(caps.managedProcess());
    }

    private static final class StubEngine implements ModelEngine {
        @Override public String id() { return "stub"; }
        @Override public Capabilities caps() { return Capabilities.of(true, true, false, 1024, false); }
        @Override public Health health() { return Health.ok("stub", true); }
        @Override public String chat(ChatRequest req) { return "ok"; }
        @Override public Stream<TokenChunk> chatStream(ChatRequest req) { return Stream.of(TokenChunk.of("ok")); }
        @Override public EmbeddingResult embed(List<String> texts) {
            return new EmbeddingResult(List.of(new float[]{1f}, new float[]{2f}), 1);
        }
    }
}
