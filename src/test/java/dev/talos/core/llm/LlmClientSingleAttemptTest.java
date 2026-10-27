package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** In-memory resolver fixtures exercise client dispatch boundaries, not a live provider. */
class LlmClientSingleAttemptTest {
    @AfterEach
    void clearCapture() {
        PromptDebugCapture.clear();
        ProviderResponseCapture.clear();
    }

    @Test
    void transientFailureUsesOneResolverCallForBothFullChatEntryPoints() {
        for (boolean streaming : List.of(false, true)) {
            RecordingResolver resolver = new RecordingResolver(FirstOutcome.TRANSIENT);
            try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
                assertThrows(EngineException.Transient.class,
                        () -> invoke(client, streaming, List.of(), noToolControls().withSingleAttempt(true)));
            }

            assertEquals(1, resolver.streamCalls.get());
            assertEquals(0, resolver.nonStreamingCalls.get());
            assertTrue(resolver.lastRequest.controls.singleAttempt());
            assertTrue(resolver.lastRequest.tools.isEmpty());
            assertEquals(ToolChoiceMode.NONE, resolver.lastRequest.controls.toolChoice());
        }
    }

    @Test
    void lengthCompletionDoesNotLiftRequiredToolOutputCapInSingleAttemptMode() {
        for (boolean streaming : List.of(false, true)) {
            RecordingResolver resolver = new RecordingResolver(FirstOutcome.LENGTH);
            StringBuilder displayed = new StringBuilder();
            LlmClient.StreamResult result;
            try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
                ChatRequestControls controls = requiredToolControls().withSingleAttempt(true);
                result = streaming
                        ? client.chatStreamFull(messages(), displayed::append, 5_000L,
                                List.of(readSpec()), controls)
                        : client.chatFull(messages(), 5_000L, List.of(readSpec()), controls);
            }

            assertEquals(1, resolver.streamCalls.get());
            assertEquals(0, resolver.nonStreamingCalls.get());
            assertEquals(128, resolver.lastRequest.controls.maxOutputTokens());
            assertTrue(result.outputLimitReached());
            assertTrue(result.text().contains("partial"));
            assertFalse(result.hasToolCalls());
            if (streaming) assertTrue(displayed.toString().contains("partial"));
        }
    }

    @Test
    void malformedToolArgumentsDoNotSwitchToCompatibilityTransportInSingleAttemptMode() {
        for (boolean streaming : List.of(false, true)) {
            RecordingResolver resolver = new RecordingResolver(FirstOutcome.MALFORMED);
            try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
                EngineException.MalformedResponse failure = assertThrows(
                        EngineException.MalformedResponse.class,
                        () -> invoke(client, streaming, List.of(readSpec()),
                                requiredToolControls().withSingleAttempt(true)));
                assertEquals("compat chat stream tool arguments", failure.context());
            }

            assertEquals(1, resolver.streamCalls.get());
            assertEquals(0, resolver.nonStreamingCalls.get());
            assertEquals(128, resolver.lastRequest.controls.maxOutputTokens());
        }
    }

    @Test
    void defaultTransientRetryStillRecovers() {
        RecordingResolver resolver = new RecordingResolver(FirstOutcome.TRANSIENT);
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            LlmClient.StreamResult result = invoke(client, false, List.of(), noToolControls());
            assertEquals("recovered", result.text());
        }

        assertEquals(2, resolver.streamCalls.get());
        assertEquals(0, resolver.nonStreamingCalls.get());
        assertFalse(resolver.lastRequest.controls.singleAttempt());
    }

    @Test
    void defaultLengthRecoveryStillLiftsOutputCap() {
        RecordingResolver resolver = new RecordingResolver(FirstOutcome.LENGTH);
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            LlmClient.StreamResult result = invoke(client, false, List.of(readSpec()), requiredToolControls());
            assertEquals("recovered", result.text());
        }

        assertEquals(2, resolver.streamCalls.get());
        assertEquals(0, resolver.nonStreamingCalls.get());
        assertEquals(0, resolver.lastRequest.controls.maxOutputTokens());
        assertTrue(resolver.lastRequest.controls.debugTags().contains("output-cap-lifted-retry"));
        assertFalse(resolver.lastRequest.controls.singleAttempt());
    }

    @Test
    void defaultMalformedRecoveryStillUsesCompatibilityTransport() {
        RecordingResolver resolver = new RecordingResolver(FirstOutcome.MALFORMED);
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            LlmClient.StreamResult result = invoke(client, false, List.of(readSpec()), requiredToolControls());
            assertEquals("recovered", result.text());
        }

        assertEquals(1, resolver.streamCalls.get());
        assertEquals(1, resolver.nonStreamingCalls.get());
        assertFalse(resolver.lastRequest.controls.singleAttempt());
    }

    @Test
    void contextTrimmingAndConfiguredSamplingPreserveSingleAttempt() {
        RecordingResolver resolver = new RecordingResolver(FirstOutcome.TRANSIENT);
        List<ChatMessage> history = new ArrayList<>();
        history.add(ChatMessage.system("You are Talos."));
        for (int i = 0; i < 24; i++) {
            history.add(ChatMessage.user("old user " + "u".repeat(600)));
            history.add(ChatMessage.assistant("old assistant " + "a".repeat(600)));
        }
        history.add(ChatMessage.user("Answer from the supplied context without tools."));
        try (LlmClient client = new LlmClient(engineConfig(), resolver)) {
            assertThrows(EngineException.Transient.class,
                    () -> client.chatFull(history, 5_000L, List.of(),
                            noToolControls().withSingleAttempt(true)));
        }

        assertEquals(1, resolver.streamCalls.get());
        assertTrue(resolver.lastRequest.controls.singleAttempt());
        assertTrue(resolver.lastRequest.controls.debugTags().contains("context-budget-trimmed"));
        assertEquals(0.7, resolver.lastRequest.controls.sampling().temperature());
        assertTrue(resolver.lastRequest.messages.size() < history.size());
    }

    @Test
    void scriptedFullChatConsumesOnlyOneResponsePerSingleAttemptCall() {
        try (LlmClient client = LlmClient.scripted(List.of("first", "second"))) {
            ChatRequestControls controls = noToolControls().withSingleAttempt(true);
            assertEquals("first", invoke(client, false, List.of(), controls).text());
            assertEquals("second", invoke(client, false, List.of(), controls).text());
        }
    }

    private static LlmClient.StreamResult invoke(
            LlmClient client, boolean streaming, List<ToolSpec> tools, ChatRequestControls controls) {
        return streaming
                ? client.chatStreamFull(messages(), ignored -> {}, 5_000L, tools, controls)
                : client.chatFull(messages(), 5_000L, tools, controls);
    }

    private static List<ChatMessage> messages() {
        return List.of(ChatMessage.user("Answer from the supplied context."));
    }

    private static ChatRequestControls noToolControls() {
        return new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT,
                "", List.of("bounded-synthesis"));
    }

    private static ChatRequestControls requiredToolControls() {
        return new ChatRequestControls(ToolChoiceMode.REQUIRED, "", ResponseFormatMode.TEXT,
                "", List.of("required-read"))
                .withMaxOutputTokens(128)
                .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE);
    }

    private static ToolSpec readSpec() {
        return new ToolSpec("talos.read_file", "Read a file.",
                "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"}},\"required\":[\"path\"]}");
    }

    private static Config engineConfig() {
        Config config = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        llm.put("sampling", Map.of("temperature", 0.7));
        config.data.put("llm", llm);
        config.data.put("llama_cpp", new LinkedHashMap<>(Map.of("model", "fixture")));
        config.data.put("limits", new LinkedHashMap<>(Map.of("llm_context_max_tokens", 2048)));
        return config;
    }

    private enum FirstOutcome { TRANSIENT, LENGTH, MALFORMED }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final FirstOutcome firstOutcome;
        private final AtomicInteger streamCalls = new AtomicInteger();
        private final AtomicInteger nonStreamingCalls = new AtomicInteger();
        private volatile ChatRequest lastRequest;

        private RecordingResolver(FirstOutcome firstOutcome) {
            this.firstOutcome = firstOutcome;
        }

        @Override
        public void select(String backend, String model) {}

        @Override
        public Capabilities capabilities() {
            return Capabilities.of(true, true, false, 2048);
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            lastRequest = request;
            if (streamCalls.incrementAndGet() == 1) {
                switch (firstOutcome) {
                    case TRANSIENT -> throw new EngineException.Transient("fixture transient", 503);
                    case LENGTH -> { return Stream.of(TokenChunk.of("partial"), TokenChunk.eos("length")); }
                    case MALFORMED -> {
                        return Stream.generate(() -> {
                            throw new EngineException.MalformedResponse(
                                    "compat chat stream tool arguments", "{\"path\":");
                        });
                    }
                }
            }
            return Stream.of(TokenChunk.of("recovered"), TokenChunk.eos("stop"));
        }

        @Override
        public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) {
            nonStreamingCalls.incrementAndGet();
            lastRequest = request;
            return Stream.of(TokenChunk.of("recovered"), TokenChunk.eos("stop"));
        }

        @Override
        public void close() {}
    }
}
