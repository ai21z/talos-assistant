package dev.talos.runtime.trace;

import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.toolcall.LoopState;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeterministicAnswerTraceRecorderTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void loopFinalAnswerRecordsOnlyHashAndCharacterCount() throws Exception {
        LocalTurnTraceCapture.begin(
                "trace-deterministic-answer",
                "session-deterministic-answer",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "select deterministic answer");
        LoopState state = new LoopState(
                "", List.of(), List.of(), null, null, null, 1, 0);

        state.finishWithAnswer("deterministic secret-free answer");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "DETERMINISTIC_ANSWER_SELECTED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(TraceRedactor.hash("deterministic secret-free answer"),
                event.data().get("answerHash"));
        assertEquals("deterministic secret-free answer".length(),
                event.data().get("answerChars"));
        assertEquals("RUNTIME_DETERMINISTIC", event.data().get("origin"));
        assertFalse(event.data().toString().contains("secret-free"));
    }

    @Test
    void toolDerivedTerminalUsesClosedToolOrigin() {
        beginTrace("trace-tool-deterministic-answer");
        LoopState state = new LoopState(
                "", List.of(), List.of(), null, null, null, 1, 0);
        state.finishWithToolAnswer("Read README.md: evidence");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("TOOL_DETERMINISTIC",
                event(trace, "DETERMINISTIC_ANSWER_SELECTED").data().get("origin"));
        assertEquals("TOOL_DETERMINISTIC", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
    }

    @Test
    void explicitOperatorSelectionUsesClosedOperatorOrigin() {
        beginTrace("trace-operator-answer");
        LoopState state = new LoopState(
                "", List.of(), List.of(), null, null, null, 1, 0);
        state.finishWithOperatorAnswer("[Turn interrupted by operator]");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent selection = event(trace, "DETERMINISTIC_ANSWER_SELECTED");
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("OPERATOR", selection.data().get("origin"));
        assertEquals("OPERATOR", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
    }

    @Test
    void identicalProviderReturnThenDeterministicSelectionRoutesAsRuntime() {
        beginTrace("trace-provider-then-runtime");
        try (LlmClient client = ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("same answer", List.of())))) {
            client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        LoopState state = new LoopState(
                "", List.of(), List.of(), null, null, null, 1, 0);
        state.finishWithAnswer("same answer");
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("same answer");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<String> origins = trace.events().stream()
                .map(TurnTraceEvent::type)
                .filter(type -> type.equals("GENERATION_RESPONSE_CAPTURED")
                        || type.equals("DETERMINISTIC_ANSWER_SELECTED")
                        || type.equals("ROUTED_ANSWER_CAPTURED"))
                .toList();

        assertEquals(List.of(
                "GENERATION_RESPONSE_CAPTURED",
                "DETERMINISTIC_ANSWER_SELECTED",
                "ROUTED_ANSWER_CAPTURED"), origins);
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("RUNTIME_DETERMINISTIC", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
    }

    @Test
    void foregroundEngineResponseRoutesAsModelProviderWithExactGeneration() {
        beginTrace("trace-provider-terminal");
        LlmClient.StreamResult result;
        try (LlmClient client = ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("provider terminal", List.of())))) {
            result = client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.text());

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent generation = event(trace, "GENERATION_RESPONSE_CAPTURED");
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("FOREGROUND_PROVIDER", generation.data().get("source"));
        assertEquals("PROVIDER_EOS", generation.data().get("completion"));
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertEquals(generation.data().get("generationId"), terminal.data().get("sourceGenerationId"));
        assertEquals(generation.data().get("attemptId"), terminal.data().get("sourceAttemptId"));
    }

    @Test
    void laterAttemptStartAndFailureInvalidatePriorTerminalBinding() {
        beginTrace("trace-failed-attempt-invalidates-binding");
        try (LlmClient client = ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("stale provider answer", List.of())))) {
            client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        try (LlmClient failing = LlmClient.scriptedFailure(
                new RuntimeException("fixture failure"))) {
            assertThrows(
                    RuntimeException.class,
                    () -> failing.chatFull(List.of(ChatMessage.user("retry"))));
        }
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("stale provider answer");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent failed = event(trace, "GENERATION_FAILED");
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("SCRIPTED_HARNESS", failed.data().get("source"));
        assertEquals("HARNESS_EXCEPTION", failed.data().get("failure"));
        assertEquals("NONE", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
    }

    @Test
    void explicit_abort_fact_invalidates_prior_provider_candidate() {
        beginTrace("trace-abort-invalidates-binding");
        LlmClient.StreamResult result;
        try (LlmClient client = ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("stale provider answer", List.of())))) {
            result = client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        assertTrue(result.provenance().providerTerminalEligible());
        LocalTurnTraceCapture.recordModelGenerationAborted("interrupted", true);
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("stale provider answer");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = event(trace, "TERMINAL_ANSWER_SELECTED");
        assertEquals("NONE", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
        assertFalse(terminal.data().containsKey("sourceAttemptId"));
    }

    @Test
    void scriptedAndPlaceholderAnswersAreNeverModelProvider() {
        assertSyntheticOrigin(
                LlmClient.scripted("scripted answer"),
                "scripted answer",
                "SCRIPTED_HARNESS",
                "NONE");

        assertSyntheticOrigin(
                new LlmClient(placeholderConfig()),
                null,
                "PLACEHOLDER_RUNTIME",
                "RUNTIME_DETERMINISTIC");
    }

    @Test
    void providerToolReceiptLinksOnlyTheCallsFromThatResponse() {
        beginTrace("trace-provider-tool-receipt");
        ChatMessage.NativeToolCall nativeCall = new ChatMessage.NativeToolCall(
                "call-read", "talos.read_file", Map.of("path", "README.md"));
        LlmClient.StreamResult result;
        try (LlmClient client = ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult("", List.of(nativeCall))))) {
            result = client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                result.provenance());
        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "runtime.txt")),
                ProviderResponseCapture.Receipt.none());

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        List<TurnTraceEvent> parsed = trace.events().stream()
                .filter(candidate -> "TOOL_CALL_PARSED".equals(candidate.type()))
                .toList();
        assertEquals(2, parsed.size());
        assertEquals(
                event(trace, "GENERATION_RESPONSE_CAPTURED").data().get("generationId"),
                parsed.getFirst().data().get("providerGenerationId"));
        assertEquals(
                event(trace, "GENERATION_RESPONSE_CAPTURED").data().get("attemptId"),
                parsed.getFirst().data().get("providerAttemptId"));
        assertFalse(parsed.get(1).data().containsKey("providerGenerationId"));
    }

    @Test
    void textualCompatibilityToolCallRetainsItsExactProviderGeneration() {
        beginTrace("trace-provider-text-tool-receipt");
        String toolText = """
                ```json
                {"name":"talos.read_file","arguments":{"path":"README.md"}}
                ```
                """;
        LlmClient.StreamResult result;
        try (LlmClient client = ScriptedNativeLlmClient.of(List.of(
                new LlmClient.StreamResult(toolText, List.of())))) {
            result = client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                result.provenance());

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent generation = event(trace, "GENERATION_RESPONSE_CAPTURED");
        TurnTraceEvent parsed = event(trace, "TOOL_CALL_PARSED");
        assertEquals("RUNTIME_LEGACY_TEXT_STOP", generation.data().get("completion"));
        assertEquals(true, generation.data().get("textualToolCallPresent"));
        assertEquals(generation.data().get("generationId"), parsed.data().get("providerGenerationId"));
    }

    @Test
    void laterAbortClearsGenerationBindingBeforeToolParsing() {
        assertNonProviderResponseClearsBinding(new ProviderResponseCapture.Response(
                "partial",
                0,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.RUNTIME_ABORT,
                false,
                false));
    }

    @Test
    void laterPlaceholderClearsGenerationBindingBeforeToolParsing() {
        assertNonProviderResponseClearsBinding(new ProviderResponseCapture.Response(
                "placeholder",
                0,
                ProviderResponseCapture.Source.PLACEHOLDER_RUNTIME,
                ProviderResponseCapture.Completion.SYNTHETIC_RETURN,
                false,
                false));
    }

    private static void assertNonProviderResponseClearsBinding(
            ProviderResponseCapture.Response laterResponse) {
        beginTrace("trace-stale-provider-binding");
        ProviderResponseCapture.attemptStarted(ProviderResponseCapture.Source.FOREGROUND_PROVIDER);
        ProviderResponseCapture.record(new ProviderResponseCapture.Response(
                "normal",
                1,
                ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                ProviderResponseCapture.Completion.PROVIDER_EOS,
                false,
                false));
        ProviderResponseCapture.attemptStarted(laterResponse.source());
        ProviderResponseCapture.Receipt laterReceipt = ProviderResponseCapture.record(laterResponse);
        LocalTurnTraceCapture.recordToolCallParsed(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                laterReceipt);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent parsed = trace.events().stream()
                .filter(event -> "TOOL_CALL_PARSED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertFalse(parsed.data().containsKey("providerGenerationId"));
    }

    private static void beginTrace(String traceId) {
        LocalTurnTraceCapture.begin(
                traceId,
                "session-deterministic-answer",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "select deterministic answer");
    }

    private static TurnTraceEvent event(LocalTurnTrace trace, String type) {
        return trace.events().stream()
                .filter(candidate -> type.equals(candidate.type()))
                .findFirst()
                .orElseThrow();
    }

    private static void assertSyntheticOrigin(
            LlmClient client,
            String expectedText,
            String expectedSource,
            String expectedOrigin) {
        LocalTurnTraceCapture.clear();
        beginTrace("trace-synthetic-" + expectedSource.toLowerCase());
        LlmClient.StreamResult result;
        try (client) {
            result = client.chatFull(List.of(ChatMessage.user("inspect")));
        }
        if (expectedText != null) {
            assertEquals(expectedText, result.text());
        }
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(result.text());
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals(expectedSource, event(trace, "GENERATION_RESPONSE_CAPTURED").data().get("source"));
        assertEquals(expectedOrigin, event(trace, "TERMINAL_ANSWER_SELECTED").data().get("origin"));
        assertFalse(event(trace, "TERMINAL_ANSWER_SELECTED").data().containsKey("sourceGenerationId"));
        assertFalse((Boolean) event(trace, "GENERATION_RESPONSE_CAPTURED").data().get("providerBacked"));
    }

    private static dev.talos.core.Config placeholderConfig() {
        dev.talos.core.Config config = new dev.talos.core.Config();
        config.data.put("llm", new java.util.LinkedHashMap<>(Map.of("transport", "placeholder")));
        return config;
    }
}
