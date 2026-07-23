package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ProviderProcessLaunchReceipt;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportAttempt;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ProviderTransportTokenStream;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.spi.EngineException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmClientProviderProvenanceTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void clearCapture() {
        ProviderResponseCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void completionVocabularyDistinguishesProviderAndRuntimeStops() {
        assertCompletion(
                List.of(TokenChunk.of("done"), TokenChunk.eos("stop")),
                1_000,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false);
        assertCompletion(
                List.of(TokenChunk.of("partial"), TokenChunk.eos("length")),
                1_000,
                ProviderResponseCapture.Completion.PROVIDER_LENGTH,
                false);
        assertCompletion(
                List.of(TokenChunk.of("abcdef"), TokenChunk.eos("stop")),
                4,
                ProviderResponseCapture.Completion.RUNTIME_SAFE_CAP,
                true);
        assertCompletion(
                List.of(TokenChunk.of("stream ended without sentinel")),
                1_000,
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS,
                false);
        assertCompletion(
                List.of(TokenChunk.of("socket closed"), TokenChunk.transportEof("stop")),
                1_000,
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS,
                false);
        assertCompletion(
                List.of(TokenChunk.of("filtered"), TokenChunk.eos("content_filter")),
                1_000,
                ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP,
                false);
        assertCompletion(
                List.of(TokenChunk.of("""
                        ```json
                        {"name":"talos.read_file","arguments":{"path":"README.md"}}
                        ```
                        """), TokenChunk.of("unconsumed tail")),
                1_000,
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS,
                false);
        assertCompletion(
                List.of(
                        TokenChunk.of("<tool_call>{\"name\":\"talos.read_file\","
                                + "\"arguments\":{\"path\":\"README.md\"}}</tool_call>"),
                        TokenChunk.of("unconsumed XML tail")),
                1_000,
                ProviderResponseCapture.Completion.RUNTIME_LEGACY_TEXT_STOP,
                false);
        assertCompletion(
                List.of(TokenChunk.of("not authenticated"), new TokenChunk(
                        "", true, null, "stop", TokenChunk.TerminalSignal.NONE)),
                1_000,
                ProviderResponseCapture.Completion.STREAM_WITHOUT_EOS,
                false);
        assertCompletion(
                List.of(TokenChunk.of("missing proposal"), new TokenChunk(
                        "", true, null, "tool_calls", TokenChunk.TerminalSignal.PROVIDER_SENTINEL)),
                1_000,
                ProviderResponseCapture.Completion.UNRECOGNIZED_PROVIDER_STOP,
                false);
    }

    @Test
    void cancellationIsRuntimeAbortAndCannotBecomeProviderTerminal() throws Exception {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(TokenChunk.of("never consumed"), TokenChunk.eos())))) {
            String answer = client.chatStream(
                    "system",
                    "user",
                    List.of(),
                    null,
                    Duration.ofSeconds(5),
                    () -> true);
            assertEquals("", answer);
        }

        assertTrue(capture.responses.isEmpty(), "an abandoned call is not a provider response");
        assertEquals(
                List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                capture.abandonments);
        assertOperatorCancellationOwnsStreamSettlement(capture);
    }

    @Test
    void emptyTextStreamCannotHideInitialOperatorCancellation() throws Exception {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        try (LlmClient client = new LlmClient(
                engineConfig(1_000), new FixedResolver(List.of()))) {
            String answer = client.chatStream(
                    "system",
                    "user",
                    List.of(),
                    null,
                    Duration.ofSeconds(5),
                    () -> true);
            assertEquals("", answer);
        }

        assertTrue(capture.responses.isEmpty());
        assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                capture.abandonments);
        assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                capture.generationAbandonments);
        assertOperatorCancellationOwnsStreamSettlement(capture);
    }

    @Test
    void structuredWorkerCancellationIsRecordedBeforeTrackedStreamClose() {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        AtomicInteger cancellationChecks = new AtomicInteger();
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(TokenChunk.of("unconsumed"), TokenChunk.eos())))) {
            client.setCancelSupplier(() -> cancellationChecks.incrementAndGet() >= 2);
            LlmClient.StreamResult result = client.chatFull(
                    List.of(ChatMessage.user("inspect")), 5_000L);

            assertTrue(result.aborted());
            assertEquals(ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL,
                    result.abortOrigin());
        }

        assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                capture.abandonments);
        assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                capture.generationAbandonments);
        assertOperatorCancellationOwnsStreamSettlement(capture);
    }

    @Test
    void plainTextApiDoesNotInventCancellationFromAnUnownedThreadInterrupt() {
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(TokenChunk.of("complete"), TokenChunk.eos())))) {
            Thread.currentThread().interrupt();
            try {
                assertEquals("complete", client.chat("system", "user", List.of()));
                assertTrue(Thread.currentThread().isInterrupted(),
                        "plain generation must not consume an unrelated interrupt flag");
            } finally {
                Thread.interrupted();
            }
        }
    }

    @Test
    void workerInterruptDoesNotClaimCallerCausality() {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        try (LlmClient client = new LlmClient(
                engineConfig(1_000), new AmbientInterruptResolver())) {
            LlmClient.StreamResult result = client.chatFull(
                    List.of(ChatMessage.user("inspect")), 5_000L);

            assertTrue(result.aborted());
            assertTrue(result.text().contains("[turn aborted: interrupted]"), result.text());
            assertTrue(result.toolCalls().isEmpty());
            assertEquals(ProviderResponseCapture.Completion.RUNTIME_ABORT, result.completion());
            assertEquals(
                    ProviderResponseCapture.AbortOrigin.RUNTIME_THREAD_INTERRUPT,
                    result.abortOrigin());
        }
        assertTrue(capture.responses.isEmpty(),
                "a worker-interrupted generation is not a completed provider response");
        assertEquals(1, capture.abandonments.size());
        assertEquals("INTERRUPTION_CAUSE_UNPROVEN",
                capture.abandonments.getFirst().name());
    }

    @Test
    void backgroundAndHarnessSourcesAreMechanicallyDistinctFromForegroundProvider() {
        Capture background = new Capture();
        ProviderResponseCapture.begin(background);
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(TokenChunk.of("summary"), TokenChunk.eos())))) {
            client.chatPlainBackgroundMaintenance(
                    "You are a conversation summarizer for a developer CLI tool.",
                    "summarize");
        }
        assertEquals(
                ProviderResponseCapture.Source.BACKGROUND_PROVIDER,
                background.onlyResponse().source());
        assertFalse(background.onlyReceipt().providerTerminalEligible());

        Capture harness = new Capture();
        ProviderResponseCapture.begin(harness);
        try (LlmClient client = LlmClient.scripted("fixture answer")) {
            client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        assertEquals(
                ProviderResponseCapture.Source.SCRIPTED_HARNESS,
                harness.onlyResponse().source());
        assertFalse(harness.onlyResponse().providerBacked());
        assertFalse(harness.onlyReceipt().providerTerminalEligible());
    }

    @Test
    void legacyResolverAnswerIsPreservedButCannotClaimObservedProviderProvenance() {
        beginTrace("trace-legacy-unobserved-provider");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000), new LegacyOnlyResolver())) {
            LlmClient.StreamResult result = client.chatFull(
                    List.of(ChatMessage.user("inspect")));
            assertEquals("legacy answer", result.text());
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.text());
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertTrue(events(trace, "GENERATION_REQUEST_DISPATCHED").isEmpty());
        TurnTraceEvent response = event(trace, "GENERATION_RESPONSE_CAPTURED");
        assertEquals(false, response.data().get("providerBacked"));
        assertEquals(false, response.data().get("completedNormally"));
        TurnTraceEvent completion = event(trace, "GENERATION_COMPLETED");
        assertEquals(false, completion.data().get("providerTerminalEligible"));
        assertEquals(false, completion.data().get("providerToolCallEligible"));
        assertEquals(
                "NONE",
                event(trace, "TERMINAL_ANSWER_SELECTED").data().get("origin"));
    }

    @Test
    void failedAttemptEmitsClosedFailureAndNoStaleResponseReceipt() {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        try (LlmClient client = LlmClient.scriptedFailure(
                new RuntimeException("provider-controlled SECRET failure"))) {
            assertThrows(
                    RuntimeException.class,
                    () -> client.chatFull(List.of(ChatMessage.user("inspect"))));
        }

        assertTrue(capture.starts.isEmpty(), "a harness failure before dispatch is not a physical attempt");
        assertEquals(
                List.of(ProviderResponseCapture.Failure.HARNESS_EXCEPTION),
                capture.generationFailures);
        assertTrue(capture.responses.isEmpty());
        assertTrue(capture.receipts.isEmpty());
    }

    @Test
    void scriptedHarnessTransientAndContextFailuresRemainHarnessOwned() {
        List<RuntimeException> failures = List.of(
                new EngineException.Transient("scripted transient", 503),
                new EngineException.ContextBudgetExceeded(4_096, 2_048, 3_072, 0));

        for (RuntimeException failure : failures) {
            Capture capture = new Capture();
            ProviderResponseCapture.begin(capture);
            try (LlmClient client = LlmClient.scriptedFailure(failure)) {
                RuntimeException observed = assertThrows(
                        RuntimeException.class,
                        () -> client.chatFull(List.of(ChatMessage.user("inspect"))));
                assertEquals(failure.getClass(), observed.getClass());
            } finally {
                ProviderResponseCapture.clear();
            }

            assertTrue(capture.starts.isEmpty());
            assertTrue(capture.dispatches.isEmpty());
            assertEquals(
                    List.of(ProviderResponseCapture.Failure.HARNESS_EXCEPTION),
                    capture.generationFailures);
        }
    }

    @Test
    void normalizedProviderEosIsNotEligibleForSoleModelAuthorship() {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(
                        TokenChunk.of("<think>hidden</think>visible"),
                        TokenChunk.eos("stop"))))) {
            client.chatFull(List.of(ChatMessage.user("inspect")));
        }

        ProviderResponseCapture.Response response = capture.onlyResponse();
        assertEquals(ProviderResponseCapture.Completion.PROVIDER_EOS, response.completion());
        assertTrue(response.normalized());
        assertFalse(response.completedNormally());
        assertFalse(capture.onlyReceipt().providerTerminalEligible());
    }

    @Test
    void traceNeverPersistsProviderFinishOrFailureProse() throws Exception {
        beginTrace("trace-provider-metadata-redaction");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(
                        TokenChunk.of("<think>SECRET-THOUGHT</think>safe answer"),
                        TokenChunk.eos("SECRET-FINISH-METADATA"))))) {
            LlmClient.StreamResult result = client.chatFull(List.of(ChatMessage.user("inspect")));
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.text());
        }

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        String json = MAPPER.writeValueAsString(trace);
        assertFalse(json.contains("SECRET-FINISH-METADATA"), json);
        assertFalse(json.contains("SECRET-THOUGHT"), json);
        TurnTraceEvent generation = event(trace, "GENERATION_RESPONSE_CAPTURED");
        assertEquals("UNRECOGNIZED_PROVIDER_STOP", generation.data().get("completion"));
        assertEquals(true, generation.data().get("normalized"));
        assertEquals(false, generation.data().get("completedNormally"));
        assertEquals(
                "NONE",
                event(trace, "TERMINAL_ANSWER_SELECTED").data().get("origin"));
    }

    @Test
    void observedTransientAfterSendQuarantinesAndBlocksSameProcessRetry() {
        beginTrace("trace-transient-retry-ledger");
        TransientOnceResolver resolver = new TransientOnceResolver();
        try (LlmClient client = new LlmClient(engineConfig(1_000), resolver)) {
            EngineException.ResponseError blocked = assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chatFull(List.of(ChatMessage.user("inspect"))));
            assertTrue(blocked.getCause() instanceof IllegalStateException);
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        List<TurnTraceEvent> starts = events(trace, "GENERATION_ATTEMPT_STARTED");
        List<TurnTraceEvent> dispatched = events(trace, "GENERATION_REQUEST_DISPATCHED");
        assertEquals(1, starts.size());
        assertEquals(1, dispatched.size());
        assertEquals(1, events(trace, "GENERATION_ATTEMPT_FAILED").size());
        assertTrue(events(trace, "GENERATION_RESPONSE_CAPTURED").isEmpty());
        assertTrue(events(trace, "GENERATION_COMPLETED").isEmpty());
        assertEquals(1, starts.get(0).data().get("attemptOrdinal"));
        assertEquals("PRIMARY", starts.get(0).data().get("attemptKind"));
        assertEquals(
                "PROVIDER_TRANSIENT",
                events(trace, "GENERATION_ATTEMPT_FAILED").getFirst().data().get("failure"));
        assertTransportSettlement(
                trace, "FAILED_BEFORE_HEADERS", true);
    }

    @Test
    void predispatchTransientRemainsRetryableWithoutForgingAProviderDispatch() {
        beginTrace("trace-predispatch-transient-retry");
        PreDispatchTransientOnceResolver resolver = new PreDispatchTransientOnceResolver();
        try (LlmClient client = new LlmClient(engineConfig(1_000), resolver)) {
            LlmClient.StreamResult result = client.chatFull(List.of(ChatMessage.user("inspect")));
            assertEquals("recovered", result.text());
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(2, resolver.calls);
        assertEquals(2, events(trace, "GENERATION_ATTEMPT_STARTED").size());
        assertEquals(1, events(trace, "GENERATION_REQUEST_DISPATCHED").size());
        assertEquals(
                "RUNTIME_PRE_DISPATCH",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(1, events(trace, "GENERATION_COMPLETED").size());
        assertTrue(events(trace, "GENERATION_FAILED").isEmpty());
    }

    @Test
    void textApiAlsoBlocksRetryAfterObservedUnknownSettlement() {
        beginTrace("trace-text-api-retry-ledger");
        TransientOnceResolver resolver = new TransientOnceResolver();
        try (LlmClient client = new LlmClient(engineConfig(1_000), resolver)) {
            EngineException.ResponseError blocked = assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chat("system", "user", List.of()));
            assertTrue(blocked.getCause() instanceof IllegalStateException);
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> starts = events(trace, "GENERATION_ATTEMPT_STARTED");
        assertEquals(1, starts.size());
        assertEquals(List.of("PRIMARY"), starts.stream()
                .map(candidate -> candidate.data().get("attemptKind")).toList());
        assertEquals(1, events(trace, "GENERATION_ATTEMPT_FAILED").size());
        assertTrue(events(trace, "GENERATION_COMPLETED").isEmpty());
        assertTransportSettlement(trace, "FAILED_BEFORE_HEADERS", true);
    }

    @Test
    void output_cap_lift_is_a_distinct_physical_retry_and_raw_tool_proposal_precedes_completion() {
        beginTrace("trace-output-cap-retry-ledger");
        CapLiftResolver resolver = new CapLiftResolver();
        LlmClient.StreamResult result;
        try (LlmClient client = new LlmClient(engineConfig(4_000), resolver)) {
            result = client.chatFull(
                    List.of(ChatMessage.user("write index.html")),
                    5_000L,
                    List.of(writeSpec()),
                    requiredControls(128));
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertTrue(result.hasToolCalls());
        List<TurnTraceEvent> starts = events(trace, "GENERATION_ATTEMPT_STARTED");
        assertEquals(2, starts.size());
        assertEquals("OUTPUT_CAP_LIFT_RETRY", starts.get(1).data().get("attemptKind"));
        assertEquals("OUTPUT_CAP_TRUNCATION", starts.get(1).data().get("retryReason"));
        List<TurnTraceEvent> responses = events(trace, "GENERATION_RESPONSE_CAPTURED");
        assertEquals(List.of("PROVIDER_LENGTH", "PROVIDER_EOS"), responses.stream()
                .map(candidate -> candidate.data().get("completion")).toList());
        TurnTraceEvent proposal = event(trace, "PROVIDER_TOOL_PROPOSAL_CAPTURED");
        assertEquals("CANONICAL", proposal.data().get("toolNameClassification"));
        assertTrue(String.valueOf(proposal.data().get("toolNameHash")).startsWith("sha256:"));
        assertFalse(proposal.data().containsKey("toolName"));
        assertEquals(responses.get(1).data().get("attemptId"), proposal.data().get("attemptId"));
        assertTrue(trace.events().indexOf(proposal)
                < trace.events().indexOf(event(trace, "GENERATION_COMPLETED")));
        assertFalse(MAPPER.valueToTree(proposal).toString().contains("<h1>private</h1>"));
        assertTrue(events(trace, "GENERATION_TRANSPORT_UNOBSERVED").isEmpty());
        assertEquals(List.of("COMPLETED_PROVIDER_TERMINAL", "COMPLETED_PROVIDER_TERMINAL"),
                events(trace, "PROVIDER_TRANSPORT_LIFECYCLE").stream()
                        .filter(candidate -> "SETTLED".equals(
                                candidate.data().get("transition")))
                        .map(candidate -> candidate.data().get("settlement"))
                        .toList());
    }

    @Test
    void observedMalformedBodyQuarantinesAndBlocksCompatRetry() {
        beginTrace("trace-compat-retry-ledger");
        CompatRetryResolver resolver = new CompatRetryResolver();
        try (LlmClient client = new LlmClient(engineConfig(4_000), resolver)) {
            EngineException.ResponseError blocked = assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chatFull(
                            List.of(ChatMessage.user("write index.html")),
                            5_000L,
                            List.of(writeSpec()),
                            requiredControls(0)));
            assertTrue(blocked.getCause() instanceof IllegalStateException);
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        List<TurnTraceEvent> starts = events(trace, "GENERATION_ATTEMPT_STARTED");
        assertEquals(1, starts.size());
        assertEquals(
                "PROVIDER_MALFORMED_RESPONSE",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(1, resolver.streamingCalls);
        assertEquals(0, resolver.nonStreamingCalls);
        assertTransportSettlement(trace, "FAILED_DURING_BODY", true);
    }

    @Test
    void legacyMalformedStreamStillReceivesCompatRetryWithoutProvenanceUpgrade() {
        beginTrace("trace-legacy-compat-retry");
        LegacyCompatRetryResolver resolver = new LegacyCompatRetryResolver();
        try (LlmClient client = new LlmClient(engineConfig(4_000), resolver)) {
            LlmClient.StreamResult result = client.chatFull(
                    List.of(ChatMessage.user("write index.html")),
                    5_000L,
                    List.of(writeSpec()),
                    requiredControls(0));
            assertTrue(result.hasToolCalls());
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, resolver.streamingCalls);
        assertEquals(1, resolver.nonStreamingCalls);
        assertEquals(2, events(trace, "GENERATION_TRANSPORT_UNOBSERVED").size());
        assertTrue(events(trace, "GENERATION_REQUEST_DISPATCHED").isEmpty());
        assertEquals(
                "TRANSPORT_UNOBSERVED",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(
                false,
                event(trace, "GENERATION_COMPLETED").data()
                        .get("providerToolCallEligible"));
    }

    @Test
    void context_fit_failure_is_runtime_predispatch_with_no_provider_attempt() {
        beginTrace("trace-context-predispatch-failure");
        CountingResolver resolver = new CountingResolver(256);
        Config config = engineConfig(1_000);
        @SuppressWarnings("unchecked")
        Map<String, Object> limits = (Map<String, Object>) config.data.get("limits");
        limits.put("llm_context_max_tokens", 256);
        try (LlmClient client = new LlmClient(config, resolver)) {
            assertThrows(
                    EngineException.ContextBudgetExceeded.class,
                    () -> client.chatFull(List.of(ChatMessage.user("x".repeat(20_000))), 5_000L));
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(0, resolver.calls);
        assertTrue(events(trace, "GENERATION_ATTEMPT_STARTED").isEmpty());
        assertTrue(events(trace, "GENERATION_REQUEST_DISPATCHED").isEmpty());
        assertEquals("RUNTIME_PRE_DISPATCH", event(trace, "GENERATION_FAILED").data().get("failure"));
    }

    @Test
    void localBudgetSubmissionFailureIsRuntimePredispatchWithNoProviderAttempt() {
        beginTrace("trace-budget-submission-predispatch-failure");
        LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(TokenChunk.of("never sent"), TokenChunk.eos())));
        client.close();

        assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> client.chatFull(List.of(ChatMessage.user("inspect")), 5_000L));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertTrue(events(trace, "GENERATION_ATTEMPT_STARTED").isEmpty());
        assertTrue(events(trace, "GENERATION_REQUEST_DISPATCHED").isEmpty());
        assertEquals(
                "RUNTIME_PRE_DISPATCH",
                event(trace, "GENERATION_FAILED").data().get("failure"));
    }

    @Test
    void engineLocalPreparationFailureStartsAttemptButDoesNotClaimTransportDispatch() {
        beginTrace("trace-engine-preparation-failure");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new PreDispatchFailureResolver())) {
            EngineException.ResponseError failure = assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chatFull(List.of(ChatMessage.user("inspect")), 5_000L));
            assertTrue(failure.getCause() instanceof IllegalArgumentException);
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, events(trace, "GENERATION_ATTEMPT_STARTED").size());
        assertTrue(events(trace, "GENERATION_REQUEST_DISPATCHED").isEmpty());
        assertEquals(
                "RUNTIME_PRE_DISPATCH",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(
                "RUNTIME_PRE_DISPATCH",
                event(trace, "GENERATION_FAILED").data().get("failure"));
    }

    @Test
    void serverContextRejectionAfterTransportHandoffIsProviderFailureNotLocalFitFailure() {
        beginTrace("trace-server-context-rejection");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new PostDispatchContextFailureResolver())) {
            assertThrows(
                    EngineException.ContextBudgetExceeded.class,
                    () -> client.chatFull(List.of(ChatMessage.user("inspect")), 5_000L));
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, events(trace, "GENERATION_REQUEST_DISPATCHED").size());
        assertEquals(
                "PROVIDER_EXCEPTION",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(
                "PROVIDER_EXCEPTION",
                event(trace, "GENERATION_FAILED").data().get("failure"));
    }

    @Test
    void runtimeFailureAfterDispatchRetainsRuntimeCausalTypeAndDispatchFact() {
        beginTrace("trace-runtime-failure-after-dispatch");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new PostDispatchRuntimeFailureResolver())) {
            assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chatFull(List.of(ChatMessage.user("inspect")), 5_000L));
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, events(trace, "GENERATION_REQUEST_DISPATCHED").size());
        assertEquals(
                "RUNTIME_EXCEPTION",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(
                "RUNTIME_EXCEPTION",
                event(trace, "GENERATION_FAILED").data().get("failure"));
    }

    @Test
    void processBindingInvalidationAfterDispatchIsRuntimeOwnedAndNeverEntersSend() {
        beginTrace("trace-process-binding-invalidation");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new PostDispatchProcessBindingInvalidationResolver())) {
            EngineException.ResponseError failure = assertThrows(
                    EngineException.ResponseError.class,
                    () -> client.chatFull(
                            List.of(ChatMessage.user("inspect")), 5_000L));
            assertTrue(failure.getCause()
                    instanceof ProviderTransportHandoff.ProcessBindingInvalidated);
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, events(trace, "GENERATION_REQUEST_DISPATCHED").size());
        assertEquals(
                "RUNTIME_EXCEPTION",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(
                "RUNTIME_EXCEPTION",
                event(trace, "GENERATION_FAILED").data().get("failure"));
        assertTrue(events(trace, "PROVIDER_TRANSPORT_LIFECYCLE").stream().anyMatch(event ->
                "PROCESS_BINDING_INVALIDATED".equals(event.data().get("transition"))
                        && "SEND_API_NOT_ENTERED".equals(
                                event.data().get("wireDisposition"))));
        assertTrue(events(trace, "PROVIDER_TRANSPORT_LIFECYCLE").stream().noneMatch(event ->
                "SEND_API_ENTERED".equals(event.data().get("transition"))));
    }

    @Test
    void interruptedTransportWithoutOwnedInterruptHasUnprovenCausalOwner() {
        beginTrace("trace-interrupted-after-handoff");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000), new PostDispatchInterruptedResolver())) {
            RuntimeException observed = assertThrows(
                    RuntimeException.class,
                    () -> client.chatFull(List.of(ChatMessage.user("inspect")), 5_000L));
            assertFalse(observed instanceof EngineException.ResponseError,
                    "typed interruption must not become a provider response failure");
            assertTrue(observed.getCause() instanceof InterruptedException);
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(1, events(trace, "GENERATION_REQUEST_DISPATCHED").size());
        assertEquals(
                "INTERRUPTION_CAUSE_UNPROVEN",
                event(trace, "GENERATION_ATTEMPT_FAILED").data().get("failure"));
        assertEquals(
                "INTERRUPTION_CAUSE_UNPROVEN",
                event(trace, "GENERATION_FAILED").data().get("failure"));
        assertTrue(events(trace, "GENERATION_ATTEMPT_ABANDONED").isEmpty());
        assertTrue(events(trace, "GENERATION_ABANDONED").isEmpty());
        assertFalse(Thread.currentThread().isInterrupted(),
                "an exception type alone must not invent or restore a caller interrupt");
    }

    @Test
    void cancellationWinningTheHandoffSendGapPreventsLateSendEntry()
            throws Exception {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        HandoffGapResolver resolver = new HandoffGapResolver();
        AtomicBoolean cancelled = new AtomicBoolean();
        Thread canceller = new Thread(() -> {
            try {
                if (!resolver.handoffCommitted.await(2, TimeUnit.SECONDS)) return;
                cancelled.set(true);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }, "handoff-gap-canceller");
        canceller.start();

        try {
            try (LlmClient client = new LlmClient(engineConfig(1_000), resolver)) {
                client.setCancelSupplier(cancelled::get);
                LlmClient.StreamResult result = client.chatFull(
                        List.of(ChatMessage.user("inspect")), 5_000L);

                assertEquals(ProviderResponseCapture.AbortOrigin.OPERATOR_EXTERNAL,
                        result.abortOrigin());
                assertEquals(1, capture.dispatches.size());
                assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                        capture.abandonments);
                assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                        capture.generationAbandonments);
                assertTrue(capture.responses.isEmpty());
                assertTrue(capture.receipts.isEmpty());
                assertTrue(capture.failures.isEmpty());
                assertTrue(capture.generationFailures.isEmpty());
                assertTrue(capture.generationCompletions.isEmpty());
                assertFalse(resolver.physicalSendStarted.await(100, TimeUnit.MILLISECONDS),
                        "the committed handoff event must not be described as a physical-send fact");

                resolver.releaseHandoff.countDown();
                assertFalse(resolver.physicalSendStarted.await(250, TimeUnit.MILLISECONDS),
                        "pre-send cancellation must prevent the transport send invocation");
            }

            Thread worker = resolver.workerThread.get();
            assertNotNull(worker);
            worker.join(2_000L);
            assertFalse(worker.isAlive(), "the late worker must finish before evidence is asserted");
            assertTrue(capture.responses.isEmpty(),
                    "a response returned after terminal abandonment must remain unrecorded");
            assertTrue(capture.receipts.isEmpty(),
                    "a response returned after terminal abandonment must remain unselectable");
            assertTrue(capture.failures.isEmpty());
            assertTrue(capture.generationFailures.isEmpty());
            assertTrue(capture.generationCompletions.isEmpty());
            assertEquals(List.of(ProviderResponseCapture.Abandonment.OPERATOR_CANCELLED),
                    capture.generationAbandonments);
            assertEquals(List.of(
                            ProviderTransportAttempt.Transition.HANDOFF_ADMITTED,
                            ProviderTransportAttempt.Transition.CANCELLATION_REQUESTED,
                            ProviderTransportAttempt.Transition.SETTLED),
                    capture.transportEvents.stream()
                            .map(ProviderResponseCapture.TransportLifecycleEvent::transition)
                            .toList());
            assertEquals(ProviderTransportAttempt.Settlement.CANCELLED_BEFORE_SEND,
                    capture.transportEvents.get(2).settlement());
        } finally {
            resolver.releaseHandoff.countDown();
            canceller.join(2_000L);
        }
    }

    @Test
    void ordinary_followup_generation_starts_a_new_logical_generation_not_a_retry() {
        beginTrace("trace-ordinary-followup-generations");
        try (LlmClient client = new LlmClient(
                engineConfig(1_000),
                new FixedResolver(List.of(TokenChunk.of("answer"), TokenChunk.eos())))) {
            client.chatFull(List.of(ChatMessage.user("first")));
            client.chatFull(List.of(ChatMessage.user("second")));
        }
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> starts = events(trace, "GENERATION_ATTEMPT_STARTED");
        assertEquals(2, starts.size());
        assertNotEquals(starts.get(0).data().get("generationId"), starts.get(1).data().get("generationId"));
        assertEquals(List.of(1, 1), starts.stream()
                .map(candidate -> candidate.data().get("attemptOrdinal")).toList());
        assertEquals(List.of("PRIMARY", "PRIMARY"), starts.stream()
                .map(candidate -> candidate.data().get("attemptKind")).toList());
    }

    private static void assertCompletion(
            List<TokenChunk> chunks,
            int responseCap,
            ProviderResponseCapture.Completion expected,
            boolean expectedTruncated) {
        Capture capture = new Capture();
        ProviderResponseCapture.begin(capture);
        try (LlmClient client = new LlmClient(
                engineConfig(responseCap),
                new FixedResolver(chunks))) {
            LlmClient.StreamResult result = client.chatFull(List.of(ChatMessage.user("inspect")));
            assertEquals(expected, result.completion());
            assertEquals(expectedTruncated, result.truncated());
        } finally {
            ProviderResponseCapture.clear();
        }
        ProviderResponseCapture.Response response = capture.onlyResponse();
        assertEquals(expected, response.completion());
        assertEquals(expectedTruncated, response.truncated());
        assertEquals(expected == ProviderResponseCapture.Completion.PROVIDER_EOS,
                capture.onlyReceipt().providerTerminalEligible());
    }

    private static void assertOperatorCancellationOwnsStreamSettlement(Capture capture) {
        assertEquals(List.of(
                        ProviderTransportAttempt.Transition.HANDOFF_ADMITTED,
                        ProviderTransportAttempt.Transition.SEND_API_ENTERED,
                        ProviderTransportAttempt.Transition.RESPONSE_HEADERS_OBSERVED,
                        ProviderTransportAttempt.Transition.RESPONSE_BODY_OPENED,
                        ProviderTransportAttempt.Transition.CANCELLATION_REQUESTED,
                        ProviderTransportAttempt.Transition.CANCELLATION_ACKNOWLEDGED,
                        ProviderTransportAttempt.Transition.SETTLED),
                capture.transportEvents.stream()
                        .map(ProviderResponseCapture.TransportLifecycleEvent::transition)
                        .toList());
        ProviderResponseCapture.TransportLifecycleEvent settled =
                capture.transportEvents.getLast();
        assertEquals(ProviderTransportAttempt.Settlement.CANCELLED_IN_FLIGHT,
                settled.settlement());
        assertEquals(ProviderTransportAttempt.CancellationReason.OPERATOR_CANCELLED,
                settled.cancellationReason());
        assertEquals(ProviderTransportAttempt.CancellationState.BODY_CLOSE_ACKNOWLEDGED,
                settled.cancellationState());
        assertTrue(settled.lateAfterGenerationTerminal());
    }

    private static void assertTransportSettlement(
            LocalTurnTrace trace,
            String expectedSettlement,
            boolean expectedQuarantine) {
        List<TurnTraceEvent> settlements = events(trace, "PROVIDER_TRANSPORT_LIFECYCLE")
                .stream()
                .filter(candidate -> "SETTLED".equals(candidate.data().get("transition")))
                .toList();
        assertEquals(1, settlements.size());
        assertEquals(expectedSettlement, settlements.getFirst().data().get("settlement"));
        assertEquals(expectedQuarantine,
                settlements.getFirst().data().get("quarantineRequired"));
    }

    private static Stream<TokenChunk> observedTransport(
            ProviderTransportHandoff handoff,
            ProviderTransportAttempt.SendOperation<Stream<TokenChunk>> operation) {
        ProviderTransportAttempt transport = handoff.admitTransport(
                ProviderTransportHandoff.DispatchEvidence.unbound());
        try {
            Stream<TokenChunk> source = transport.invokeSend(operation);
            transport.responseHeadersObserved();
            transport.responseBodyOpened(source::close);
            return ProviderTransportTokenStream.track(source, transport);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Exception failure) {
            throw new RuntimeException(failure);
        }
    }

    private static Config engineConfig(int responseCap) {
        Config config = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "test");
        config.data.put("llm", llm);
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("response_max_chars", responseCap);
        limits.put("llm_timeout_ms", 5_000L);
        limits.put("llm_idle_ms", 0L);
        config.data.put("limits", limits);
        return config;
    }

    private static ProviderProcessLaunchReceipt processReceipt(long processId) {
        return new ProviderProcessLaunchReceipt(
                processId,
                1_721_600_000_000L,
                1_721_600_000_100L,
                1_721_600_000_200L,
                "0123456789abcdef0123456789abcdef",
                "a".repeat(64),
                "b".repeat(64),
                "c".repeat(64),
                "e".repeat(64),
                ProviderProcessLaunchReceipt.LaunchArgumentSet.STANDARD,
                "f".repeat(64),
                ProviderProcessLaunchReceipt.EnvironmentContract
                        .QUALIFICATION_RELEVANT_ALLOWLIST_V1,
                "127.0.0.1",
                8080,
                true,
                "1".repeat(64),
                "qualification-model",
                ProviderProcessLaunchReceipt.ServedIdentity.MATCHED,
                ProviderProcessLaunchReceipt.EndpointObserverKind
                        .WINDOWS_NETSTAT_NOFOLLOW_SHA256,
                "2".repeat(64),
                ProviderProcessLaunchReceipt.EndpointOwnershipBoundary
                        .WINDOWS_NETSTAT_LISTENER_OWNER_PID_START_AT_READINESS_BIND_AND_TRANSPORT,
                ProviderProcessLaunchReceipt.StableEndpointAssumption
                        .WINDOWS_TRUSTED_HELPER_AND_STABLE_LISTENER_OWNER_BETWEEN_OBSERVATION_AND_NETWORK_TRANSITION,
                ProviderProcessLaunchReceipt.ContentVerificationBoundary
                        .IMMEDIATE_PRELAUNCH_NOFOLLOW_SHA256,
                ProviderProcessLaunchReceipt.StableFilesystemAssumption
                        .STABLE_LOCAL_FILES_DURING_PROCESS_LOAD);
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-provider-provenance",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "inspect provenance");
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .findFirst()
                .orElseThrow();
    }

    private static List<TurnTraceEvent> events(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .toList();
    }

    private static ToolSpec writeSpec() {
        return new ToolSpec("talos.write_file", "Write a file", "{\"type\":\"object\"}");
    }

    private static ChatRequestControls requiredControls(int cap) {
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

    private static final class FixedResolver implements LlmEngineResolver {
        private final List<TokenChunk> chunks;

        private FixedResolver(List<TokenChunk> chunks) {
            this.chunks = List.copyOf(chunks);
        }

        @Override
        public void select(String backend, String model) {
        }

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return chunks.stream();
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            ProviderTransportAttempt transport = handoff.admitTransport(
                    ProviderTransportHandoff.DispatchEvidence.unbound());
            try {
                Stream<TokenChunk> source = transport.invokeSend(() -> chatStream(request));
                transport.responseHeadersObserved();
                transport.responseBodyOpened(source::close);
                return ProviderTransportTokenStream.track(source, transport);
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        public void close() {
        }
    }

    private static final class AmbientInterruptResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            Thread.currentThread().interrupt();
            return Stream.of(TokenChunk.of("complete"), TokenChunk.eos())
                    .onClose(Thread::interrupted);
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> chatStream(request));
        }

        @Override
        public void close() {}
    }

    private static final class TransientOnceResolver implements LlmEngineResolver {
        private int calls;

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            if (++calls == 1) throw new EngineException.Transient("temporary", 503);
            return Stream.of(TokenChunk.of("recovered"), TokenChunk.eos());
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> chatStream(request));
        }

        @Override
        public void close() {}
    }

    private static final class LegacyOnlyResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            return Stream.of(TokenChunk.of("legacy answer"), TokenChunk.eos());
        }

        @Override
        public void close() {}
    }

    private static final class PreDispatchTransientOnceResolver implements LlmEngineResolver {
        private int calls;

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("dispatch-aware overload required");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            if (++calls == 1) {
                throw new EngineException.Transient("local preparation retry", 0);
            }
            return observedTransport(handoff, () ->
                    Stream.of(TokenChunk.of("recovered"), TokenChunk.eos()));
        }

        @Override
        public void close() {}
    }

    private static final class CapLiftResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            if (request.controls.maxOutputTokens() > 0) {
                return Stream.of(TokenChunk.of("partial"), TokenChunk.eos("length"));
            }
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call_write",
                            "talos.write_file",
                            Map.of("path", "index.html", "content", "<h1>private</h1>")))),
                    TokenChunk.eos("tool_calls"));
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> chatStream(request));
        }

        @Override
        public void close() {}
    }

    private static final class CompatRetryResolver implements LlmEngineResolver {
        private int streamingCalls;
        private int nonStreamingCalls;

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            streamingCalls++;
            return Stream.generate(() -> {
                throw new EngineException.MalformedResponse(
                        "compat chat stream tool arguments", "{\"path\":");
            });
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> chatStream(request));
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) {
            nonStreamingCalls++;
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call_compat", "talos.write_file", Map.of("path", "index.html")))),
                    TokenChunk.eos("tool_calls"));
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> chatStreamNonStreaming(request));
        }

        @Override
        public void close() {}
    }

    private static final class LegacyCompatRetryResolver implements LlmEngineResolver {
        private int streamingCalls;
        private int nonStreamingCalls;

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            streamingCalls++;
            throw new EngineException.MalformedResponse(
                    "compat chat stream tool arguments", "{\"path\":");
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) {
            nonStreamingCalls++;
            return Stream.of(
                    TokenChunk.ofToolCalls(List.of(new ChatMessage.NativeToolCall(
                            "call_legacy_compat",
                            "talos.write_file",
                            Map.of("path", "index.html")))),
                    TokenChunk.eos("tool_calls"));
        }

        @Override
        public void close() {}
    }

    private static final class CountingResolver implements LlmEngineResolver {
        private final int contextWindow;
        private int calls;

        private CountingResolver(int contextWindow) {
            this.contextWindow = contextWindow;
        }

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, contextWindow);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            calls++;
            return Stream.of(TokenChunk.eos());
        }

        @Override
        public void close() {}
    }

    private static final class PreDispatchFailureResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new IllegalArgumentException("provider body could not be constructed");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return chatStream(request);
        }

        @Override
        public void close() {}
    }

    private static final class PostDispatchContextFailureResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("dispatch-aware overload required");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> {
                throw new EngineException.ContextBudgetExceeded(
                        3_390, 3_072, 3_072, 0, 400);
            });
        }

        @Override
        public void close() {}
    }

    private static final class PostDispatchRuntimeFailureResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("dispatch-aware overload required");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) {
            return observedTransport(handoff, () -> {
                throw new IllegalStateException(
                        "local parser invariant failed after dispatch");
            });
        }

        @Override
        public void close() {}
    }

    private static final class PostDispatchProcessBindingInvalidationResolver
            implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("dispatch-aware overload required");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) throws Exception {
            AtomicReference<ProviderProcessLaunchReceipt> active =
                    new AtomicReference<>(processReceipt(8101L));
            ProviderTransportAttempt transport =
                    ProviderTransportHandoff.bindProcessReceipt(
                            handoff, active::get).admitTransport(
                                    ProviderTransportHandoff.DispatchEvidence.unbound());
            active.set(processReceipt(8102L));
            return transport.invokeSend(() ->
                    Stream.of(TokenChunk.of("must not send"), TokenChunk.eos()));
        }

        @Override
        public void close() {}
    }

    private static final class PostDispatchInterruptedResolver implements LlmEngineResolver {
        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("dispatch-aware overload required");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) throws InterruptedException {
            return observedTransport(handoff, () -> {
                throw new InterruptedException(
                        "transport operation interrupted without owned cause");
            });
        }

        @Override
        public void close() {}
    }

    private static final class HandoffGapResolver implements LlmEngineResolver {
        private final CountDownLatch handoffCommitted = new CountDownLatch(1);
        private final CountDownLatch releaseHandoff = new CountDownLatch(1);
        private final CountDownLatch physicalSendStarted = new CountDownLatch(1);
        private final AtomicReference<Thread> workerThread = new AtomicReference<>();

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 32_768);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            throw new AssertionError("dispatch-aware overload required");
        }

        @Override
        public Stream<TokenChunk> chatStream(
                ChatRequest request,
                ProviderTransportHandoff handoff) throws InterruptedException {
            workerThread.set(Thread.currentThread());
            ProviderTransportAttempt transport = handoff.admitTransport(
                    ProviderTransportHandoff.DispatchEvidence.unbound());
            handoffCommitted.countDown();
            releaseHandoff.await();
            try {
                return transport.invokeSend(() -> {
                    physicalSendStarted.countDown();
                    return Stream.of(TokenChunk.of("late response"), TokenChunk.eos());
                });
            } catch (InterruptedException interrupted) {
                throw interrupted;
            } catch (Exception failure) {
                if (failure instanceof RuntimeException runtime) throw runtime;
                throw new RuntimeException(failure);
            }
        }

        @Override
        public void close() {
            releaseHandoff.countDown();
        }
    }

    private static final class Capture implements ProviderResponseCapture.Listener {
        private final List<ProviderResponseCapture.AttemptEvent> starts = new ArrayList<>();
        private final List<ProviderResponseCapture.AttemptEvent> dispatches = new ArrayList<>();
        private final List<ProviderResponseCapture.Response> responses = new ArrayList<>();
        private final List<ProviderResponseCapture.Receipt> receipts = new ArrayList<>();
        private final List<ProviderResponseCapture.Failure> failures = new ArrayList<>();
        private final List<ProviderResponseCapture.Failure> generationFailures = new ArrayList<>();
        private final List<ProviderResponseCapture.Abandonment> abandonments = new ArrayList<>();
        private final List<ProviderResponseCapture.Abandonment> generationAbandonments =
                new ArrayList<>();
        private final List<ProviderResponseCapture.GenerationCompletion> generationCompletions =
                new ArrayList<>();
        private final List<ProviderResponseCapture.TransportLifecycleEvent> transportEvents =
                new CopyOnWriteArrayList<>();

        @Override
        public void attemptStarted(ProviderResponseCapture.AttemptEvent event) {
            starts.add(event);
        }

        @Override
        public void requestDispatched(ProviderResponseCapture.AttemptEvent event) {
            dispatches.add(event);
        }

        @Override
        public void transportLifecycle(ProviderResponseCapture.TransportLifecycleEvent event) {
            transportEvents.add(event);
        }

        @Override
        public ProviderResponseCapture.Receipt responseReturned(
                ProviderResponseCapture.AttemptResponse returned) {
            ProviderResponseCapture.Response response = returned.response();
            responses.add(response);
            ProviderResponseCapture.Receipt receipt = returned.receipt();
            receipts.add(receipt);
            return receipt;
        }

        @Override
        public void attemptFailed(ProviderResponseCapture.AttemptFailure failed) {
            failures.add(failed.failure());
        }

        @Override
        public void generationFailed(ProviderResponseCapture.GenerationFailure failed) {
            generationFailures.add(failed.failure());
        }

        @Override
        public void attemptAbandoned(ProviderResponseCapture.AttemptAbandonment abandoned) {
            abandonments.add(abandoned.abandonment());
        }

        @Override
        public void generationAbandoned(ProviderResponseCapture.GenerationAbandonment abandoned) {
            generationAbandonments.add(abandoned.abandonment());
        }

        @Override
        public void generationCompleted(ProviderResponseCapture.GenerationCompletion completed) {
            generationCompletions.add(completed);
        }

        private ProviderResponseCapture.Response onlyResponse() {
            assertEquals(1, responses.size());
            return responses.getFirst();
        }

        private ProviderResponseCapture.Receipt onlyReceipt() {
            assertEquals(1, receipts.size());
            return receipts.getFirst();
        }
    }
}
