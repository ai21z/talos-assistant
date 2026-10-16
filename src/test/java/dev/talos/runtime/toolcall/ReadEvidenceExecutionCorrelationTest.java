package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ProviderReadInputBinding;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Real file reads and execution permits are used with a scripted provider response. */
class ReadEvidenceExecutionCorrelationTest {
    @TempDir Path workspace;
    private static final List<ToolSpec> TOOLS = List.of(new ToolSpec("talos.read_file", "Read", "{}"));
    private final List<LlmClient> clients = new ArrayList<>();

    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        LocalTurnTraceCapture.clear();
        TurnTaskContractCapture.clear();
        TurnSourceEvidenceCapture.clear();
        dev.talos.core.context.ContextLedgerCapture.clear();
    }

    private Harness harness() {
        String request = "Explain the current sources without changing files.";
        LocalTurnTraceCapture.begin("correlation", "fixture", 1, "2026-09-28T00:00:00Z",
                "workspace", "ask", "scripted", "fixture", request);
        TurnTaskContractCapture.set(new TaskContract(TaskType.WORKSPACE_EXPLAIN, false, false, false,
                Set.of(), Set.of(), request));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("An unverified explanation.", List.of())), 16384);
        clients.add(recorded.client());
        var cfg = new Config(null);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var ctx = Context.builder(cfg).sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                .llm(recorded.client()).nativeToolSpecs(TOOLS).build();
        var state = new LoopState("", List.of(), new ArrayList<>(List.of(ChatMessage.system("system"),
                ChatMessage.user(request))), workspace, ctx, new Session(workspace, cfg), 10, 0);
        var stage = new ToolCallExecutionStage(new TurnProcessor(null, new NoOpApprovalGate(), registry), null, true);
        return new Harness(state, stage);
    }

    private void execute(Harness harness, String id, String path) {
        harness.state.setRuntimeNativeCalls("", List.of(new ChatMessage.NativeToolCall(id,
                        "talos.read_file", Map.of("path", path))),
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.DIRECT_RUNTIME_API);
        var parsed = new ToolCallParseStage().parse(harness.state, ++harness.state.iterations);
        assertEquals(1, parsed.calls().size());
        harness.stage.execute(harness.state, parsed);
        assertFalse(harness.state.failureDecision.shouldStop());
        var outcome = harness.state.toolOutcomes.getLast();
        assertFalse(outcome.toolCallId().isBlank());
        assertNotEquals(id, outcome.toolCallId(), "Trace and provider IDs must be genuinely distinct");
        assertEquals(id, harness.state.messages.getLast().toolCallId());
    }

    private void read(Harness harness, String id, String path, String body) throws Exception {
        Files.writeString(workspace.resolve(path), body);
        execute(harness, id, path);
        assertTrue(harness.state.toolOutcomes.getLast().success());
    }

    private void compactWithOtherReads(Harness harness) throws Exception {
        read(harness, "provider-b", "b.txt", "second source");
        read(harness, "provider-c", "c.txt", "third source");
        ToolCallSupport.compactOlderToolResultsInPlace(harness.state.messages,
                harness.state.compactedToolResultOriginals);
    }

    private void assertNotRestored(Harness harness, String body) {
        var state = harness.state;
        assertFalse(state.messages.stream().anyMatch(message -> message.content().contains(body)),
                "The obsolete body must be compacted before the restoration check");
        var before = List.copyOf(state.messages);
        try (var ignored = ToolRepromptMessageOverlay.apply(state, List.of(), List.of(), "")) {
            assertFalse(state.messages.stream().anyMatch(message -> message.content().contains(body)));
        }
        assertEquals(before, state.messages);
        assertEquals(1, ReadOnlyEvidenceContext.delivery(state).excludedSourceCount());
        assertEquals(2, ReadOnlyEvidenceContext.delivery(state).carriers().size());
    }

    @Test void failedRefreshAfterSuccessCannotRestoreTheOldBody() throws Exception {
        var harness = harness();
        read(harness, "provider-old", "a.txt", "obsolete source");
        Files.delete(workspace.resolve("a.txt"));
        execute(harness, "provider-failed", "a.txt");
        assertFalse(harness.state.toolOutcomes.getLast().success());
        compactWithOtherReads(harness);
        assertNotRestored(harness, "obsolete source");
    }

    @Test void unrelatedFailureDoesNotExcludeValidEvidence() throws Exception {
        var harness = harness();
        execute(harness, "provider-failed", "missing.txt");
        read(harness, "provider-a", "a.txt", "unaffected source");
        compactWithOtherReads(harness);
        assertEquals(3, ReadOnlyEvidenceContext.delivery(harness.state).carriers().size());
        assertEquals(0, ReadOnlyEvidenceContext.delivery(harness.state).excludedSourceCount());
    }

    @Test void anUnboundCopyOfTheFailedOutcomeCannotBorrowTheOriginalAssociation() throws Exception {
        var harness = harness();
        execute(harness, "provider-failed", "a.txt");
        var failed = harness.state.toolOutcomes.getFirst();
        var copy = failed.withMutationEffects(failed.mutationEffects());
        assertEquals(failed, copy);
        assertNotSame(failed, copy);
        harness.state.toolOutcomes.set(0, copy);
        read(harness, "provider-a", "a.txt", "unbound refresh");
        compactWithOtherReads(harness);
        assertNotRestored(harness, "unbound refresh");
    }

    @Test void reusedProviderIdCannotEstablishSupersedingReadOrder() throws Exception {
        var harness = harness();
        execute(harness, "provider-reused", "a.txt");
        read(harness, "provider-reused", "a.txt", "ambiguous refresh");
        compactWithOtherReads(harness);
        assertNotRestored(harness, "ambiguous refresh");
    }

    @Test void suppressionWithoutAnOutcomeDoesNotRebindThePreviousOutcome() throws Exception {
        var harness = harness();
        execute(harness, "provider-failed", "a.txt");
        read(harness, "provider-a", "a.txt", "fresh source");
        var state = harness.state;
        var successful = state.toolOutcomes.getLast();
        int originalIndex = state.toolOutcomeResults.resultIndex(successful, state.toolOutcomes, state.messages);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var nonStrict = new Harness(state, new ToolCallExecutionStage(
                new TurnProcessor(null, new NoOpApprovalGate(), registry), null, false));
        state.setRuntimeNativeCalls("", List.of(new ChatMessage.NativeToolCall("provider-suppressed",
                        "talos.read_file", Map.of("path", "a.txt"))),
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.DIRECT_RUNTIME_API);
        var parsed = new ToolCallParseStage().parse(state, ++state.iterations);
        nonStrict.stage.execute(state, parsed);
        assertEquals(2, state.toolOutcomes.size());
        assertEquals(1, state.cushionFiresRedundantRead);
        assertEquals(originalIndex, state.toolOutcomeResults.resultIndex(successful, state.toolOutcomes, state.messages));
        compactWithOtherReads(harness);
        assertEquals(3, ReadOnlyEvidenceContext.delivery(state).carriers().size());
    }

    @Test void failedRefreshCannotRestoreInheritedEvidenceButANewReadCanSupersedeIt() throws Exception {
        var harness = harness();
        TurnTaskContractCapture.set(new TaskContract(TaskType.READ_ONLY_QA, false, false, false,
                Set.of("a.txt", "b.txt", "c.txt"), Set.of(), "Read a.txt, b.txt and c.txt without editing."));
        read(harness, "provider-original", "a.txt", "inherited source");
        compactWithOtherReads(harness);
        var handoff = ReadOnlyEvidenceHandoff.capture(harness.state);
        var old = harness.state;
        var nested = new LoopState("", List.of(), new ArrayList<>(old.messages), workspace,
                old.ctx, old.toolSession, 10, 0);
        nested.seedReadOnlyEvidence(handoff);
        assertEquals(3, nested.readEvidence.latestReadFileObservations().size());
        var continuation = new Harness(nested, harness.stage);
        Files.delete(workspace.resolve("a.txt"));
        execute(continuation, "provider-failed", "a.txt");
        assertFalse(nested.toolOutcomes.getLast().success());
        ToolCallSupport.compactOlderToolResultsInPlace(nested.messages, nested.compactedToolResultOriginals);
        assertNotRestored(continuation, "inherited source");
        read(continuation, "provider-new", "a.txt", "replacement source");
        assertEquals(3, ReadOnlyEvidenceContext.delivery(nested).carriers().size());
        assertEquals(0, ReadOnlyEvidenceContext.delivery(nested).excludedSourceCount());
        assertTrue(ReadOnlyEvidenceContext.delivery(nested).carriers().stream()
                .anyMatch(carrier -> carrier.message().toolCallId().equals("provider-new")));
        assertFalse(ReadOnlyEvidenceContext.select(nested).complete());
    }

    @Test void refreshedReadAfterFailureReachesPreparedProviderRequestWithDistinctIds() throws Exception {
        var harness = harness();
        execute(harness, "provider-failed", "a.txt");
        assertFalse(harness.state.toolOutcomes.getLast().success());
        read(harness, "provider-refreshed", "a.txt", "fresh source after failure");
        compactWithOtherReads(harness);
        var state = harness.state;
        var before = List.copyOf(state.messages);
        assertTrue(before.stream().filter(message -> "provider-refreshed".equals(message.toolCallId()))
                .allMatch(message -> message.content().startsWith("[compacted:")));
        assertTrue(ToolRepromptOverlayContinuation.execute(state, List.of(), List.of(), "", false, TOOLS));
        var binding = state.currentResponseReceipt.readInputBinding();
        assertEquals(ProviderReadInputBinding.Purpose.DELIVERY, binding.purpose());
        assertTrue(binding.bound());
        assertEquals(1, binding.preparedToolResults().stream().filter(input ->
                input.toolCallId().equals("provider-refreshed")
                        && input.content().contains("fresh source after failure")).count(),
                "The superseding successful read must reach the prepared request");
        assertEquals(Set.of("provider-failed", "provider-refreshed", "provider-b", "provider-c"),
                binding.preparedToolResults().stream().map(ProviderReadInputBinding.ToolResultInput::toolCallId)
                        .collect(java.util.stream.Collectors.toSet()));
        assertEquals(3, ReadOnlyEvidenceContext.delivery(state).carriers().size());
        assertFalse(ReadOnlyEvidenceContext.select(state).complete());
        assertFalse(state.readOnlyFinalizationEvidence.available());
        assertEquals(1, state.failedCalls);
        assertEquals(before, state.messages);
    }

    private record Harness(LoopState state, ToolCallExecutionStage stage) {}
}
