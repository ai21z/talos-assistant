package dev.talos.core.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * E2E-only foreground provider fixture.
 *
 * <p>Unlike {@link LlmClient#scripted(List)}, this fixture crosses the typed
 * provider-dispatch boundary, reports authoritative non-native capabilities,
 * and terminates every response with an observed provider EOS. It therefore
 * exercises JSON-text tool authority without manufacturing authority for the
 * production scripted-harness seam.
 */
public final class ForegroundProviderScriptedLlmClient {
    private ForegroundProviderScriptedLlmClient() {}

    public static LlmClient create(Config config, List<String> responses) {
        return create(config, responses, ignored -> {});
    }

    /** Observes transient requests at the scripted provider send boundary without retaining them. */
    public static LlmClient create(
            Config config, List<String> responses, Consumer<ChatRequest> requestObserver) {
        Config effectiveConfig = config == null ? new Config() : config;
        Object llmBlock = effectiveConfig.data.get("llm");
        if (llmBlock != null && !(llmBlock instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("llm configuration must be a map");
        }
        Map<String, Object> llm = new LinkedHashMap<>();
        if (llmBlock instanceof Map<?, ?> rawLlm) {
            rawLlm.forEach((key, value) -> llm.put(String.valueOf(key), value));
        }
        llm.put("transport", "engine");
        llm.putIfAbsent("default_backend", "foreground-provider-fixture");
        llm.putIfAbsent("model", "foreground-provider-fixture");
        effectiveConfig.data.put("llm", llm);
        return new LlmClient(effectiveConfig, new Resolver(responses, requestObserver));
    }

    private static final class Resolver implements LlmEngineResolver {
        private static final ObjectMapper JSON = new ObjectMapper();

        private final List<String> responses;
        private final AtomicInteger cursor = new AtomicInteger();
        private final Consumer<ChatRequest> requestObserver;

        private Resolver(List<String> responses, Consumer<ChatRequest> requestObserver) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of("")
                    : responses.stream().map(response -> response == null ? "" : response).toList();
            this.requestObserver = requestObserver == null ? ignored -> {} : requestObserver;
        }

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, false);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return responseChunks(request);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatchCompletedTerminal(
                    request,
                    physicalProviderBody(request),
                    handoff,
                    () -> responseChunks(request));
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatchCompletedTerminal(
                    request,
                    physicalProviderBody(request),
                    handoff,
                    () -> responseChunks(request));
        }

        private Stream<TokenChunk> responseChunks(ChatRequest request) {
            requestObserver.accept(request);
            int index = Math.min(cursor.getAndIncrement(), responses.size() - 1);
            String response = responses.get(index);
            List<TokenChunk> chunks = new ArrayList<>(2);
            if (!response.isEmpty()) {
                chunks.add(TokenChunk.of(response));
            }
            chunks.add(TokenChunk.eos("stop"));
            return chunks.stream();
        }

        private static String physicalProviderBody(ChatRequest request) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("backend", request.backend);
            body.put("model", request.model);
            body.put("systemPrompt", request.systemPrompt);
            body.put("userPrompt", request.userPrompt);
            body.put("snippets", request.snippets);
            body.put("timeoutMillis", request.timeout.toMillis());
            body.put("messages", request.messages);
            body.put("tools", request.tools);
            body.put("controls", request.controls);
            try {
                return JSON.writeValueAsString(body);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(
                        "foreground provider fixture request was not serializable",
                        e);
            }
        }

        @Override
        public void close() {}
    }
}
