package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.runtime.ToolCallParser;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
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
import java.util.Map;
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
        ExternalTransientAfterTextResolver resolver =
                new ExternalTransientAfterTextResolver("prefix");
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
    void external_stream_transient_after_buffered_retained_output_does_not_retry() {
        ExternalTransientAfterTextResolver resolver =
                new ExternalTransientAfterTextResolver("buffered-prefix");
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("buffer then drop")),
                5_000L,
                List.of());

        assertEquals(1, resolver.chatCalls.get(),
                "retry must not replace a retained partial provider response");
        assertTrue(result.aborted(),
                "a transport abort after retained buffered output must remain an abort");
        assertEquals(ProviderResponseCapture.Completion.RUNTIME_ABORT, result.completion());
        assertEquals(
                ProviderResponseCapture.AbortOrigin.PROVIDER_TRANSPORT,
                result.abortOrigin());
        assertTrue(result.text().contains("buffered-prefix"), result.text());
        assertFalse(result.text().contains("retry-output"), result.text());
        assertTrue(result.toolCalls().isEmpty());
    }

    @Test
    void external_stream_transient_after_safe_prose_and_incomplete_protocol_aborts_without_authority() {
        String partial = "Visible prefix.\n```json\n"
                + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"a.txt\",\"content\":\"";
        ExternalTransientAfterTextResolver resolver =
                new ExternalTransientAfterTextResolver(partial);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs());

        assertEquals(1, resolver.chatCalls.get());
        assertTrue(result.aborted(), result.text());
        assertTrue(result.text().contains("Visible prefix."), result.text());
        assertFalse(result.text().contains("talos.write_file"), result.text());
        assertFalse(result.text().contains("```json"), result.text());
        assertFalse(result.text().contains("retry-output"), result.text());
        assertTrue(result.toolCalls().isEmpty());
        assertTrue(ToolCallParser.parse(result.text()).isEmpty(), result.text());
    }

    @Test
    void external_stream_transient_after_complete_protocol_only_output_may_retry_without_authority() {
        String protocolOnly = """
                ```json
                {"name":"talos.write_file","arguments":{"path":"a.txt","content":"x"}}
                ```
                """;
        ExternalTransientAfterTextResolver resolver =
                new ExternalTransientAfterTextResolver(protocolOnly);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs());

        assertEquals(2, resolver.chatCalls.get(),
                "withheld non-authoritative protocol does not create a retry barrier");
        assertEquals("retry-output", result.text());
        assertTrue(ToolCallParser.parse(result.text()).isEmpty(), result.text());
        assertFalse(result.text().contains("talos.write_file"), result.text());
    }

    @Test
    void external_stream_transient_after_incomplete_protocol_only_output_may_retry_without_authority() {
        String protocolOnly = "```json\n"
                + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"a.txt\",\"content\":\"";
        ExternalTransientAfterTextResolver resolver =
                new ExternalTransientAfterTextResolver(protocolOnly);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs());

        assertEquals(2, resolver.chatCalls.get(),
                "withheld incomplete protocol does not create a retry barrier");
        assertEquals("retry-output", result.text());
        assertTrue(result.toolCalls().isEmpty());
        assertTrue(ToolCallParser.parse(result.text()).isEmpty(), result.text());
        assertFalse(result.text().contains("talos.write_file"), result.text());
    }

    @Test
    void streaming_protocol_only_transient_retries_without_displaying_complete_or_incomplete_authority() {
        List<String> protocolOnlyAttempts = List.of(
                """
                        ```json
                        {"name":"talos.write_file","arguments":{"path":"a.txt","content":"x"}}
                        ```
                        """,
                "```json\n"
                        + "{\"name\":\"talos.write_file\",\"arguments\":{\"path\":\"a.txt\",\"content\":\"");

        for (String protocolOnly : protocolOnlyAttempts) {
            ExternalTransientAfterTextResolver resolver =
                    new ExternalTransientAfterTextResolver(protocolOnly);
            LlmClient client = new LlmClient(engineConfig(), resolver);
            StringBuilder displayed = new StringBuilder();

            LlmClient.StreamResult result = client.chatStreamFull(
                    List.of(ChatMessage.user("write the file")),
                    displayed::append,
                    5_000L,
                    writeToolSpecs());

            assertEquals(2, resolver.chatCalls.get(), protocolOnly);
            assertEquals("retry-output", result.text(), protocolOnly);
            assertEquals("retry-output", displayed.toString(), protocolOnly);
            assertTrue(result.toolCalls().isEmpty(), protocolOnly);
            assertTrue(ToolCallParser.parse(result.text()).isEmpty(), result.text());
            assertFalse(displayed.toString().contains("talos.write_file"), displayed.toString());
        }
    }

    @Test
    void deferred_capped_transient_discards_unselected_partial_before_retry() {
        ExternalTransientAfterTextResolver resolver =
                new ExternalTransientAfterTextResolver("discarded-first-attempt");
        LlmClient client = new LlmClient(engineConfig(), resolver);
        StringBuilder displayed = new StringBuilder();

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("write the file")),
                displayed::append,
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertEquals(2, resolver.chatCalls.get());
        assertEquals("retry-output", result.text());
        assertEquals("retry-output", displayed.toString());
        assertFalse(displayed.toString().contains("discarded-first-attempt"), displayed.toString());
    }

    @Test
    void native_tool_only_transient_discards_unsettled_call_before_retry() {
        ExternalTransientAfterNativeToolResolver resolver =
                new ExternalTransientAfterNativeToolResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs());

        assertEquals(2, resolver.chatCalls.get());
        assertEquals("retry-output", result.text());
        assertTrue(result.toolCalls().isEmpty(),
                "a native call without provider terminal settlement must not survive retry");
    }

    @Test
    void whitespace_retry_barrier_distinguishes_delivered_buffered_and_deferred_output() {
        String whitespace = " \r\n\t";

        ExternalTransientAfterTextResolver streamingResolver =
                new ExternalTransientAfterTextResolver(whitespace);
        LlmClient streamingClient = new LlmClient(engineConfig(), streamingResolver);
        StringBuilder displayed = new StringBuilder();
        LlmClient.StreamResult streaming = streamingClient.chatStreamFull(
                List.of(ChatMessage.user("stream whitespace")),
                displayed::append,
                5_000L,
                List.of());
        assertEquals(1, streamingResolver.chatCalls.get());
        assertFalse(displayed.isEmpty(),
                "at least one stable whitespace byte must reach the display retry barrier");
        assertTrue(displayed.toString().isBlank(), displayed.toString());
        assertTrue(whitespace.startsWith(displayed.toString()), displayed.toString());
        assertTrue(streaming.aborted(), streaming.text());
        assertFalse(streaming.text().contains("retry-output"), streaming.text());

        ExternalTransientAfterTextResolver bufferedResolver =
                new ExternalTransientAfterTextResolver(whitespace);
        LlmClient bufferedClient = new LlmClient(engineConfig(), bufferedResolver);
        LlmClient.StreamResult buffered = bufferedClient.chatFull(
                List.of(ChatMessage.user("buffer whitespace")),
                5_000L,
                List.of());
        assertEquals(2, bufferedResolver.chatCalls.get());
        assertEquals("retry-output", buffered.text());

        ExternalTransientAfterTextResolver deferredResolver =
                new ExternalTransientAfterTextResolver(whitespace);
        LlmClient deferredClient = new LlmClient(engineConfig(), deferredResolver);
        StringBuilder deferredDisplay = new StringBuilder();
        LlmClient.StreamResult deferred = deferredClient.chatStreamFull(
                List.of(ChatMessage.user("write the file")),
                deferredDisplay::append,
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));
        assertEquals(2, deferredResolver.chatCalls.get());
        assertEquals("retry-output", deferred.text());
        assertEquals("retry-output", deferredDisplay.toString());
    }

    @Test
    void user_cancel_before_native_tool_chunk_returns_abort_without_tool_or_retry() {
        NativeToolThenRetryResolver resolver = new NativeToolThenRetryResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        AtomicBoolean cancelled = new AtomicBoolean(true);
        client.setCancelSupplier(cancelled::get);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("create interrupted.txt")),
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertTrue(result.aborted(), "observed user cancellation must be a terminal abort");
        assertTrue(result.text().contains("interrupted"), result.text());
        assertTrue(result.toolCalls().isEmpty(), "cancelled tool calls must never reach approval");
        assertEquals(1, resolver.chatCalls.get(),
                "user cancellation must not start a retry or obligation generation");
    }

    @Test
    void user_cancel_closes_no_chunk_stream_before_wall_clock_budget() throws Exception {
        CancelableNoChunkResolver resolver = new CancelableNoChunkResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        client.setCancelSupplier(cancelled::get);
        Thread cancelThread = Thread.ofPlatform().start(() -> {
            try {
                assertTrue(resolver.started.await(2, TimeUnit.SECONDS), "stream did not start");
                cancelled.set(true);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        long startedAt = System.nanoTime();
        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("wait for a slow model")),
                5_000L,
                List.of());
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
        cancelThread.join(2_000L);

        assertTrue(result.aborted(), result.text());
        assertTrue(result.text().contains("interrupted"), result.text());
        assertTrue(elapsedMillis < 2_000L,
                "cancel must not wait for the 5s wall-clock budget; elapsed=" + elapsedMillis);
        assertTrue(resolver.streamClosed.get(), "cancel watchdog must close the active stream");
        assertEquals(1, resolver.chatCalls.get(), "cancelled request must not retry");
    }

    @Test
    void deterministicCancellationKeepsLegacyCallbackAndReturnedPartialIdentical()
            throws Exception {
        for (String firstChunk : List.of(
                "visible o",
                "Before\n```json\n{\"name\":\"talos.write_file\",")) {
            AtomicBoolean cancelled = new AtomicBoolean();
            CancelAfterFirstChunkResolver resolver =
                    new CancelAfterFirstChunkResolver(firstChunk, cancelled);
            LlmClient client = new LlmClient(engineConfig(), resolver);
            StringBuilder emitted = new StringBuilder();

            String result = client.chatStream(
                    List.of(ChatMessage.user("cancel deterministically")),
                    emitted::append,
                    Duration.ofSeconds(5),
                    cancelled::get);

            assertEquals(result, emitted.toString(), firstChunk);
            assertTrue(ToolCallParser.parse(result).isEmpty(), result);
            assertFalse(result.contains("talos.write_file"), result);
        }
    }

    @Test
    void deterministicCancellationFlushesOnlySafeProviderPartialBeforeAbortMarker() {
        for (String firstChunk : List.of(
                "visible o",
                "Before\n```json\n{\"name\":\"talos.write_file\",")) {
            AtomicBoolean cancelled = new AtomicBoolean();
            CancelAfterFirstChunkResolver resolver =
                    new CancelAfterFirstChunkResolver(firstChunk, cancelled);
            LlmClient client = new LlmClient(engineConfig(), resolver);
            client.setCancelSupplier(cancelled::get);
            StringBuilder emitted = new StringBuilder();

            LlmClient.StreamResult result = client.chatStreamFull(
                    List.of(ChatMessage.user("cancel deterministically")),
                    emitted::append,
                    5_000L,
                    List.of());

            assertTrue(result.aborted(), result.text());
            assertEquals(
                    ProviderResponseCapture.Completion.RUNTIME_ABORT,
                    result.completion());
            assertEquals(
                    ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL,
                    result.abortOrigin());
            assertTrue(
                    result.text().startsWith(
                            emitted + System.lineSeparator()),
                    () -> "callback/partial mismatch: callback=" + emitted
                            + " result=" + result.text());
            assertTrue(ToolCallParser.parse(result.text()).isEmpty(), result.text());
            assertFalse(result.text().contains("talos.write_file"), result.text());
        }
    }

    @Test
    void json_text_tool_call_requires_provider_eos_and_failsClosedWithoutIt() {
        TextToolCallThenRambleResolver resolver = new TextToolCallThenRambleResolver(false);
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("create the file")),
                null,
                5_000L,
                List.of());

        assertEquals(3, resolver.chunksConsumed.get(),
                "JSON cannot acquire terminal authority before provider EOS");
        assertTrue(resolver.streamClosed.get(), "natural exhaustion must close the provider stream");
        assertEquals(
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS,
                result.completion());
        assertFalse(result.text().contains("talos.write_file"), result.text());
        assertTrue(result.text().contains("trailing provider text"), result.text());
        assertTrue(result.normalized(), "non-authoritative protocol must be removed");
    }

    @Test
    void jsonTextToolCallWaitsForProviderEosAndPreservesFollowingProviderText() {
        TextToolCallThenRambleResolver resolver = new TextToolCallThenRambleResolver(true);
        LlmClient client = new LlmClient(engineConfig(), resolver);
        StringBuilder streamed = new StringBuilder();

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("create the file")),
                streamed::append,
                5_000L,
                List.of());

        assertEquals(4, resolver.chunksConsumed.get(),
                "three text chunks and the provider terminal must be consumed");
        assertEquals(
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                result.completion());
        assertEquals(resolver.providerText, result.text());
        assertEquals(result.text(), streamed.toString());
        assertEquals(1, ToolCallParser.parse(result.text()).size(), result.text());
        assertFalse(result.normalized());
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
    void streaming_capped_required_retry_does_not_emit_discarded_first_generation_prose() {
        CapTruncationResolver resolver = new CapTruncationResolver(false);
        LlmClient client = new LlmClient(engineConfig(), resolver);
        StringBuilder emitted = new StringBuilder();

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("write the file")),
                emitted::append,
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertTrue(result.hasToolCalls(), "uncapped retry must recover the required tool call");
        assertEquals(List.of(1024, 0), resolver.capsSeen);
        assertFalse(emitted.toString().contains("Sure, writing the file now"),
                "discarded capped generation prose must not be streamed before the uncapped retry: " + emitted);
    }

    @Test
    void streaming_capped_required_nonretry_answer_is_emitted_to_visible_sink() {
        CappedShortAnswerResolver resolver = new CappedShortAnswerResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);
        StringBuilder emitted = new StringBuilder();

        LlmClient.StreamResult result = client.chatStreamFull(
                List.of(ChatMessage.user("write the file")),
                emitted::append,
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertEquals("I cannot safely write that without more detail.", result.text());
        assertEquals(result.text(), emitted.toString(),
                "when a capped first generation does not qualify for retry, deferred text must be flushed visibly");
        assertEquals(List.of(1024), resolver.capsSeen);
    }

    @Test
    void cappedLengthTextToolCallIsNonAuthoritativeAndRetriesOnceUncapped() {
        CompleteTextToolCallResolver resolver = new CompleteTextToolCallResolver();
        LlmClient client = new LlmClient(engineConfig(), resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("write the file")),
                5_000L,
                writeToolSpecs(),
                cappedRequiredControls(1024));

        assertEquals(2, resolver.calls.get(),
                "provider length cannot authorize JSON; the bounded cap-lift retry must run once");
        assertEquals(List.of(1024, 0), resolver.capsSeen);
        assertEquals(1, ToolCallParser.parse(result.text()).size(), result.text());
        assertTrue(result.text().contains("talos.write_file"), result.text());
        assertFalse(result.outputLimitReached());
        assertEquals(
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                result.completion());
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

    @Test
    void nativeToolChunksCountAsGenerationProgressForTheWallClockBudget() {
        NativeToolProgressResolver resolver = new NativeToolProgressResolver();
        Config config = engineConfig();
        LinkedHashMap<String, Object> limits = new LinkedHashMap<>();
        limits.put("llm_idle_ms", 4_000L);
        config.data.put("limits", limits);
        LlmClient client = new LlmClient(config, resolver);

        LlmClient.StreamResult result = client.chatFull(
                List.of(ChatMessage.user("inspect with a native tool")),
                1_000L,
                List.of());

        assertFalse(result.aborted(), result.text());
        assertTrue(result.hasToolCalls(), "native proposal must survive beyond the initial wall-clock deadline");
        assertEquals("talos.read_file", result.toolCalls().getFirst().name());
    }

    @Test
    void bufferedJsonStillFeedsTheRepetitionBreakerWithoutReachingDisplay() {
        String pattern = "0123456789abcdefghijklmnopqrstuvwxyzWXYZ!@#$%^&*";
        assertEquals(48, pattern.length(), "fixture must match the default probe width");
        List<String> chunks = new ArrayList<>();
        chunks.add("```json\n{\"name\":\"talos.write_file\",\"arguments\":{"
                + "\"path\":\"x\",\"content\":\"");
        for (int i = 0; i < 6; i++) {
            chunks.add(pattern);
        }
        BlockingTextChunksResolver resolver = new BlockingTextChunksResolver(chunks);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    StringBuilder visible = new StringBuilder();
                    try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
                        LlmClient.StreamResult result = client.chatStreamFull(
                                List.of(ChatMessage.user("write the file")),
                                visible::append,
                                3_000L,
                                List.of());

                        assertTrue(result.aborted(), result.text());
                        assertEquals("repetition loop", result.abortReason());
                        assertEquals(
                                ProviderResponseCapture.Completion.RUNTIME_ABORT,
                                result.completion());
                        assertEquals("", visible.toString(),
                                "buffered protocol must never reach display");
                        assertFalse(result.text().contains("talos.write_file"), result.text());
                    }
                    assertTrue(resolver.streamClosed.get(),
                            "watchdog must close the blocked provider stream");
                });
    }

    @Test
    void deferredCappedDisplayDoesNotDoubleFeedDecodedBytesToRepetitionAccounting() {
        String pattern = "0123456789abcdefghijklmnopqrstuvwxyzWXYZ!@#$%^&*";
        assertEquals(48, pattern.length(), "fixture must match the default probe width");
        String response = pattern.repeat(3);
        DelayedTerminalTextResolver resolver =
                new DelayedTerminalTextResolver(response, 800L);

        org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> {
                    StringBuilder visible = new StringBuilder();
                    try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
                        LlmClient.StreamResult result = client.chatStreamFull(
                                List.of(ChatMessage.user("write the file")),
                                visible::append,
                                3_000L,
                                writeToolSpecs(),
                                cappedRequiredControls(1_024));

                        assertFalse(result.aborted(), result.text());
                        assertEquals(
                                ProviderResponseCapture.Completion.PROVIDER_EOS,
                                result.completion());
                        assertEquals(response, result.text());
                        assertEquals(response, visible.toString());
                    }
                });
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

    private static final class BlockingTextChunksResolver implements LlmEngineResolver {
        private final List<String> chunks;
        private final AtomicBoolean streamClosed = new AtomicBoolean();

        private BlockingTextChunksResolver(List<String> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void select(String backend, String model) {
        }

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
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    if (index < chunks.size()) return true;
                    while (!streamClosed.get()) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return false;
                }

                @Override
                public TokenChunk next() {
                    if (index >= chunks.size()) {
                        throw new NoSuchElementException();
                    }
                    return TokenChunk.of(chunks.get(index++));
                }
            };
            return StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(iterator, 0),
                            false)
                    .onClose(() -> streamClosed.set(true));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
            streamClosed.set(true);
        }
    }

    private static final class DelayedTerminalTextResolver implements LlmEngineResolver {
        private final String text;
        private final long terminalDelayMs;

        private DelayedTerminalTextResolver(String text, long terminalDelayMs) {
            this.text = text;
            this.terminalDelayMs = terminalDelayMs;
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768, true);
        }

        @Override
        public boolean capabilitiesAuthoritative() {
            return true;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private int state;

                @Override
                public boolean hasNext() {
                    return state < 2;
                }

                @Override
                public TokenChunk next() {
                    if (state == 0) {
                        state = 1;
                        return TokenChunk.of(text);
                    }
                    if (state == 1) {
                        state = 2;
                        try {
                            Thread.sleep(terminalDelayMs);
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return TokenChunk.eos("stop");
                    }
                    throw new NoSuchElementException();
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(iterator, 0),
                    false);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
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
                "",
                ResponseFormatMode.TEXT,
                "",
                List.of())
                .withMaxOutputTokens(cap)
                .withToolAuthorityMode(
                        ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
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

    private static final class NativeToolProgressResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    return index < 2;
                }

                @Override
                public TokenChunk next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    try {
                        Thread.sleep(index == 0 ? 250L : 1_200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new NoSuchElementException("interrupted");
                    }
                    if (index++ == 0) {
                        return TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                                "call-read",
                                "talos.read_file",
                                Map.of("path", "README.md"))));
                    }
                    return TokenChunk.eos("tool_calls");
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(iterator, 0),
                    false);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class NativeToolThenRetryResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            if (chatCalls.incrementAndGet() > 1) {
                return Stream.of(TokenChunk.of("retry-output"), TokenChunk.eos());
            }
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call-1",
                            "talos.write_file",
                            Map.of("path", "interrupted.txt", "content", "unfinished")))),
                    TokenChunk.eos("tool_calls"));
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class CancelableNoChunkResolver implements LlmEngineResolver {
        private final CountDownLatch started = new CountDownLatch(1);
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            chatCalls.incrementAndGet();
            Iterator<TokenChunk> iterator = new Iterator<>() {
                @Override
                public boolean hasNext() {
                    started.countDown();
                    while (!streamClosed.get()) {
                        try {
                            Thread.sleep(10L);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            return false;
                        }
                    }
                    return false;
                }

                @Override
                public TokenChunk next() {
                    throw new NoSuchElementException();
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
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ExternalTransientAfterTextResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final String firstText;

        private ExternalTransientAfterTextResolver(String firstText) {
            this.firstText = firstText;
        }

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
                    return TokenChunk.of(firstText);
                }
            };
            return StreamSupport.stream(Spliterators.spliteratorUnknownSize(iterator, 0), false);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class ExternalTransientAfterNativeToolResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();

        @Override
        public void select(String backend, String model) {
            // no-op
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
                    throw new dev.talos.spi.EngineException.Transient(
                            "external stream dropped after native tool proposal",
                            new java.io.IOException("connection reset after native tool proposal"),
                            408);
                }

                @Override
                public TokenChunk next() {
                    emitted = true;
                    return TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "unsettled-call",
                            "talos.write_file",
                            Map.of("path", "a.txt", "content", "x"))));
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(iterator, 0),
                    false);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class TextToolCallThenRambleResolver implements LlmEngineResolver {
        private final AtomicInteger chunksConsumed = new AtomicInteger();
        private final AtomicBoolean streamClosed = new AtomicBoolean();
        private final boolean includeProviderEos;
        private final List<String> textChunks = List.of(
                "I will write it now.\n",
                """
                        ```json
                        {"name":"talos.write_file","arguments":{"path":"index.html","content":"<h1>Hello</h1>"}}
                        ```
                        """,
                "trailing provider text");
        private final String providerText = String.join("", textChunks);

        private TextToolCallThenRambleResolver(boolean includeProviderEos) {
            this.includeProviderEos = includeProviderEos;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private final List<TokenChunk> chunks = includeProviderEos
                        ? Stream.concat(
                                textChunks.stream().map(TokenChunk::of),
                                Stream.of(TokenChunk.eos("stop"))).toList()
                        : textChunks.stream().map(TokenChunk::of).toList();
                private int index;

                @Override
                public boolean hasNext() {
                    return index < chunks.size();
                }

                @Override
                public TokenChunk next() {
                    if (!hasNext()) throw new java.util.NoSuchElementException();
                    chunksConsumed.incrementAndGet();
                    return chunks.get(index++);
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

    private static final class CancelAfterFirstChunkResolver implements LlmEngineResolver {
        private final String firstChunk;
        private final AtomicBoolean cancelled;

        private CancelAfterFirstChunkResolver(
                String firstChunk,
                AtomicBoolean cancelled) {
            this.firstChunk = firstChunk;
            this.cancelled = cancelled;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Iterator<TokenChunk> iterator = new Iterator<>() {
                private int index;

                @Override
                public boolean hasNext() {
                    if (index > 0) {
                        cancelled.set(true);
                    }
                    return index < 2;
                }

                @Override
                public TokenChunk next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    return TokenChunk.of(index++ == 0 ? firstChunk : "must not assemble");
                }
            };
            return StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(iterator, 0), false);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
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
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportHandoff.mark(handoff);
            return chatStream(request);
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class CappedShortAnswerResolver implements LlmEngineResolver {
        final List<Integer> capsSeen = new ArrayList<>();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            int cap = request.controls == null ? -1 : request.controls.maxOutputTokens();
            capsSeen.add(cap);
            return Stream.of(
                    TokenChunk.of("I cannot safely write that without more detail."),
                    TokenChunk.eos("stop"));
        }

        @Override
        public void close() {
            // no-op
        }
    }

    private static final class CompleteTextToolCallResolver implements LlmEngineResolver {
        final AtomicInteger calls = new AtomicInteger();
        final List<Integer> capsSeen = new ArrayList<>();

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            calls.incrementAndGet();
            int cap = request.controls == null ? -1 : request.controls.maxOutputTokens();
            capsSeen.add(cap);
            return Stream.of(
                    TokenChunk.of("""
                            ```json
                            {"name":"talos.write_file","arguments":{"path":"a.txt","content":"x"}}
                            ```
                            """),
                    TokenChunk.eos(cap > 0 ? "length" : "stop"));
        }

        @Override
        public void close() {
            // no-op
        }
    }
}
