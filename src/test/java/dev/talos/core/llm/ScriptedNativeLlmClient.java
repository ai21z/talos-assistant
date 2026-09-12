package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.TokenChunk;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterators;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public final class ScriptedNativeLlmClient {
    private ScriptedNativeLlmClient() {}

    public static LlmClient of(List<LlmClient.StreamResult> responses) {
        return scripted(responses, false);
    }

    /** Provider fixture that returns the supplied responses, then throws once exhausted. */
    public static LlmClient ofThenFailure(
            List<LlmClient.StreamResult> responses,
            RuntimeException terminalFailure
    ) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent(
                "llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        return new LlmClient(config, new Resolver(
                responses,
                false,
                terminalFailure == null
                        ? new RuntimeException("scripted terminal failure")
                        : terminalFailure));
    }

    /**
     * Foreground provider fixture with an authoritative non-native tool
     * capability, used to exercise the explicit JSON-text fallback surface.
     */
    public static LlmClient textToolFallbackOf(List<LlmClient.StreamResult> responses) {
        return scripted(responses, true);
    }

    /**
     * Foreground provider fixture for deterministic textual responses on the
     * explicitly selected JSON-text tool surface.
     */
    public static LlmClient textToolFallbackStrings(List<String> responses) {
        List<LlmClient.StreamResult> scriptedResponses =
                responses == null || responses.isEmpty()
                        ? List.of(new LlmClient.StreamResult("", List.of()))
                        : responses.stream()
                                .map(response -> new LlmClient.StreamResult(
                                        response == null ? "" : response,
                                        List.of()))
                                .toList();
        return scripted(scriptedResponses, true);
    }

    public static LlmClient textToolFallbackStrings(String response) {
        return textToolFallbackStrings(List.of(response == null ? "" : response));
    }

    private static LlmClient scripted(
            List<LlmClient.StreamResult> responses,
            boolean authoritativeNonNative
    ) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
        }
        return new LlmClient(config, new Resolver(responses, authoritativeNonNative));
    }

    public record RecordedClient(LlmClient client, List<ChatRequest> requests) {}

    public record BlockingClient(
            LlmClient client,
            CountDownLatch firstChunkEmitted,
            AtomicBoolean streamClosed,
            AtomicInteger chatCalls
    ) {}

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
        return recordingWithContextWindow(
                config, responses, contextWindowTokens, false);
    }

    public static RecordedClient recordingTextToolFallbackWithContextWindow(
            List<LlmClient.StreamResult> responses,
            int contextWindowTokens) {
        Config config = new Config();
        return recordingWithContextWindow(
                config, responses, contextWindowTokens, true);
    }

    public static RecordedClient recordingWithContextWindow(
            Config config,
            List<LlmClient.StreamResult> responses,
            int contextWindowTokens) {
        return recordingWithContextWindow(
                config, responses, contextWindowTokens, false);
    }

    /** Native/REQUIRED-capable foreground fixture that records every request, then fails once exhausted. */
    public static RecordedClient recordingThenFailureWithContextWindow(
            List<LlmClient.StreamResult> responses,
            int contextWindowTokens,
            RuntimeException terminalFailure) {
        Config config = new Config();
        @SuppressWarnings("unchecked")
        Map<String, Object> llm = (Map<String, Object>) config.data.computeIfAbsent(
                "llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        RecordingResolver resolver = new RecordingResolver(responses, contextWindowTokens, false,
                java.util.Objects.requireNonNull(terminalFailure));
        return new RecordedClient(new LlmClient(config, resolver), resolver.requests());
    }

    private static RecordedClient recordingWithContextWindow(
            Config config,
            List<LlmClient.StreamResult> responses,
            int contextWindowTokens,
            boolean authoritativeNonNative) {
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        RecordingResolver resolver = new RecordingResolver(
                responses, contextWindowTokens, authoritativeNonNative);
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

    /**
     * Client whose first generation emits {@code partialText} as a visible
     * chunk and then fails with an external {@link EngineException.Transient}
     * (connection reset mid-stream). LlmClient converts that shape into an
     * aborted result carrying the partial text plus the trailing abort
     * marker - the exact shape the abort-truth paths must recognize. Any
     * later generation returns {@code "retry-output"} so a caller that
     * wrongly re-generates after the abort is observable.
     */
    public static LlmClient transientAfterPartialOutput(String partialText) {
        return transientAfterPartialOutput(partialText, false);
    }

    /**
     * Foreground provider fixture that selects the JSON-text tool surface,
     * emits a partial provider response, and then fails before provider EOS.
     */
    public static LlmClient transientTextToolAfterPartialOutput(String partialText) {
        return transientAfterPartialOutput(partialText, true);
    }

    private static LlmClient transientAfterPartialOutput(
            String partialText,
            boolean authoritativeNonNative) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        return new LlmClient(
                config,
                new TransientAfterPartialResolver(
                        partialText,
                        authoritativeNonNative));
    }

    public static BlockingClient blockingAfterFirstChunk(long configuredTimeoutMs) {
        return blockingAfterFirstChunk(configuredTimeoutMs, 1_000L);
    }

    public static BlockingClient blockingAfterFirstChunk(long configuredTimeoutMs, long configuredIdleMs) {
        Config config = new Config();
        Object llmBlock = config.data.computeIfAbsent("llm", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (llmBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> llm = (Map<String, Object>) map;
            llm.put("transport", "engine");
            llm.put("default_backend", "llama_cpp");
        }
        Object limitsBlock = config.data.computeIfAbsent("limits", ignored -> new java.util.LinkedHashMap<String, Object>());
        if (limitsBlock instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> limits = (Map<String, Object>) map;
            limits.put("llm_timeout_ms", configuredTimeoutMs);
            limits.put("llm_idle_ms", configuredIdleMs);
        }
        CountDownLatch firstChunkEmitted = new CountDownLatch(1);
        AtomicBoolean streamClosed = new AtomicBoolean();
        AtomicInteger chatCalls = new AtomicInteger();
        BlockingResolver resolver = new BlockingResolver(firstChunkEmitted, streamClosed, chatCalls);
        return new BlockingClient(
                new LlmClient(config, resolver),
                firstChunkEmitted,
                streamClosed,
                chatCalls);
    }

    private static final class Resolver implements LlmEngineResolver {
        private final List<LlmClient.StreamResult> responses;
        private final boolean authoritativeNonNative;
        private final RuntimeException terminalFailure;
        private final AtomicInteger cursor = new AtomicInteger();

        private Resolver(
                List<LlmClient.StreamResult> responses,
                boolean authoritativeNonNative
        ) {
            this(responses, authoritativeNonNative, null);
        }

        private Resolver(
                List<LlmClient.StreamResult> responses,
                boolean authoritativeNonNative,
                RuntimeException terminalFailure
        ) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of(new LlmClient.StreamResult("", List.of()))
                    : List.copyOf(responses);
            this.authoritativeNonNative = authoritativeNonNative;
            this.terminalFailure = terminalFailure;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, false, false, 0);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return authoritativeNonNative;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int next = cursor.getAndIncrement();
            if (terminalFailure != null && next >= responses.size()) {
                throw terminalFailure;
            }
            int index = Math.min(next, responses.size() - 1);
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
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatchCompletedTerminal(
                    request, handoff, () -> chatStream(request));
        }

        @Override
        public void close() {
        }
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final List<LlmClient.StreamResult> responses;
        private final RuntimeException terminalFailure;
        private final AtomicInteger cursor = new AtomicInteger();
        private final int contextWindowTokens;
        private final boolean authoritativeNonNative;
        private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());

        private RecordingResolver(
                List<LlmClient.StreamResult> responses,
                int contextWindowTokens,
                boolean authoritativeNonNative) {
            this(responses, contextWindowTokens, authoritativeNonNative, null);
        }

        private RecordingResolver(
                List<LlmClient.StreamResult> responses,
                int contextWindowTokens,
                boolean authoritativeNonNative,
                RuntimeException terminalFailure) {
            this.responses = responses == null || responses.isEmpty()
                    ? List.of(new LlmClient.StreamResult("", List.of()))
                    : List.copyOf(responses);
            this.contextWindowTokens = Math.max(256, contextWindowTokens);
            this.authoritativeNonNative = authoritativeNonNative;
            this.terminalFailure = terminalFailure;
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
                    !authoritativeNonNative,
                    !authoritativeNonNative,
                    !authoritativeNonNative,
                    false, false, false, true);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return authoritativeNonNative;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            requests.add(request);
            int next = cursor.getAndIncrement();
            if (terminalFailure != null && next >= responses.size()) throw terminalFailure;
            int index = Math.min(next, responses.size() - 1);
            return chunks(responses.get(index));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatchCompletedTerminal(
                    request, handoff, () -> chatStream(request));
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
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatchCompletedTerminal(
                    request, handoff, () -> chatStream(request));
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
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatch(
                    request, handoff, () -> chatStream(request));
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) {
            return chunks(recovery);
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatch(
                    request, handoff, () -> chatStreamNonStreaming(request));
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

    private static final class BlockingResolver implements LlmEngineResolver {
        private final CountDownLatch firstChunkEmitted;
        private final AtomicBoolean streamClosed;
        private final AtomicInteger chatCalls;

        private BlockingResolver(
                CountDownLatch firstChunkEmitted,
                AtomicBoolean streamClosed,
                AtomicInteger chatCalls) {
            this.firstChunkEmitted = firstChunkEmitted;
            this.streamClosed = streamClosed;
            this.chatCalls = chatCalls;
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
            chatCalls.incrementAndGet();
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private boolean emitted;

                @Override
                public boolean hasNext() {
                    if (!emitted) return true;
                    while (!streamClosed.get()) {
                        try {
                            TimeUnit.MILLISECONDS.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    throw new EngineException.Transient(
                            "stream closed by Talos",
                            new java.io.IOException("stream closed by Talos"),
                            408);
                }

                @Override
                public TokenChunk next() {
                    emitted = true;
                    firstChunkEmitted.countDown();
                    return TokenChunk.of("partial");
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
                    .onClose(() -> streamClosed.set(true));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatch(
                    request, handoff, () -> chatStream(request));
        }

        @Override
        public void close() {
        }
    }

    private static final class TransientAfterPartialResolver implements LlmEngineResolver {
        private final String partialText;
        private final boolean authoritativeNonNative;
        private final AtomicInteger chatCalls = new AtomicInteger();

        private TransientAfterPartialResolver(
                String partialText,
                boolean authoritativeNonNative) {
            this.partialText = partialText == null ? "" : partialText;
            this.authoritativeNonNative = authoritativeNonNative;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, false, false, 0);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return authoritativeNonNative;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            if (chatCalls.incrementAndGet() > 1) {
                return Stream.of(TokenChunk.of("retry-output"), TokenChunk.eos());
            }
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private boolean emitted;

                @Override
                public boolean hasNext() {
                    if (!emitted) return true;
                    throw new EngineException.Transient(
                            "external stream dropped after visible output",
                            new java.io.IOException("connection reset after partial output"),
                            408);
                }

                @Override
                public TokenChunk next() {
                    emitted = true;
                    return TokenChunk.of(partialText);
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return ObservedProviderTransportFixture.dispatch(
                    request, handoff, () -> chatStream(request));
        }

        @Override
        public void close() {
        }
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
