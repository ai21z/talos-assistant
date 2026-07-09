package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.NoSuchElementException;
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
        assertTrue(result.aborted(),
                "a transport abort after partial output must carry abort metadata");
        assertTrue(result.text().contains("[turn aborted"), result.text());
        assertTrue(result.text().contains("prefix"),
                "the partial output must be preserved in the aborted result");
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
        assertFalse(result.aborted(), "a length-capped completion is not an abort");
    }

    @Test
    void capped_required_malformed_tool_arguments_retry_clears_the_cap() {
        CapTruncationResolver resolver = new CapTruncationResolver(true);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertTrue(result.hasToolCalls(), "uncapped retry must recover the truncated tool call");
        assertEquals(List.of(1024, 0), resolver.capsSeen,
                "cap-truncated tool arguments must retry exactly once with the cap lifted");
    }

    @Test
    void capped_required_length_without_tool_calls_retries_uncapped() {
        CapTruncationResolver resolver = new CapTruncationResolver(false);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertTrue(result.hasToolCalls(), "uncapped retry must produce the required tool call");
        assertEquals(List.of(1024, 0), resolver.capsSeen);
    }

    @Test
    void capped_length_with_complete_text_tool_call_does_not_retry() {
        CompleteTextToolCallResolver resolver = new CompleteTextToolCallResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertEquals(1, resolver.calls.get(),
                "a complete text-form tool call must not trigger the uncapped retry");
        assertTrue(result.text().contains("talos.write_file"), result.text());
    }

    @Test
    void capped_auto_chat_length_does_not_retry() {
        CapTruncationResolver resolver = new CapTruncationResolver(false);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("explain")),
                5_000L,
                List.of(),
                ChatRequestControls.defaults().withMaxOutputTokens(512));

        assertEquals(List.of(512), resolver.capsSeen,
                "capped chat answers without a tool obligation must not burn an uncapped retry");
        assertTrue(result.outputLimitReached(), result.finishReason());
    }

    @Test
    void no_first_token_hang_fails_at_the_wall_clock_budget_when_idle_exceeds_it() {
        // Contract from T988: the wall clock is the no-progress budget before
        // the first token. The run()-owned heartbeat prime must not count as
        // progress, even when llm_idle_ms is configured far above the budget.
        BlockingNoChunkResolver resolver = new BlockingNoChunkResolver();
        Config config = engineConfig();
        Object limitsBlock = config.data.computeIfAbsent("limits", ignored -> new LinkedHashMap<String, Object>());
        if (limitsBlock instanceof java.util.Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            java.util.Map<String, Object> limits = (java.util.Map<String, Object>) map;
            limits.put("llm_idle_ms", 30_000L);
        }
        LlmClient client = new LlmClient(config, resolver);

        long started = System.currentTimeMillis();
        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("hello")), 400L);
        long elapsed = System.currentTimeMillis() - started;

        assertTrue(result.text().contains("wall-clock"),
                "a call with zero chunks must abort with the wall-clock reason, got: " + result.text());
        assertTrue(elapsed < 10_000L,
                "the abort must happen at the wall-clock budget, not the idle limit; took " + elapsed + " ms");
        resolver.release();
    }

    private static final class BlockingNoChunkResolver implements LlmEngineResolver {
        private final CountDownLatch release = new CountDownLatch(1);

        void release() {
            release.countDown();
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                @Override
                public boolean hasNext() {
                    try {
                        release.await(20, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return false;
                }

                @Override
                public TokenChunk next() {
                    throw new NoSuchElementException();
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static List<ToolSpec> writeToolSpecs() {
        return List.of(new ToolSpec(
                "talos.write_file",
                "Write a file",
                "{\"type\":\"object\"}"));
    }

    private static ChatRequestControls cappedRequiredControls(int cap) {
        return new ChatRequestControls(
                ToolChoiceMode.REQUIRED,
                "talos.write_file",
                ResponseFormatMode.TEXT,
                "",
                List.of()).withMaxOutputTokens(cap);
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

    private static final class CapTruncationResolver implements LlmEngineResolver {
        final List<Integer> capsSeen = new ArrayList<>();
        private final boolean throwMalformed;

        CapTruncationResolver(boolean throwMalformed) {
            this.throwMalformed = throwMalformed;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 0);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int cap = request.controls == null ? -1 : request.controls.maxOutputTokens();
            capsSeen.add(cap);
            if (cap > 0) {
                if (throwMalformed) {
                    throw new EngineException.MalformedResponse(
                            "compat chat stream tool arguments",
                            "{\"path\": \"index.html\", \"content\": \"<h1>tru");
                }
                return Stream.of(
                        TokenChunk.of("Sure, writing the file now {\"name\": \"talos.wri"),
                        TokenChunk.eos("length"));
            }
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call_recovered",
                            "talos.write_file",
                            java.util.Map.of("path", "index.html", "content", "<h1>done</h1>")))),
                    TokenChunk.eos("stop"));
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class CompleteTextToolCallResolver implements LlmEngineResolver {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            calls.incrementAndGet();
            return Stream.of(
                    TokenChunk.of("""
                            ```json
                            {"name":"talos.write_file","arguments":{"path":"a.txt","content":"x"}}
                            ```
                            """),
                    TokenChunk.eos("length"));
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
