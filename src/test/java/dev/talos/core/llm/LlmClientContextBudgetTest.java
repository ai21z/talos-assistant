package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
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

class LlmClientContextBudgetTest {

    @Test
    void trimsOldHistoryBeforeEngineSendButKeepsCurrentExactFrameUserAndTools() {
        RecordingResolver resolver = new RecordingResolver(Capabilities.of(
                true, true, false, 2048,
                true, true, true,
                false, false, false, true));
        LlmClient client = new LlmClient(engineConfig(2048), resolver);
        client.setModel("llama_cpp/qwen2.5-coder-14b");
        client.setToolSpecs(List.of(writeSpec()));

        client.chatFull(longExactWriteMessages(), 5_000L);

        String sent = joinedMessageContent(resolver.lastRequest.messages);
        assertFalse(sent.contains("OLD_HISTORY_00"), "oldest history should be trimmed before provider send");
        assertFalse(sent.contains("OLD_HISTORY_01"), "old history should be trimmed before provider send");
        assertFalse(sent.contains("TALOS_CONTEXT_BUDGET_SECRET_MARKER"),
                "protected-looking stale history must not survive trimming");
        assertTrue(sent.contains("[CurrentTurnCapability]"), "current-turn frame must survive trimming");
        assertTrue(sent.contains("[ExactFileWrite]"), "exact-write frame must survive trimming");
        assertTrue(sent.contains("requiredTargets: index.html"), "expected target must survive trimming");
        assertTrue(sent.contains("AFTER"), "current-turn literal content must survive trimming");
        assertTrue(sent.contains("Overwrite index.html with exactly AFTER"), "latest user request must survive trimming");
        assertEquals(List.of("talos.write_file"),
                resolver.lastRequest.tools.stream().map(ToolSpec::name).toList());
        assertTrue(resolver.lastRequest.controls.debugTags().contains("context-budget-trimmed"),
                "prompt debug should mark locally trimmed context");
    }

    @Test
    void failsBeforeBackendCallWhenCurrentTurnCannotFitContextBudget() {
        RecordingResolver resolver = new RecordingResolver(Capabilities.of(
                true, true, false, 512,
                true, true, true,
                false, false, false, true));
        LlmClient client = new LlmClient(engineConfig(512), resolver);
        client.setModel("llama_cpp/qwen2.5-coder-14b");
        client.setToolSpecs(List.of(writeSpec()));

        EngineException.ContextBudgetExceeded ex = assertThrows(
                EngineException.ContextBudgetExceeded.class,
                () -> client.chatFull(irreduciblyLargeCurrentTurnMessages(), 5_000L));

        assertEquals(0, resolver.chatCalls.get(), "irreducible request should fail before backend send");
        assertTrue(ex.getMessage().contains("context budget"));
    }

    private static List<ChatMessage> longExactWriteMessages() {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system("You are Talos."));
        for (int i = 0; i < 24; i++) {
            messages.add(ChatMessage.user("OLD_HISTORY_%02d ".formatted(i) + "u".repeat(600)));
            messages.add(ChatMessage.assistant("OLD_HISTORY_%02d ".formatted(i) + "a".repeat(600)));
        }
        messages.add(ChatMessage.user(".env contained TALOS_CONTEXT_BUDGET_SECRET_MARKER before this turn. "
                + "s".repeat(6_000)));
        messages.add(ChatMessage.system("""
                [CurrentTurnCapability]
                [TaskContract]
                type: FILE_EDIT
                mutationAllowed: true
                verificationRequired: true
                [ExpectedTargets]
                requiredTargets: index.html
                [ExactFileWrite]
                target: index.html
                expectedContent:
                <<<TALOS_CURRENT_TURN_EXACT_CONTENT
                AFTER
                TALOS_CURRENT_TURN_EXACT_CONTENT
                """));
        messages.add(ChatMessage.user("Overwrite index.html with exactly AFTER. Use talos.write_file."));
        return messages;
    }

    @Test
    void explicitOutputReserveHonorsTheEstimatedBoundaryForFullAndStreamingCalls() {
        // The input allowance is 8192 - 4096 - 100, with 83 estimated framing tokens.
        for (boolean streaming : List.of(false, true)) {
            for (int extra : List.of(0, 4)) {
                var resolver = resolver(8192);
                try (var client = new LlmClient(engineConfig(8192), resolver)) {
                    var messages = List.of(ChatMessage.user("x".repeat((3996 - 83) * 4 + extra)));
                    if (extra == 0) {
                        invoke(client, streaming, messages, controls(4096));
                        assertEquals(messages, resolver.lastRequest.messages);
                        assertEquals(4096, resolver.lastRequest.controls.maxOutputTokens());
                        assertEquals(1, resolver.chatCalls.get());
                    } else {
                        assertThrows(EngineException.ContextBudgetExceeded.class,
                                () -> invoke(client, streaming, messages, controls(4096)));
                        assertEquals(0, resolver.chatCalls.get());
                    }
                }
            }
        }
    }

    @Test
    void absentOrSmallerExplicitCapsRetainTheExistingDefaultReserve() {
        var messages = List.of(ChatMessage.user("x".repeat(20_000)));
        for (int cap : List.of(0, 128, 2048)) {
            var resolver = resolver(8192);
            try (var client = new LlmClient(engineConfig(8192), resolver)) {
                invoke(client, false, messages, controls(cap));
                assertEquals(messages, resolver.lastRequest.messages);
                assertEquals(cap, resolver.lastRequest.controls.maxOutputTokens());
                assertEquals(1, resolver.chatCalls.get());
            }
        }
    }

    @Test
    void outputConsumingTheContextFailsWithoutOverflowOrDispatch() {
        for (boolean streaming : List.of(false, true)) {
            for (int cap : List.of(8192, 8193, Integer.MAX_VALUE)) {
                var resolver = resolver(8192);
                try (var client = new LlmClient(engineConfig(8192), resolver)) {
                    assertThrows(EngineException.ContextBudgetExceeded.class,
                            () -> invoke(client, streaming, List.of(ChatMessage.user("small")), controls(cap)));
                    assertEquals(0, resolver.chatCalls.get());
                }
            }
        }
    }

    @Test
    void engineContextLimitRemainsAuthoritativeForExplicitOutputReserve() {
        var resolver = resolver(8192);
        try (var client = new LlmClient(engineConfig(16384), resolver)) {
            assertThrows(EngineException.ContextBudgetExceeded.class,
                    () -> invoke(client, false, List.of(ChatMessage.user("x".repeat(20_000))), controls(4096)));
            assertEquals(0, resolver.chatCalls.get());
        }
    }

    @Test
    void largerReserveTrimsOnlyOldHistoryAndPreservesAnchoredReadPairsAndSchema() {
        for (boolean streaming : List.of(false, true)) {
            var resolver = resolver(8192);
            var current = List.of(
                    ChatMessage.system("[CurrentTurnCapability]\nRead only."),
                    ChatMessage.user("Explain the observed file."),
                    ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                            "read-id", "talos.read_file", Map.of("path", "README.md")))),
                    ChatMessage.toolResult("read-id", "observed evidence ".repeat(300)));
            var messages = new ArrayList<ChatMessage>();
            messages.add(ChatMessage.user("OLD_HISTORY " + "x".repeat(16_000)));
            messages.add(ChatMessage.assistant("old answer"));
            messages.addAll(current);
            var controls = new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.JSON_SCHEMA,
                    "{\"type\":\"object\"}", List.of(), null, 4096).withSingleAttempt(true);
            try (var client = new LlmClient(engineConfig(8192), resolver)) {
                invoke(client, streaming, messages, controls);
                assertFalse(joinedMessageContent(resolver.lastRequest.messages).contains("OLD_HISTORY"));
                assertTrue(resolver.lastRequest.messages.containsAll(current));
                assertEquals(current, resolver.lastRequest.messages.subList(
                        resolver.lastRequest.messages.size() - current.size(), resolver.lastRequest.messages.size()));
                assertTrue(resolver.lastRequest.tools.isEmpty());
                assertEquals(controls.jsonSchema(), resolver.lastRequest.controls.jsonSchema());
                assertEquals(4096, resolver.lastRequest.controls.maxOutputTokens());
                assertTrue(resolver.lastRequest.controls.singleAttempt());
                assertEquals(1, resolver.chatCalls.get());
            }
        }
    }

    @Test
    void schemaAlsoConsumesInputHeadroomAndCannotBeDroppedToFit() {
        var resolver = resolver(8192);
        try (var client = new LlmClient(engineConfig(8192), resolver)) {
            var controls = new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.JSON_SCHEMA,
                    "{\"description\":\"" + "x".repeat(16_000) + "\",\"type\":\"object\"}",
                    List.of(), null, 4096).withSingleAttempt(true);
            assertThrows(EngineException.ContextBudgetExceeded.class,
                    () -> invoke(client, false, List.of(ChatMessage.user("small")), controls));
            assertEquals(0, resolver.chatCalls.get());
        }
    }

    private static RecordingResolver resolver(int contextTokens) {
        return new RecordingResolver(Capabilities.of(true, true, false, contextTokens,
                true, true, true, false, false, false, true));
    }

    private static ChatRequestControls controls(int outputTokens) {
        return new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "", List.of(),
                null, outputTokens).withSingleAttempt(true);
    }

    private static void invoke(LlmClient client, boolean streaming, List<ChatMessage> messages,
                               ChatRequestControls controls) {
        if (streaming) client.chatStreamFull(messages, ignored -> {}, 5_000L, List.of(), controls);
        else client.chatFull(messages, 5_000L, List.of(), controls);
    }

    private static List<ChatMessage> irreduciblyLargeCurrentTurnMessages() {
        return List.of(
                ChatMessage.system("You are Talos."),
                ChatMessage.system("""
                        [CurrentTurnCapability]
                        [ExactFileWrite]
                        expectedContent:
                        """ + "x".repeat(20_000)),
                ChatMessage.user("Overwrite index.html with exactly the provided content."));
    }

    private static ToolSpec writeSpec() {
        return new ToolSpec(
                "talos.write_file",
                "Create or overwrite a file in the workspace.",
                """
                {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}
                """);
    }

    private static String joinedMessageContent(List<ChatMessage> messages) {
        return messages.stream().map(ChatMessage::content).reduce("", (left, right) -> left + "\n" + right);
    }

    private static Config engineConfig(int contextTokens) {
        Config cfg = new Config();
        LinkedHashMap<String, Object> llm = new LinkedHashMap<>();
        llm.put("transport", "engine");
        llm.put("default_backend", "llama_cpp");
        cfg.data.put("llm", llm);

        LinkedHashMap<String, Object> llamaCpp = new LinkedHashMap<>();
        llamaCpp.put("model", "qwen2.5-coder-14b");
        cfg.data.put("llama_cpp", llamaCpp);

        LinkedHashMap<String, Object> limits = new LinkedHashMap<>();
        limits.put("llm_context_max_tokens", contextTokens);
        cfg.data.put("limits", limits);
        return cfg;
    }

    private static final class RecordingResolver implements LlmEngineResolver {
        private final AtomicInteger chatCalls = new AtomicInteger();
        private final Capabilities capabilities;
        private volatile ChatRequest lastRequest;

        private RecordingResolver(Capabilities capabilities) {
            this.capabilities = capabilities;
        }

        @Override
        public void select(String backend, String model) {
            // no-op
        }

        @Override
        public Capabilities capabilities() {
            return capabilities;
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
