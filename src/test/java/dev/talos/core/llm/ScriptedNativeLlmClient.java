package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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

    public record RecordedClient(LlmClient client, List<ChatRequest> requests) {}

    public record CompactAwareClient(
            LlmClient client,
            List<ChatRequest> requests,
            AtomicInteger normalContinuations,
            AtomicInteger compactContinuations
    ) {}

    public static RecordedClient recordingWithContextWindow(
            List<LlmClient.StreamResult> responses,
            int contextWindowTokens) {
        Config config = new Config();
        return recordingWithContextWindow(config, responses, contextWindowTokens);
    }

    public static RecordedClient recordingWithContextWindow(
            Config config,
            List<LlmClient.StreamResult> responses,
            int contextWindowTokens) {
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        RecordingResolver resolver = new RecordingResolver(responses, contextWindowTokens);
        return new RecordedClient(new LlmClient(config, resolver), resolver.requests());
    }

    public static CompactAwareClient compactMutationContinuationAware(
            LlmClient.StreamResult normalResponse,
            LlmClient.StreamResult compactResponse) {
        return compactMutationContinuationAware(List.of(normalResponse), compactResponse);
    }

    public static CompactAwareClient compactMutationContinuationAware(
            List<LlmClient.StreamResult> normalResponses,
            LlmClient.StreamResult compactResponse) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        CompactAwareResolver resolver = new CompactAwareResolver(normalResponses, compactResponse);
        return new CompactAwareClient(
                new LlmClient(config, resolver),
                resolver.requests(),
                resolver.normalContinuations(),
                resolver.compactContinuations());
    }

    public static LlmClient compatMalformedStreamThenNonStreamingRecovery(
            LlmClient.StreamResult recovery,
            List<LlmClient.StreamResult> followups) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        return new LlmClient(config, new CompatRecoveryResolver(recovery, followups));
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
            chunks.add(TokenChunk.eos(response.finishReason()));
            return chunks.stream();
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final List<LlmClient.StreamResult> responses;
        private final AtomicInteger cursor = new AtomicInteger();
        private final int contextWindowTokens;
        private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

        private RecordingResolver(List<LlmClient.StreamResult> responses, int contextWindowTokens) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of(new LlmClient.StreamResult("", List.of()))
                    : List.copyOf(responses);
            this.contextWindowTokens = Math.max(256, contextWindowTokens);
        }

        private List<ChatRequest> requests() {
            return requests;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(
                    true, true, false, contextWindowTokens,
                    true, true, true,
                    false, false, false, true);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            requests.add(request);
            int index = Math.min(cursor.getAndIncrement(), responses.size() - 1);
            return chunks(responses.get(index));
        }

        @Override
        public void close() {
        }
    }

    private static final class CompactAwareResolver implements LlmEngineResolver {
        private final List<LlmClient.StreamResult> normalResponses;
        private final LlmClient.StreamResult compactResponse;
        private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger normalContinuations = new AtomicInteger();
        private final AtomicInteger compactContinuations = new AtomicInteger();

        private CompactAwareResolver(
                List<LlmClient.StreamResult> normalResponses,
                LlmClient.StreamResult compactResponse) {
            this.normalResponses = normalResponses == null || normalResponses.isEmpty()
                    ? List.of(new LlmClient.StreamResult("", List.of()))
                    : List.copyOf(normalResponses);
            this.compactResponse = compactResponse == null
                    ? new LlmClient.StreamResult("", List.of())
                    : compactResponse;
        }

        private List<ChatRequest> requests() {
            return requests;
        }

        private AtomicInteger normalContinuations() {
            return normalContinuations;
        }

        private AtomicInteger compactContinuations() {
            return compactContinuations;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(
                    true, true, false, 16_384,
                    true, true, true,
                    false, false, false, true);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            requests.add(request);
            String joined = request.messages == null
                    ? ""
                    : request.messages.stream()
                    .map(message -> message == null ? "" : message.content())
                    .filter(java.util.Objects::nonNull)
                    .reduce("", (left, right) -> left + "\n" + right);
            if (joined.contains("[CompactMutationContinuation]")) {
                compactContinuations.incrementAndGet();
                return chunks(compactResponse);
            }
            int index = normalContinuations.getAndIncrement();
            return chunks(normalResponses.get(Math.min(index, normalResponses.size() - 1)));
        }

        @Override
        public void close() {
        }
    }

    private static final class CompatRecoveryResolver implements LlmEngineResolver {
        private final LlmClient.StreamResult recovery;
        private final List<LlmClient.StreamResult> followups;
        private final AtomicInteger streamCalls = new AtomicInteger();
        private final AtomicInteger followupCursor = new AtomicInteger();

        private CompatRecoveryResolver(
                LlmClient.StreamResult recovery,
                List<LlmClient.StreamResult> followups) {
            this.recovery = recovery == null ? new LlmClient.StreamResult("", List.of()) : recovery;
            this.followups = followups == null || followups.isEmpty()
                    ? List.of(new LlmClient.StreamResult("", List.of()))
                    : List.copyOf(followups);
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(
                    true, true, false, 16_384,
                    true, true, true,
                    false, false, false, true);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            if (streamCalls.getAndIncrement() == 0) {
                return malformedToolArgumentStream();
            }
            int index = Math.min(followupCursor.getAndIncrement(), followups.size() - 1);
            return chunks(followups.get(index));
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) {
            return chunks(recovery);
        }

        @Override
        public void close() {
        }
    }

    private static Stream<TokenChunk> chunks(LlmClient.StreamResult response) {
        List<TokenChunk> chunks = new ArrayList<>();
        if (response.toolCalls() != null && !response.toolCalls().isEmpty()) {
            chunks.add(TokenChunk.ofToolCalls(response.toolCalls()));
        }
        if (response.text() != null && !response.text().isEmpty()) {
            chunks.add(TokenChunk.of(response.text()));
        }
        chunks.add(TokenChunk.eos(response.finishReason()));
        return chunks.stream();
    }

    private static Stream<TokenChunk> malformedToolArgumentStream() {
        Iterator<TokenChunk> iterator = new Iterator<>() {
            @Override
            public boolean hasNext() {
                throw new EngineException.MalformedResponse(
                        "compat chat stream tool arguments",
                        "{\"path\":\"scripts.js\",\"content\":\"console.log('new');");
            }

            @Override
            public TokenChunk next() {
                throw new NoSuchElementException();
            }
        };
        return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
    }
}
