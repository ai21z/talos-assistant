package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

final class LlmClientResolverSeamTest {

    @Test
    void injected_resolver_receives_selection_and_chat_requests() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        assertEquals("ollama", resolver.selectedBackend);
        assertEquals("qwen3:8b", resolver.selectedModel);

        client.setModel("mock/custom-model");

        assertEquals("mock", resolver.selectedBackend);
        assertEquals("custom-model", resolver.selectedModel);

        LlmClient.StreamResult result = client.chatFull(List.of(
                new ChatMessage("system", "be helpful"),
                new ChatMessage("user", "hello")
        ), 5_000L);

        assertNotNull(resolver.lastRequest);
        assertEquals("mock", resolver.lastRequest.backend);
        assertEquals("custom-model", resolver.lastRequest.model);
        assertEquals("reply", result.text());
        assertEquals(1, resolver.chatCalls.get());
    }

    private static Config engineConfig() {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("model", "qwen3:8b");
        cfg.data.put("ollama", ollama);
        return cfg;
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private volatile String selectedBackend;
        private volatile String selectedModel;
        private volatile ChatRequest lastRequest;

        @Override
        public void select(String backend, String model) {
            this.selectedBackend = backend;
            this.selectedModel = model;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.lastRequest = request;
            chatCalls.incrementAndGet();
            return Stream.of(TokenChunk.of("reply"), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
