package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ToolChoiceMode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlyEvidenceSynthesizerTest {
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();
    @BeforeEach void trace() {
        LocalTurnTraceCapture.begin("read-synthesis", "tests", 1, "2026-09-09T00:00:00Z",
                "workspace", "auto", "llama_cpp", "fixture", "diagnose files");
    }
    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    private LoopState state(LlmClient client) throws Exception {
        clients.add(client);
        var seed = ReadOnlyEvidenceContextTest.state(workspace);
        var state = new LoopState("", List.of(), seed.messages, workspace,
                Context.builder(new Config()).llm(client).build(), null, 10, 0);
        ReadOnlyEvidenceContextTest.populate(state);
        state.iterations = 10;
        state.totalToolsInvoked = 4;
        return state;
    }
    private static ToolCallExecutionStage.IterationOutcome duplicate() {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), 0, false, false, false, 0);
    }

    @Test void oneCapturedResponseUsesAllFourBodiesWithoutToolsAndRetainsTheCap() throws Exception {
        String answer = "index.html contains the Calculate button. script.js uses the wrong selector. "
                + "README.md names script.js, while scripts.js is the unrelated sentinel.";
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
        var state = state(recorded.client());
        var before = List.copyOf(state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
        assertEquals(answer, state.currentText);
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
        assertTrue(state.currentResponseReceipt.providerTerminalEligible());
        assertFalse(state.currentResponseReceipt.providerToolCallEligible());
        assertEquals(1, recorded.requests().size());
        var request = recorded.requests().getFirst();
        assertTrue(request.tools.isEmpty());
        assertEquals(ToolChoiceMode.NONE, request.controls.toolChoice());
        assertTrue(request.controls.singleAttempt());
        assertEquals(2048, request.controls.maxOutputTokens());
        assertEquals(ProviderResponseCapture.ToolAuthorityMode.NONE, request.controls.toolAuthorityMode());
        int anchor = -1;
        for (int i = 0; i < request.messages.size(); i++) {
            var message = request.messages.get(i);
            if ("system".equals(message.role()) && message.content().startsWith("[CurrentTurnCapability]")) anchor = i;
            if ("tool".equals(message.role())) {
                assertTrue(anchor >= 0 && anchor < i);
                assertTrue(message.content().contains("observed contents of"));
                assertEquals(message.toolCallId(), request.messages.get(i - 1).toolCalls().getFirst().id());
            }
        }
        assertEquals(4, request.messages.stream().filter(message -> "tool".equals(message.role())).count());
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(1, recorded.requests().size(), "No reinvocation after a selected synthesis");
    }

    @Test void completeReadsTriggerImmediatelyBeforeTheCapWithoutARepeatedRead() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("The supplied files establish the selector mismatch.", List.of())), 8192);
        var state = state(recorded.client());
        state.iterations = 4;
        assertEquals(0, state.cushionFiresRedundantRead);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
        assertFalse(new ToolCallRepromptStage().hitIterationLimit(state));
        assertEquals(1, recorded.requests().size());
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(1, recorded.requests().size(), "Immediate synthesis remains one-use");
    }

    @Test void missingOrInvalidatedEvidenceDoesNotDispatchSynthesis() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 8192);
        var state = state(recorded.client());
        ReadEvidenceStateAccounting.clearSuccessfulReadCaches(state);
        assertTrue(ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).isEmpty());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, state.readOnlySynthesisStatus);
        assertTrue(recorded.requests().isEmpty());
    }

    @Test void immediateDeflectionAndToolPayloadAreRejectedWithoutASecondSynthesis() throws Exception {
        for (String answer : List.of("How can I help?", "{\"name\":\"talos.read_file\",\"parameters\":{\"path\":\"README.md\"}}")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
            var state = state(recorded.client());
            state.iterations = 4;
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
            assertTrue(state.failureDecision.shouldStop());
            assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(1, recorded.requests().size());
            assertFalse(new ToolCallRepromptStage().hitIterationLimit(state));
        }
    }

    @Test void nativeProposalCannotAcquireAuthorityDuringSynthesis() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "unexpected", "talos.read_file", Map.of("path", "README.md"))))), 8192);
        var state = state(recorded.client());
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(4, state.totalToolsInvoked);
        assertEquals(1, recorded.requests().size());
    }

    @Test void closingOffersAndQuotedPhrasesAreNotLexicalDeflections() throws Exception {
        for (String answer : List.of(
                "index.html defines #calculate, but script.js uses #calc. README.md identifies script.js, "
                        + "while scripts.js is unrelated. Let me know if you want more detail.",
                "The diagnosis concerns a selector mismatch, not the conversational phrase 'how can I help'.")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
            var state = state(recorded.client());
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, state.readOnlySynthesisStatus);
            assertEquals(answer, state.currentText);
            assertTrue(state.currentResponseReceipt.providerTerminalEligible());
            ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test void leadingDeflectionsRemainRejectedDespiteWhitespaceAndCase() throws Exception {
        for (String answer : List.of("  \nHOW CAN I HELP?", "\tLet Me Know If You need anything.")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult(answer, List.of())), 8192);
            var state = state(recorded.client());
            assertTerminalRejectionWithoutReinvocation(state);
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test void lengthAndUnrecognizedProviderStopsCannotBecomeAcceptedSynthesis() throws Exception {
        for (String finishReason : List.of("length", "unknown-provider-stop")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                    new LlmClient.StreamResult("Partial diagnosis from the observed files.", List.of(), finishReason)),
                    8192);
            var state = state(recorded.client());
            assertTerminalRejectionWithoutReinvocation(state);
            assertEquals(1, recorded.requests().size(), finishReason + " must not trigger another request");
            assertTrue(recorded.requests().getFirst().controls.singleAttempt());
            assertEquals(2048, recorded.requests().getFirst().controls.maxOutputTokens());
        }
    }

    @Test void syntheticAnswerWithoutForegroundProviderReceiptCannotBecomeAcceptedSynthesis() throws Exception {
        var state = state(LlmClient.scripted(List.of("Plausible but synthetic diagnosis.", "Must not be consumed.")));
        assertTerminalRejectionWithoutReinvocation(state);
        var trace = LocalTurnTraceCapture.complete();
        assertEquals(1, trace.events().stream().filter(event -> "GENERATION_STARTED".equals(event.type())).count());
        assertFalse(trace.events().stream().anyMatch(event -> "TERMINAL_ANSWER_SELECTED".equals(event.type())
                && "MODEL_PROVIDER".equals(event.data().get("origin"))));
    }

    @Test void transportFailureAfterPartialTextCannotBecomeAcceptedSynthesis() throws Exception {
        var state = state(ScriptedNativeLlmClient.transientAfterPartialOutput(
                "The supplied files suggest a selector mismatch, but the response is unfinished."));
        assertTerminalRejectionWithoutReinvocation(state);
        var trace = LocalTurnTraceCapture.complete();
        assertEquals(1, trace.events().stream().filter(event -> "GENERATION_STARTED".equals(event.type())).count());
        assertEquals(1, trace.events().stream().filter(event -> "GENERATION_ATTEMPT_STARTED".equals(event.type())).count());
        assertFalse(trace.events().stream().anyMatch(event -> "GENERATION_COMPLETED".equals(event.type())));
    }

    private static void assertTerminalRejectionWithoutReinvocation(LoopState state) {
        var before = List.copyOf(state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(TerminalReadOnlyStopAnswer.SYNTHESIS_UNAVAILABLE_REASON, state.failureDecision.reason());
        assertFalse(state.currentResponseReceipt.providerTerminalEligible());
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(4, state.totalToolsInvoked);
        assertEquals(0, state.mutatingToolSuccesses);
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
        assertEquals(before, state.messages);
    }

    @Test void contextBudgetFailureCannotSilentlyDropBodiesOrEnterCompactRetry() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Do not consume.", List.of())), 512);
        var state = state(recorded.client());
        ReadOnlyEvidenceContextTest.read(state, "README.md", "long evidence ".repeat(500), Map.of("path", "README.md"));
        var before = List.copyOf(state.messages);
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertTrue(recorded.requests().isEmpty(), "Budget fails before provider dispatch rather than trimming required evidence");
        assertEquals(before, state.messages);
    }

    @Test void cancellationPropagatesAndCannotReinvoke() throws Exception {
        CancellationException cancellation = new CancellationException("operator stopped synthesis");
        var state = state(LlmClient.scriptedFailure(cancellation));
        state.iterations = 4;
        var before = List.copyOf(state.messages);
        assertSame(cancellation, assertThrows(CancellationException.class,
                () -> ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate())));
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, state.readOnlySynthesisStatus);
        assertEquals(before, state.messages);
        assertEquals(false, ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate()).orElseThrow());
    }

    @Test void requestBoundIncludesNativeArgumentsAndEscaping() {
        var call = new ChatMessage.NativeToolCall("id", "talos.read_file",
                Map.of("path", "README.md", "ignored", "\\\"".repeat(20_000)));
        assertFalse(ReadOnlyEvidenceSynthesizer.requestFits(List.of(
                ChatMessage.assistantWithToolCalls("", List.of(call)))));
        assertTrue(ReadOnlyEvidenceSynthesizer.requestFits(List.of(ChatMessage.user("small request"))));
    }

    @Test void providerFailureReturnsTruthfulIncompleteResult() throws Exception {
        var state = state(LlmClient.scriptedFailure(new IllegalStateException("fixture failure")));
        ReadOnlyEvidenceSynthesizer.tryHandle(state, duplicate());
        assertEquals(ReadOnlyEvidenceSynthesizer.UNAVAILABLE, state.currentText);
        assertTrue(state.failureDecision.shouldStop());
        assertTrue(new ToolCallRepromptStage().hitIterationLimit(state));
    }
}
