package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
