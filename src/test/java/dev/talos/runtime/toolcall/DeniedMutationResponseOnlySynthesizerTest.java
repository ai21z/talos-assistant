package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolSpec;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.trace.TurnTraceEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeniedMutationResponseOnlySynthesizerTest {

    @AfterEach
    void clearTrace() {
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
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
        List<ChatMessage> originalMessages =
                List.copyOf(state.messages);

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals("I inspected the available evidence only.", answer.text());
        assertTrue(answer.capturedGeneration());
        assertEquals(ProviderResponseCapture.Source.FOREGROUND_PROVIDER, answer.receipt().source());
        assertTrue(answer.receipt().providerTerminalEligible());
        assertEquals(initialMessages, state.messages.size());
        assertEquals(originalMessages, state.messages);
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[Tool policy stop]"), prompt);
        assertTrue(prompt.contains("Do not call any more tools in this turn."), prompt);
        assertFalse(prompt.contains("talos.write_file"), prompt);
        assertTrue(prompt.contains("visibleTools: (none)"), prompt);
        assertTrue(recorded.requests().getFirst().tools.isEmpty());
        assertEquals(
                ProviderResponseCapture.ToolAuthorityMode.NONE,
                recorded.requests().getFirst().controls.toolAuthorityMode());
        assertTrue(
                answer.receipt().toolAuthorityContract()
                        .selectedToolNames().isEmpty());
    }

    @Test
    void deniedResponseProjectsOnlyCurrentTaskAndPreservesOriginalEvidence() {
        String original = "Who are you? Read README.md. Do not change files.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("The available read evidence was inspected.", List.of())), 16_384);
        try (var client = recorded.client()) {
            var current = ChatMessage.user(original);
            var evidence = ChatMessage.toolResult("read", "Quoted source question: Who are you?");
            var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(original),
                    ChatMessage.assistant("An earlier answer."), current, evidence));
            var resolution = TaskContractResolver.resolveFromMessages(messages);
            TurnTaskContractCapture.set(resolution, messages, dev.talos.spi.types.ModelIdentitySnapshot.empty(), true);
            var before = List.copyOf(messages);
            var state = new LoopState("", List.of(), messages, Path.of("."),
                    Context.builder(new Config()).llm(client).build(), null, 5, 0);
            assertTrue(DeniedMutationResponseOnlySynthesizer.synthesize(state).capturedGeneration());
            var request = recorded.requests().getFirst();
            assertTrue(request.messages.stream().anyMatch(message -> "user".equals(message.role())
                    && "Read README.md. Do not change files.".equals(message.content().strip())));
            assertEquals(1, request.messages.stream().filter(message -> original.equals(message.content())).count());
            assertTrue(request.messages.contains(evidence));
            assertEquals(before, messages);
            assertSame(resolution, TurnTaskContractCapture.getResolution());
            assertEquals(original, TurnTaskContractCapture.get().originalUserRequest());
            assertTrue(request.tools.isEmpty());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter"})
    void incompleteProviderStopCannotSelectPrivateDeniedMutationCandidateOrRetry(String finishReason) {
        String partial = "PRIVATE_DENIED_PARTIAL must not become a response after the denied write.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(partial, List.of(), finishReason)), 16_384);
        try (var client = recorded.client()) {
            LoopState state = state(client);
            var originalMessages = List.copyOf(state.messages);
            beginTrace();

            var answer = DeniedMutationResponseOnlySynthesizer.synthesize(state);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();

            assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage() + "\n\n"
                    + dev.talos.runtime.IncompleteGenerationPresentation.NOTICE, answer.text());
            assertFalse(answer.text().contains(partial));
            assertFalse(answer.capturedGeneration());
            assertFalse(answer.receipt().providerTerminalEligible());
            assertEquals(originalMessages, state.messages);
            assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
            assertTrue(state.currentNativeCalls.isEmpty());
            assertFalse(state.mutationSinceStart);
            assertEquals(0, state.mutatingToolSuccesses);
            assertEquals(1, recorded.requests().size(), "Denied mutation synthesis must not retry incomplete prose");
            var request = recorded.requests().getFirst();
            assertTrue(request.tools.isEmpty());
            assertEquals(dev.talos.spi.types.ToolChoiceMode.AUTO, request.controls.toolChoice());
            assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
            var responses = trace.events().stream()
                    .filter(event -> "GENERATION_RESPONSE_CAPTURED".equals(event.type())).toList();
            assertEquals(1, responses.size());
            assertEquals("length".equals(finishReason) ? "PROVIDER_LENGTH" : "UNRECOGNIZED_PROVIDER_STOP",
                    responses.getFirst().data().get("completion"));
            assertFalse(trace.toString().contains("PRIVATE_DENIED_PARTIAL"));
        }
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
    void directCancellationFromResponseOnlySynthesisPropagatesUnchanged() {
        CancellationException cancellation = new CancellationException("stop response-only synthesis");
        LoopState state = state(LlmClient.scriptedFailure(cancellation));
        int initialMessages = state.messages.size();

        CancellationException thrown = assertThrows(
                CancellationException.class,
                () -> DeniedMutationResponseOnlySynthesizer.synthesize(state));

        assertSame(cancellation, thrown);
        assertEquals(initialMessages, state.messages.size());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void genericWrappedCancellationFromResponseOnlySynthesisPropagatesUnchanged() {
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("stop response-only synthesis"));
        LoopState state = state(LlmClient.scriptedFailure(wrapped));
        int initialMessages = state.messages.size();

        RuntimeException thrown = assertThrows(
                RuntimeException.class,
                () -> DeniedMutationResponseOnlySynthesizer.synthesize(state));

        assertSame(wrapped, thrown);
        assertEquals(initialMessages, state.messages.size());
        assertFalse(state.messages.stream().anyMatch(DeniedMutationResponseOnlySynthesizerTest::isPolicyStopPrompt));
    }

    @Test
    void typedEngineFailureWithCancellationCauseStillUsesDeterministicFallback() {
        CancellationException hiddenCause = new CancellationException("transport detail");
        LoopState state = state(LlmClient.scriptedFailure(
                new EngineException.ConnectionFailed("local engine", hiddenCause)));

        DeniedMutationResponseOnlySynthesizer.Result answer =
                DeniedMutationResponseOnlySynthesizer.synthesize(state);

        assertEquals(DeniedMutationResponseOnlySynthesizer.stopMessage(), answer.text());
        assertFalse(answer.capturedGeneration());
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

    @ParameterizedTest
    @ValueSource(strings = {"length", "content_filter"})
    void repromptStagePreservesIncompleteCauseAfterSelectingItsPolicyStop(String finishReason) {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("PRIVATE_DENIED_PARTIAL", List.of(), finishReason)), 16_384);
        try (var client = recorded.client()) {
            LoopState state = state(client);
            beginTrace();
            boolean continuing = new ToolCallRepromptStage().reprompt(state,
                    new ToolCallExecutionStage.IterationOutcome(0, List.of(), 1, false, true, false, 0));
            LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);
            LocalTurnTrace trace = LocalTurnTraceCapture.complete();
            var status = dev.talos.runtime.trace.FinalAnswerStatus.decode(trace);
            assertFalse(continuing);
            assertTrue(state.currentProviderAnswerIncomplete);
            assertTrue(state.currentText.startsWith(DeniedMutationResponseOnlySynthesizer.stopMessage()));
            assertTrue(state.currentText.contains(dev.talos.runtime.IncompleteGenerationPresentation.NOTICE));
            assertFalse(state.currentText.contains("PRIVATE_DENIED_PARTIAL"));
            assertEquals(dev.talos.runtime.trace.FinalAnswerStatus.Delivery.LIMITED, status.delivery());
            assertTrue(status.causes().contains(
                    dev.talos.runtime.trace.FinalAnswerStatus.Cause.INCOMPLETE_PROVIDER_GENERATION));
            assertEquals(1, recorded.requests().size());
            assertTrue(recorded.requests().getFirst().tools.isEmpty());
            assertTrue(state.currentNativeCalls.isEmpty());
            assertEquals(0, state.mutatingToolSuccesses);
        }
    }

    private static LoopState state(LlmClient llm) {
        Context.Builder builder = Context.builder(new Config())
                .nativeToolSpecs(List.of(new ToolSpec("talos.write_file", "Write", "{}")));
        if (llm != null) {
            builder.llm(llm);
        }
        List<ChatMessage> messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Try to write README.md.")));
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromMessages(messages),
                messages);
        return new LoopState(
                "",
                List.of(),
                messages,
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
