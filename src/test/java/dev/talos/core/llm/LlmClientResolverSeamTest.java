package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Spliterators;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.StreamSupport;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class LlmClientResolverSeamTest {

    @Test
    void injected_resolver_receives_selection_and_chat_requests() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        assertEquals("ollama", resolver.selectedBackend);
        assertEquals("qwen2.5-coder:14b", resolver.selectedModel);

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

    @Test
    void configured_llm_timeout_reaches_engine_chat_requests() {
        RecordingResolver resolver = new RecordingResolver();
        LlmClient client = new LlmClient(engineConfigWithTimeout(240_000L), resolver);
        List<ChatMessage> messages = List.of(ChatMessage.user("what is this workspace?"));

        client.chatStreamFull(messages, null);
        assertEquals(Duration.ofMillis(240_000L), resolver.requests.get(0).timeout);

        client.chatFull(messages);
        assertEquals(Duration.ofMillis(240_000L), resolver.requests.get(1).timeout);
    }

    @Test
    void locally_closed_stream_read_abort_does_not_start_retry_generation() throws Exception {
        LocallyClosedStreamResolver resolver = new LocallyClosedStreamResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        StringBuilder emitted = new StringBuilder();

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("slow local generation")),
                emitted::append,
                150L,
                List.of());

        assertNotNull(result);
        assertEquals(1, resolver.chatCalls.get(), "local stream close must not retry immediately");
        assertFalse(resolver.retryStarted.await(900, TimeUnit.MILLISECONDS),
                "Talos-initiated stream close must not start a second generation");
        assertEquals("partial", emitted.toString(),
                "retry output must not be emitted after the local abort");
    }

    @Test
    void external_stream_transient_still_retries_generation() {
        ExternalTransientThenSuccessResolver resolver = new ExternalTransientThenSuccessResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("temporary backend fault")),
                null,
                5_000L,
                List.of());

        assertEquals("retry-ok", result.text());
        assertEquals(2, resolver.chatCalls.get(),
                "external transient failure should still use the retry policy");
    }

    @Test
    void external_stream_transient_after_visible_output_does_not_retry_or_duplicate() {
        ExternalTransientAfterVisibleOutputResolver resolver =
                new ExternalTransientAfterVisibleOutputResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        StringBuilder emitted = new StringBuilder();

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("stream then drop")),
                emitted::append,
                5_000L,
                List.of());

        assertEquals(1, resolver.chatCalls.get(),
                "retry after visible output would duplicate the streamed prefix");
        assertEquals("prefix", emitted.toString());
        assertTrue(result.text().contains("[turn aborted"), result.text());
        assertFalse(result.text().contains("retry-output"), result.text());
    }

    @Test
    void streaming_text_tool_call_closes_generation_before_trailing_ramble() {
        TextToolCallThenRambleResolver resolver = new TextToolCallThenRambleResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("create the file")),
                null,
                5_000L,
                List.of());

        assertEquals(2, resolver.chunksConsumed.get(),
                "generation should stop after the complete text-form tool call");
        assertTrue(resolver.streamClosed.get(), "breaking after the tool call should close the provider stream");
        assertTrue(result.text().contains("\"name\":\"talos.write_file\""), result.text());
        assertFalse(result.text().contains("trailing ramble"), result.text());
    }

    @Test
    void ordinary_fenced_json_does_not_close_generation_early() {
        OrdinaryJsonFenceThenAnswerResolver resolver = new OrdinaryJsonFenceThenAnswerResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("show me an example package.json")),
                null,
                5_000L,
                List.of());

        assertEquals(4, resolver.chunksConsumed.get(),
                "ordinary fenced package JSON must not be treated as complete tool protocol");
        assertTrue(result.text().contains("demo-app"), result.text());
        assertTrue(result.text().contains("done after example"), result.text());
    }

    @Test
    void mixed_native_tool_calls_do_not_trigger_text_tool_early_close() {
        MixedNativeAndTextToolResolver resolver = new MixedNativeAndTextToolResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("create the file")),
                null,
                5_000L,
                List.of());

        assertEquals(4, resolver.chunksConsumed.get(),
                "once native tool calls are present, text cleanup must not close the provider stream early");
        assertTrue(result.hasToolCalls());
        assertTrue(result.text().contains("tail after native call"), result.text());
    }

    @Test
    void stream_result_records_length_finish_reason() {
        LengthFinishReasonResolver resolver = new LengthFinishReasonResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("answer briefly")),
                5_000L,
                List.of());

        assertTrue(result.outputLimitReached());
        assertEquals("length", result.finishReason());
    }

    private static Config engineConfig() {
        return engineConfigWithTimeout(null);
    }

    private static Config engineConfigWithTimeout(Long timeoutMs) {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("model", "qwen2.5-coder:14b");
        cfg.data.put("ollama", ollama);
        if (timeoutMs != null) {
            LinkedHashMap<String, Object> limits = new LinkedHashMap<>();
            limits.put("llm_timeout_ms", timeoutMs);
            cfg.data.put("limits", limits);
        }
        return cfg;
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private volatile String selectedBackend;
        private volatile String selectedModel;
        private volatile ChatRequest lastRequest;
        private final List<ChatRequest> requests = new ArrayList<>();

        @Override
        public void select(String backend, String model) {
            this.selectedBackend = backend;
            this.selectedModel = model;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            this.lastRequest = request;
            this.requests.add(request);
            chatCalls.incrementAndGet();
            return Stream.of(TokenChunk.of("reply"), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class LocallyClosedStreamResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final CountDownLatch retryStarted = new CountDownLatch(1);
        private final AtomicBoolean firstStreamClosed = new AtomicBoolean();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int call = chatCalls.incrementAndGet();
            if (call > 1) {
                retryStarted.countDown();
                return Stream.of(TokenChunk.of("retry-output"), TokenChunk.eos());
            }
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private boolean emitted;

                @Override
                public boolean hasNext() {
                    if (!emitted) return true;
                    while (!firstStreamClosed.get()) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                    throw new dev.talos.spi.EngineException.Transient(
                            "Stream read aborted during generation",
                            new java.io.IOException("stream closed by Talos"),
                            408);
                }

                @Override
                public TokenChunk next() {
                    emitted = true;
                    return TokenChunk.of("partial");
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
                    .onClose(() -> firstStreamClosed.set(true));
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ExternalTransientThenSuccessResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int call = chatCalls.incrementAndGet();
            if (call == 1) {
                Iterator<TokenChunk> iterator = new Iterator<>() {
                    @Override
                    public boolean hasNext() {
                        throw new dev.talos.spi.EngineException.Transient(
                                "temporary stream transport failure",
                                new java.io.IOException("external socket timeout"),
                                408);
                    }

                    @Override
                    public TokenChunk next() {
                        throw new java.util.NoSuchElementException();
                    }
                };
                return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
            }
            return Stream.of(TokenChunk.of("retry-ok"), TokenChunk.eos());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ExternalTransientAfterVisibleOutputResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int call = chatCalls.incrementAndGet();
            if (call > 1) {
                return Stream.of(TokenChunk.of("retry-output"), TokenChunk.eos());
            }
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private boolean emitted;

                @Override
                public boolean hasNext() {
                    if (!emitted) return true;
                    throw new dev.talos.spi.EngineException.Transient(
                            "external stream dropped after visible output",
                            new java.io.IOException("connection reset after prefix"),
                            408);
                }

                @Override
                public TokenChunk next() {
                    emitted = true;
                    return TokenChunk.of("prefix");
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class TextToolCallThenRambleResolver implements LlmEngineResolver {
        private final AtomicInteger chunksConsumed = new AtomicInteger();
        private final AtomicBoolean streamClosed = new AtomicBoolean();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private final List<String> chunks = List.of(
                        "I will write it now.\n",
                        """
                                ```json
                                {"name":"talos.write_file","arguments":{"path":"index.html","content":"<h1>Hello</h1>"}}
                                ```
                                """,
                        "trailing ramble that should not be consumed");
                private int index;

                @Override
                public boolean hasNext() {
                    return index < chunks.size();
                }

                @Override
                public TokenChunk next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    chunksConsumed.incrementAndGet();
                    return TokenChunk.of(chunks.get(index++));
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false)
                    .onClose(() -> streamClosed.set(true));
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class OrdinaryJsonFenceThenAnswerResolver implements LlmEngineResolver {
        private final AtomicInteger chunksConsumed = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return Stream.of(
                    TokenChunk.of("Here is package.json:\n"),
                    TokenChunk.of("```json\n{\"name\":\"demo-app\",\"version\":\"1.0.0\"}\n```\n"),
                    TokenChunk.of("done after example"),
                    TokenChunk.eos()
            ).peek(ignored -> chunksConsumed.incrementAndGet());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class MixedNativeAndTextToolResolver implements LlmEngineResolver {
        private final AtomicInteger chunksConsumed = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call_write",
                            "talos.write_file",
                            java.util.Map.of("path", "index.html", "content", "<h1>Hello</h1>")))),
                    TokenChunk.of("```json\n{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"extra.txt\",\"content\":\"x\"}}\n```\n"),
                    TokenChunk.of("tail after native call"),
                    TokenChunk.eos()
            ).peek(ignored -> chunksConsumed.incrementAndGet());
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class LengthFinishReasonResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return Stream.of(TokenChunk.of("partial answer"), TokenChunk.eos("length"));
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
