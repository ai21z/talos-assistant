package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

public final class ScriptedNativeLlmClient {
    private ScriptedNativeLlmClient() {}

    public static LlmClient of(List<LlmClient.StreamResult> responses) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
        }
        return new LlmClient(config, new Resolver(responses));
    }

    private static final class Resolver implements LlmEngineResolver {
        private final List<LlmClient.StreamResult> responses;
        private final AtomicInteger cursor = new AtomicInteger();

        private Resolver(List<LlmClient.StreamResult> responses) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of(new LlmClient.StreamResult("", List.of()))
                    : List.copyOf(responses);
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, false, false, 0);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int index = Math.min(cursor.getAndIncrement(), responses.size() - 1);
            LlmClient.StreamResult response = responses.get(index);
            List<TokenChunk> chunks = new ArrayList<>();
            if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
                chunks.add(TokenChunk.ofToolCalls(response.toolCalls()));
            }
            if (response.text() != null && !response.text().isEmpty()) {
                chunks.add(TokenChunk.of(response.text()));
            }
            chunks.add(TokenChunk.eos());
            return chunks.stream();
        }

        @Override
        public void close() {
        }
    }
}
