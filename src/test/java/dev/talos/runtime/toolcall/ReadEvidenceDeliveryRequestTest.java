package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Constructed responses are exercised through the real continuation and serialized transport fixture. */
class ReadEvidenceDeliveryRequestTest {
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();
    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("talos.read_file", "Read", "{}"));

    @BeforeEach void trace() {
        LocalTurnTraceCapture.begin("delivery", "tests", 1, "2026-09-27T00:00:00Z",
                "workspace", "ask", "llama_cpp", "fixture", "Explain the current sources.");
    }

    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
    }

    private LoopState state(LlmClient client) throws Exception {
        clients.add(client);
        String request = "Explain the current sources without changing files.";
        TurnTaskContractCapture.set(new TaskContract(TaskType.WORKSPACE_EXPLAIN, false, false, false,
                Set.of(), Set.of(), request));
        var state = new LoopState("", List.of(), new ArrayList<>(List.of(ChatMessage.system("system"),
                ChatMessage.user(request))), workspace, Context.builder(new Config()).llm(client)
                .nativeToolSpecs(TOOLS).build(), null, 10, 0);
        for (String path : List.of("caller.txt", "implementation.txt", "options.txt"))
            ReadOnlyEvidenceContextTest.read(state, path, "1 | latest " + path + "\n", Map.of("path", path));
        ToolCallSupport.compactOlderToolResultsInPlace(state.messages, state.compactedToolResultOriginals);
        return state;
    }

    @Test void ordinaryContinuationDeliversAllBodiesWithoutProducingSynthesisEvidence() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("An unverified explanation.", List.of())), 16384);
        var state = state(recorded.client());
        var before = List.copyOf(state.messages);
        assertTrue(ToolRepromptOverlayContinuation.execute(state, List.of(), List.of(), "", false, TOOLS));
        assertEquals(1, recorded.requests().size());
        var request = recorded.requests().getFirst();
        for (String path : List.of("caller.txt", "implementation.txt", "options.txt"))
            assertTrue(request.messages.stream().anyMatch(message -> "tool".equals(message.role())
                    && message.content().contains("latest " + path)));
        assertEquals(ProviderReadInputBinding.Purpose.DELIVERY, state.currentResponseReceipt.readInputBinding().purpose());
        assertTrue(state.currentResponseReceipt.readInputBinding().bound());
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertEquals(before, state.messages);
    }

    @Test void legitimateNativeContinuationKeepsItsSeparateAuthority() throws Exception {
        var call = new ChatMessage.NativeToolCall("next", "talos.read_file", Map.of("path", "extra.txt"));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(call))), 16384);
        var state = state(recorded.client());
        assertTrue(ToolRepromptOverlayContinuation.execute(state, List.of(), List.of(), "", false, TOOLS));
        assertEquals(List.of(call), state.currentNativeCalls);
        assertTrue(state.currentResponseReceipt.providerToolCallEligible());
        assertTrue(state.currentResponseReceipt.readInputBinding().bound());
        assertEquals(0, state.totalToolsInvoked, "The continuation test does not execute proposed tools");
    }

    @Test void aMissingRestorationStopsBeforeCallingTheProvider() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Must not be requested.", List.of())), 16384);
        var state = state(recorded.client());
        assertFalse(ToolRepromptChatExecutor.executeResult(state, state.messages, TOOLS,
                ChatRequestControls.defaults(), "unused"));
        assertTrue(recorded.requests().isEmpty());
        assertEquals("READ_EVIDENCE_DELIVERY_UNAVAILABLE", state.failureDecision.reason());
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test void alteredRequestBodyStopsBeforeCallingTheProvider() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Must not be requested.", List.of())), 16384);
        var state = state(recorded.client());
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            var request = new ArrayList<>(state.messages);
            request.set(3, ChatMessage.toolResult(request.get(3).toolCallId(), "different source"));
            assertFalse(ToolRepromptChatExecutor.executeResult(state, request, TOOLS,
                    ChatRequestControls.defaults(), "unused"));
        }
        assertTrue(recorded.requests().isEmpty());
        assertTrue(state.failureDecision.shouldStop());
    }

    @Test void unnamedSourceOverflowStopsBeforeCallingTheProvider() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Must not be requested.", List.of())), 16384);
        var state = state(recorded.client());
        for (int index = 0; index < 6; index++) {
            String path = "extra" + index + ".txt";
            ReadOnlyEvidenceContextTest.read(state, path, "1 | extra source\n", Map.of("path", path));
        }
        assertFalse(ToolRepromptOverlayContinuation.execute(state, List.of(), List.of(), "", false, TOOLS));
        assertTrue(recorded.requests().isEmpty());
        assertTrue(state.currentText.contains("incomplete"));
    }

    @Test void aMissingReceiptNeverEstablishesDelivery() {
        assertFalse(ReadEvidenceDeliveryRequest.delivered(ProviderResponseCapture.Receipt.none(),
                List.of(new ProviderReadInputBinding.ToolResultInput("read", "source"))));
    }

    @Test void deliveredInputsDoNotTurnTruncatedOutputIntoExecutionAuthority() throws Exception {
        var call = new ChatMessage.NativeToolCall("unexecuted", "talos.read_file", Map.of("path", "extra.txt"));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Partial answer.", List.of(call), "length")), 16384);
        var state = state(recorded.client());
        var messages = List.copyOf(state.messages);
        var outcomes = List.copyOf(state.toolOutcomes);
        int executed = state.totalToolsInvoked;
        assertFalse(ToolRepromptOverlayContinuation.execute(state, List.of(), List.of(), "", false, TOOLS));
        assertTrue(state.currentProviderAnswerIncomplete);
        assertEquals(dev.talos.runtime.IncompleteGenerationPresentation.NOTICE, state.currentText);
        assertFalse(state.currentResponseReceipt.readInputBinding().bound());
        assertTrue(state.currentAnswerOutputLimitReached);
        assertFalse(state.currentResponseReceipt.providerTerminalEligible());
        assertFalse(state.currentResponseReceipt.providerToolCallEligible());
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(messages, state.messages);
        assertEquals(outcomes, state.toolOutcomes);
        assertEquals(executed, state.totalToolsInvoked);
        assertEquals(0, state.mutatingToolSuccesses);
        assertEquals(1, recorded.requests().size());
        var request = recorded.requests().getFirst();
        assertTrue(request.controls.debugTags().contains(ProviderReadInputBinding.DELIVERY_TAG));
        for (String path : List.of("caller.txt", "implementation.txt", "options.txt"))
            assertTrue(request.messages.stream().anyMatch(message -> "tool".equals(message.role())
                    && message.content().contains("latest " + path)));
        LocalTurnTraceCapture.recordRoutedAnswerCaptured(state.currentText);
        var trace = LocalTurnTraceCapture.complete();
        assertTrue(trace.events().stream().anyMatch(event -> "REPAIR_DECISION_RECORDED".equals(event.type())
                && "READ_EVIDENCE_DELIVERED".equals(event.data().get("status"))
                && "sourceCount=3, semanticCompleteness=notEstablished".equals(event.data().get("summary"))));
        var status = dev.talos.runtime.trace.FinalAnswerStatus.decode(trace);
        assertEquals(dev.talos.runtime.trace.FinalAnswerStatus.Delivery.LIMITED, status.delivery());
        assertTrue(status.causes().contains(dev.talos.runtime.trace.FinalAnswerStatus.Cause.INCOMPLETE_PROVIDER_GENERATION));
    }

    @Test void contextOverflowCannotReplaceMultipleSourcesWithSingleTargetFallback() throws Exception {
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Must not be requested.", List.of())), 512);
        var state = state(recorded.client());
        String request = "Review README.md and propose improvements without editing files.";
        TurnTaskContractCapture.set(new TaskContract(TaskType.READ_ONLY_QA, false, false, false,
                Set.of("README.md"), Set.of(), request));
        ReadOnlyEvidenceContextTest.read(state, "README.md", "1 | current readme\n", Map.of("path", "README.md"));
        state.messages.add(ChatMessage.user("additional context ".repeat(2000)));
        assertFalse(ToolRepromptOverlayContinuation.execute(state, List.of(), List.of(), request, false, TOOLS));
        assertTrue(recorded.requests().isEmpty(), "No compact single-source retry may reach the provider");
        assertEquals("READ_EVIDENCE_DELIVERY_UNAVAILABLE", state.failureDecision.reason());
        assertTrue(state.currentNativeCalls.isEmpty());
        assertTrue(state.currentText.contains("incomplete"));
    }
}
