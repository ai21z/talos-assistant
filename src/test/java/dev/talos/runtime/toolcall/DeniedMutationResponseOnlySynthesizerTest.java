package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeniedMutationResponseOnlySynthesizerTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void missingLlmReturnsDeterministicPolicyStopMessage() {
        LoopState state = new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(ChatMessage.system("sys"))),
                Path.of("."),
                null,
                null,
                5,
                0);

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer.text());
        assertFalse(answer.capturedGeneration());
    }

    @Test
    void textOnlySynthesisReturnsProviderExactAnswerAndRemovesTemporaryPrompt() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I inspected the available evidence only.", List.of())),
                16_384);
        LoopState state = state(recorded.client());
        beginTrace();
        int initialMessages = state.messages.size();

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals("I inspected the available evidence only.", answer.text());
        assertTrue(answer.capturedGeneration());
        assertEquals(ProviderResponseCapture.Source.FOREGROUND_PROVIDER, answer.receipt().source());
        assertTrue(answer.receipt().providerTerminalEligible());
        assertEquals(initialMessages, state.messages.size());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[Tool policy stop]"), prompt);
        assertTrue(prompt.contains("Do not call any more tools in this turn."), prompt);
    }

    @Test
    void nativeToolCallsForceDeterministicPolicyStopMessage() {
        var llm = ScriptedNativeLlmClient.of(List.of(new LlmClient.StreamResult(
                "",
                List.of(new ChatMessage.NativeToolCall(
                        "call-write",
                        "talos.write_file",
                        Map.of("path", "README.md", "content", "changed"))))));
        LoopState state = state(llm);

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer.text());
        assertFalse(answer.capturedGeneration());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void textualToolCallDebrisForcesDeterministicPolicyStopMessage() {
        LoopState state = state(LlmClient.scripted("""
                ```json
                {"name":"talos.write_file","arguments":{"path":"README.md","content":"changed"}}
                ```
                """));

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer.text());
        assertFalse(answer.capturedGeneration());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void synthesisFailureFallsBackAndRemovesTemporaryPrompt() {
        LoopState state = state(LlmClient.scriptedFailure(new RuntimeException("backend unavailable")));
        int initialMessages = state.messages.size();

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer.text());
        assertFalse(answer.capturedGeneration());
        assertEquals(initialMessages, state.messages.size());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void repromptStageDelegatesDeniedMutationSynthesisToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("DeniedMutationResponseOnlySynthesizer.synthesize"), source);
        assertFalse(source.contains("private static String responseOnlyAfterDeniedMutation"), source);
        assertFalse(source.contains("private static String deniedMutationStopMessage"), source);
    }

    @Test
    void repromptStagePreservesProviderOwnershipForGroundedDeniedMutationAnswer() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I inspected only README.md.", List.of())),
                16_384);
        LoopState state = state(recorded.client());
        beginTrace();

        boolean continueLoop = new ToolCallRepromptStage().reprompt(
                state,
                new ToolCallExecutionStage.IterationOutcome(
                        0, List.of(), 1, false, true, false, 0));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);

        assertFalse(continueLoop);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertTrue(terminal.data().containsKey("sourceGenerationId"));
    }

    @Test
    void postReturnWhitespaceNormalizationPreservesProviderLineageThroughNamedTransformation() {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("  I inspected only README.md.  ", List.of())),
                16_384);
        LoopState state = state(recorded.client());
        beginTrace();

        new ToolCallRepromptStage().reprompt(
                state,
                new ToolCallExecutionStage.IterationOutcome(
                        0, List.of(), 1, false, true, false, 0));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("I inspected only README.md.", state.currentText);
        assertEquals("MODEL_PROVIDER", terminal.data().get("origin"));
        assertTrue(terminal.data().containsKey("sourceGenerationId"));
        assertTrue(terminal.data().containsKey("sourceAttemptId"));
        TurnTraceEvent transformation = trace.events().stream()
                .filter(event -> "ANSWER_TRANSFORMED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("PROVIDER_RESPONSE_NORMALIZATION", transformation.data().get("reasonCode"));
        assertEquals(terminal.data().get("answerHash"), transformation.data().get("answerHash"));
    }

    @Test
    void repromptStageMarksFallbackStopAsRuntimeDeterministic() {
        LoopState state = state(LlmClient.scriptedFailure(
                new RuntimeException("fixture backend unavailable")));
        beginTrace();

        boolean continueLoop = new ToolCallRepromptStage().reprompt(
                state,
                new ToolCallExecutionStage.IterationOutcome(
                        0, List.of(), 1, false, true, false, 0));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);

        assertFalse(continueLoop);
        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), state.currentText);
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent terminal = trace.events().stream()
                .filter(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("RUNTIME_DETERMINISTIC", terminal.data().get("origin"));
        assertFalse(terminal.data().containsKey("sourceGenerationId"));
    }

    private static LoopState state(LlmClient llm) {
        Context.Builder builder = Context.builder(new Config())
                .nativeToolSpecs(List.of(new ToolSpec("talos.write_file", "Write", "{}")));
        if (llm != null) {
            builder.llm(llm);
        }
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(List.of(
                        ChatMessage.system("sys"),
                        ChatMessage.user("Try to write README.md."))),
                Path.of("."),
                builder.build(),
                null,
                5,
                0);
    }

    private static boolean isPolicyStopPrompt(ChatMessage message) {
        return message != null
                && "system".equals(message.role())
                && message.content() != null
                && message.content().startsWith("[Tool policy stop]");
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trace-denied-synthesis",
                "session-denied-synthesis",
                1,
                "2026-07-16T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "inspect README.md");
    }
}
