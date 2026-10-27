package dev.talos.spi;

import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
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
    void legacyEngineRemainsUsableThroughDispatchAwareEntryPointWithoutForgingDispatch()
            throws Exception {
        StubEngine engine = new StubEngine();
        ObservingHandoff textHandoff = new ObservingHandoff();
        ObservingHandoff streamHandoff = new ObservingHandoff();
        ObservingHandoff nonStreamHandoff = new ObservingHandoff();
        ChatRequest request = new ChatRequest(
                "stub", "model", "sys", "usr", List.of(), Duration.ofSeconds(1));

        assertEquals("ok", engine.chat(request, textHandoff));
        assertEquals("ok", engine.chatStream(request, streamHandoff)
                .map(TokenChunk::text).reduce("", String::concat));
        assertEquals("ok", engine.chatStreamNonStreaming(request, nonStreamHandoff)
                .filter(chunk -> !Boolean.TRUE.equals(chunk.done()))
                .map(TokenChunk::text).reduce("", String::concat));

        for (ObservingHandoff handoff : List.of(
                textHandoff, streamHandoff, nonStreamHandoff)) {
            assertFalse(handoff.dispatched.get());
            assertTrue(handoff.unobserved.get());
        }
        assertEquals(2, engine.chatCalls);
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
        private int chatCalls;

        @Override public String id() { return "stub"; }
        @Override public Capabilities caps() { return Capabilities.of(true, true, false, 1024, false); }
        @Override public Health health() { return Health.ok("stub", true); }
        @Override public String chat(ChatRequest req) {
            chatCalls++;
            return "ok";
        }
        @Override public Stream<TokenChunk> chatStream(ChatRequest req) { return Stream.of(TokenChunk.of("ok")); }
        @Override public EmbeddingResult embed(List<String> texts) {
            return new EmbeddingResult(List.of(new float[]{1f}, new float[]{2f}), 1);
        }
    }

    private static final class ObservingHandoff implements ProviderTransportHandoff {
        private final AtomicBoolean dispatched = new AtomicBoolean();
        private final AtomicBoolean unobserved = new AtomicBoolean();

        @Override
        public void requestDispatched() {
            dispatched.set(true);
        }

        @Override
        public void transportUnobserved() {
            unobserved.set(true);
        }
    }
}
