package dev.talos.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.core.util.Sanitize;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ChatRequestControls;
import dev.talos.spi.types.PromptDebugCapture;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ResponseFormatMode;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class LlmClientReadInputBindingTest {
    @AfterEach void clear() {
        ProviderResponseCapture.clear();
        PromptDebugCapture.clear();
    }

    @Test void preparedInputsAreCapturedAfterTheKnownSanitizer() {
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {});
        var resolver = new BindingResolver(false);
        String source = "\u001b[31m1 | <button>é\u0000</button>\u001b[0m\r\n2 | \tend\n";
        try (var client = new LlmClient(config(8192), resolver)) {
            var result = client.chatFull(messages(source), 5_000, List.of(), controls());
            var binding = result.provenance().readInputBinding();
            assertTrue(binding.bound());
            assertEquals(ProviderReadInputBinding.SANITIZER_VERSION, binding.sanitizerVersion());
            assertEquals(List.of(new ProviderReadInputBinding.ToolResultInput("required", Sanitize.sanitizeMessageContent(source))),
                    binding.preparedToolResults());
            assertNotEquals(source, binding.preparedToolResults().getFirst().content());
            assertEquals(1, resolver.calls.get());
            assertTrue(resolver.request.tools.isEmpty());
            assertEquals(ToolChoiceMode.NONE, resolver.request.controls.toolChoice());
        }
    }

    @Test void onlyInputsRemainingAfterContextFittingAreCaptured() {
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {});
        var resolver = new BindingResolver(false);
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("system"));
        messages.add(ChatMessage.user("old request"));
        messages.add(ChatMessage.assistantWithToolCalls("", List.of(
                new ChatMessage.NativeToolCall("old", "talos.read_file", Map.of("path", "old.html")))));
        messages.add(ChatMessage.toolResult("old", "old observation ".repeat(2000)));
        messages.addAll(messages("1 | required source\n").subList(1, 5));
        try (var client = new LlmClient(config(1024), resolver)) {
            var result = client.chatFull(messages, 5_000, List.of(), controls());
            assertTrue(result.provenance().readInputBinding().bound());
            assertEquals(List.of("required"), result.provenance().readInputBinding().preparedToolResults().stream()
                    .map(ProviderReadInputBinding.ToolResultInput::toolCallId).toList());
            assertTrue(resolver.request.controls.debugTags().contains("context-budget-trimmed"));
            assertFalse(resolver.request.messages.stream().anyMatch(message -> "old".equals(message.toolCallId())));
            assertEquals(1, resolver.calls.get());
        }
    }

    @Test void anOversizedAnchoredReadFailsBeforeAnyPhysicalAttempt() {
        var attempts = new AtomicInteger();
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {
            @Override public void attemptStarted(ProviderResponseCapture.AttemptEvent event) { attempts.incrementAndGet(); }
        });
        var resolver = new BindingResolver(false);
        try (var client = new LlmClient(config(1024), resolver)) {
            assertThrows(EngineException.ContextBudgetExceeded.class,
                    () -> client.chatFull(messages("large source ".repeat(5000)), 5_000, List.of(), controls()));
            assertEquals(0, resolver.calls.get());
            assertEquals(0, attempts.get());
        }
    }

    @Test void serializedMismatchDoesNotCauseAnotherGeneration() {
        ProviderResponseCapture.begin(new ProviderResponseCapture.Listener() {});
        var resolver = new BindingResolver(true);
        try (var client = new LlmClient(config(8192), resolver)) {
            var result = client.chatFull(messages("1 | source\n"), 5_000, List.of(), controls());
            assertTrue(result.provenance().providerTerminalEligible());
            assertFalse(result.provenance().readInputBinding().bound());
            assertEquals("TOOL_RESULT_MEMBERSHIP_MISMATCH", result.provenance().readInputBinding().reasonCode());
            assertEquals(1, resolver.calls.get());
        }
    }

    @Test void noCaptureScopeDoesNotUpgradeTheDetachedReceipt() {
        var resolver = new BindingResolver(false);
        try (var client = new LlmClient(config(8192), resolver)) {
            var result = client.chatFull(messages("1 | source\n"), 5_000, List.of(), controls());
            assertFalse(result.provenance().providerTerminalEligible());
            assertFalse(result.provenance().readInputBinding().bound());
            assertEquals(1, resolver.calls.get());
        }
    }

    private static List<ChatMessage> messages(String source) {
        return List.of(ChatMessage.system("system"), ChatMessage.user("diagnose"),
                ChatMessage.system("[CurrentTurnCapability]\nDiagnosis"),
                ChatMessage.assistantWithToolCalls("", List.of(
                        new ChatMessage.NativeToolCall("required", "talos.read_file", Map.of("path", "index.html")))),
                ChatMessage.toolResult("required", source));
    }

    private static ChatRequestControls controls() {
        return new ChatRequestControls(ToolChoiceMode.NONE, "", ResponseFormatMode.TEXT, "",
                List.of(ProviderReadInputBinding.REQUEST_TAG)).withSingleAttempt(true);
    }

    private static Config config(int contextTokens) {
        var cfg = new Config();
        cfg.data.put("llm", new LinkedHashMap<>(Map.of(
                "transport", "engine", "default_backend", "llama_cpp", "model", "fixture")));
        cfg.data.put("limits", new LinkedHashMap<>(Map.of("llm_context_max_tokens", contextTokens)));
        return cfg;
    }

    private static final class BindingResolver implements LlmEngineResolver {
        private final boolean alterSerializedContent;
        private final AtomicInteger calls = new AtomicInteger();
        private ChatRequest request;

        private BindingResolver(boolean alterSerializedContent) { this.alterSerializedContent = alterSerializedContent; }
        @Override public void select(String backend, String model) {}
        @Override public void close() {}
        @Override public Capabilities capabilities() { return Capabilities.of(true, true, false, 8192, true); }
        @Override public Stream<TokenChunk> chatStream(ChatRequest request) { throw new AssertionError("unobserved dispatch"); }

        @Override public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff) throws Exception {
            this.request = request;
            calls.incrementAndGet();
            List<Map<String, Object>> messages = new ArrayList<>();
            for (var message : request.messages) {
                var serialized = new LinkedHashMap<String, Object>();
                serialized.put("role", message.role());
                serialized.put("content", "tool".equals(message.role()) && alterSerializedContent
                        ? "changed" : message.content());
                if ("tool".equals(message.role())) serialized.put("tool_call_id", message.toolCallId());
                messages.add(serialized);
            }
            String body = new ObjectMapper().writeValueAsString(Map.of("messages", messages));
            var transport = ProviderTransportHandoff.admit(handoff, body, List.of(), request);
            transport.invokeSend(() -> null);
            transport.responseHeadersObserved();
            transport.responseBodyOpened(() -> {});
            transport.providerTerminalObserved();
            transport.responseBodyCompleted();
            return Stream.of(TokenChunk.of("answer"), TokenChunk.eos("stop"));
        }
    }
}
