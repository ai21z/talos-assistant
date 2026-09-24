package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.cli.modes.ModeController;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.*;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.*;
import dev.talos.tools.*;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.*;

/** Dispatch binding is exercised with real provider receipts and synthetic prior-read state. */
class DocumentComparisonContinuationTest {
    private static final String REQUEST = "Compare a.txt and b.txt.";
    @TempDir Path workspace;
    private final List<LlmClient> clients = new ArrayList<>();

    @AfterEach void clear() {
        clients.forEach(LlmClient::close);
        TurnAuditCapture.end();
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        Thread.interrupted();
    }

    @Test void narrowedReceiptIsNotReusedAsComparisonExecutionAuthority() throws Exception {
        var fixture = fixture(true, REQUEST);
        var state = fixture.state();
        var narrowed = fixture.client().client().chatFull(state.messages, List.of(readSpec()), ChatRequestControls.defaults()
                .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));
        state.applyProviderResult(narrowed);
        assertEquals(List.of("talos.read_file"), ToolRepromptRequestBuilder.currentNativeToolSpecs(state).stream().map(ToolSpec::name).toList());
        var previous = state.currentResponseReceipt;
        assertTrue(DocumentComparisonContinuation.tryHandle(state).orElseThrow());
        assertNotSame(previous, state.currentResponseReceipt);
        assertEquals(List.of(DocumentComparisonTool.NAME), state.currentResponseReceipt.toolAuthorityContract().selectedToolNames());
        var request = fixture.client().requests().getLast();
        assertEquals(ToolChoiceMode.REQUIRED, request.controls.toolChoice());
        assertEquals(List.of(compareSpec()), request.tools);
        assertTrue(request.messages.stream().noneMatch(message -> message.role().equals("tool")));
        assertEquals(0, state.totalToolsInvoked);
    }

    @Test void laterRegistryAvailabilityCannotAddAnInitiallyAbsentCapability() throws Exception {
        var fixture = fixture(false, REQUEST);
        fixture.context().tools = List.of(readSpec(), compareSpec());
        fixture.client().client().setToolSpecs(fixture.context().tools);
        assertNull(fixture.state().comparisonScope);
        assertTrue(DocumentComparisonContinuation.tryHandle(fixture.state()).isEmpty());
        assertEquals(1, fixture.client().requests().size());
    }

    @ParameterizedTest @ValueSource(strings = {"override-removed", "override-changed", "override-duplicate",
            "next-turn", "no-turn", "equal-contract", "new-request", "no-receipt", "no-authority", "stale-provider-scope",
            "mutation", "mutation-success", "withheld", "failure", "denial", "failed-outcome", "stopped", "synthesis"})
    void invalidatedScopeCannotDispatch(String variation) throws Exception {
        var fixture = fixture(true, REQUEST);
        var state = fixture.state();
        switch (variation) {
            case "override-removed" -> fixture.context().tools = List.of(readSpec());
            case "override-changed" -> fixture.context().tools = List.of(new ToolSpec(DocumentComparisonTool.NAME, "Changed descriptor", "{}"));
            case "override-duplicate" -> fixture.context().tools = List.of(compareSpec(), compareSpec());
            case "next-turn" -> TurnAuditCapture.begin();
            case "no-turn" -> TurnAuditCapture.end();
            case "equal-contract" -> TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(REQUEST));
            case "new-request" -> TurnUserRequestCapture.set("Compare a.txt and c.txt.");
            case "no-receipt" -> state.currentResponseReceipt = null;
            case "no-authority" -> state.currentResponseReceipt = ProviderResponseCapture.Receipt.none();
            case "stale-provider-scope" -> ProviderResponseCapture.clear();
            case "mutation" -> state.mutationSinceStart = true;
            case "mutation-success" -> state.mutatingToolSuccesses = 1;
            case "withheld" -> state.contentWithheldFromModelContext = true;
            case "failure" -> state.failedCalls = 1;
            case "denial" -> state.toolOutcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", "a.txt", false, false, true, "", "denied"));
            case "failed-outcome" -> state.toolOutcomes.add(new ToolCallLoop.ToolOutcome("talos.read_file", "a.txt", false, false, false, "", "failed"));
            case "stopped" -> state.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, "fixture stop");
            case "synthesis" -> state.readOnlySynthesisStatus = ToolCallLoop.ReadOnlySynthesisStatus.REJECTED;
            default -> fail(variation);
        }
        assertTrue(DocumentComparisonContinuation.tryHandle(state).isEmpty());
        if (variation.equals("stale-provider-scope")) assertNull(DocumentComparisonContinuation.captureScope(state));
        assertEquals(1, fixture.client().requests().size());
    }

    @Test void diagnosisCannotAdvanceWhileRequiredReadsAreMissing() throws Exception {
        var fixture = fixture(true, "Read a.txt and b.txt. Diagnose why the button fails. Compare a.txt and b.txt. Do not edit files.");
        assertEquals(dev.talos.runtime.task.TaskType.DIAGNOSE_ONLY, TurnTaskContractCapture.get().type());
        assertFalse(ReadOnlyEvidenceContext.select(fixture.state()).complete());
        assertTrue(DocumentComparisonContinuation.tryHandle(fixture.state()).isEmpty());
        assertEquals(1, fixture.client().requests().size());
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void observedPairsAreNotRequestedAgainEvenWhenDiffIsIncomplete(boolean workLimited) throws Exception {
        var fixture = fixture(true, REQUEST);
        Files.writeString(workspace.resolve("a.txt"), workLimited ? "x\n".repeat(600) : "before\n");
        Files.writeString(workspace.resolve("b.txt"), workLimited ? "y\n".repeat(600) : "after\n");
        var registry = new ToolRegistry();
        registry.register(new DocumentComparisonTool());
        var gateway = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var produced = gateway.executeTool(new Session(workspace, fixture.context().cfg()),
                new ToolCall(DocumentComparisonTool.NAME, Map.of("left", "a.txt", "right", "b.txt")), fixture.context());
        var observations = TurnAuditCapture.currentComparisons(REQUEST, workspace);
        assertEquals(1, observations.size(), produced.errorMessage());
        assertEquals(workLimited ? dev.talos.core.diff.DocumentTextDiff.Status.WORK_LIMIT : dev.talos.core.diff.DocumentTextDiff.Status.COMPLETE,
                observations.getFirst().result().status());
        assertTrue(DocumentComparisonContinuation.pendingPairs(fixture.state()).isEmpty());
        assertTrue(DocumentComparisonContinuation.tryHandle(fixture.state()).isEmpty());
        assertEquals(1, fixture.client().requests().size());
    }

    @Test void onlyUnobservedOrderedPairsAreRequested() throws Exception {
        String request = "Compare a.txt and b.txt. Compare b.txt and c.txt.";
        var fixture = fixture(true, request);
        for (String path : List.of("a.txt", "b.txt", "c.txt")) Files.writeString(workspace.resolve(path), path);
        var registry = new ToolRegistry();
        registry.register(new DocumentComparisonTool());
        new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry).executeTool(
                new Session(workspace, fixture.context().cfg()), new ToolCall(DocumentComparisonTool.NAME, Map.of("left", "a.txt", "right", "b.txt")), fixture.context());
        var pending = DocumentComparisonContinuation.pendingPairs(fixture.state());
        assertEquals(List.of(new dev.talos.core.diff.DocumentPairAdmission.RequestedPair("b.txt", "c.txt")), pending);
        assertTrue(DocumentComparisonContinuation.tryHandle(fixture.state()).orElseThrow());
        String last = fixture.client().requests().getLast().messages.getLast().content();
        assertFalse(last.contains("a.txt"));
        assertTrue(last.contains("b.txt"));
        assertTrue(last.contains("c.txt"));
    }

    @ParameterizedTest @ValueSource(strings = {"limit", "empty", "exception", "cancel", "interrupt"})
    void unsuccessfulContinuationIsBoundedAndCancellationIsPreserved(String variation) throws Exception {
        var fixture = fixture(true, REQUEST, variation);
        var state = fixture.state();
        if (variation.equals("limit")) state.iterations = state.maxIterations;
        if (variation.equals("interrupt")) Thread.currentThread().interrupt();
        if (variation.equals("cancel") || variation.equals("interrupt")) {
            assertThrows(RuntimeException.class, () -> DocumentComparisonContinuation.tryHandle(state));
        } else {
            assertFalse(DocumentComparisonContinuation.tryHandle(state).orElseThrow());
            assertTrue(state.failureDecision.shouldStop());
            assertTrue(DocumentComparisonContinuation.tryHandle(state).isEmpty());
        }
        assertEquals(variation.equals("limit") || variation.equals("interrupt") ? 1 : 2, fixture.client().requests().size());
        assertEquals(0, state.totalToolsInvoked);
    }

    private Fixture fixture(boolean initialComparison, String request) throws Exception {
        return fixture(initialComparison, request, "normal");
    }

    private Fixture fixture(boolean initialComparison, String request, String response) throws Exception {
        TurnAuditCapture.begin();
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
        LocalTurnTraceCapture.begin("comparison-dispatch", "test", 1, "2026-09-24T00:00:00Z", "workspace", "ask", "fixture", "fixture", request);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var client = ScriptedNativeLlmClient.recordingResponder(ignored -> {
            if (count.getAndIncrement() == 0) return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("read", "talos.read_file", Map.of("path", "a.txt"))));
            if (response.equals("exception")) throw new IllegalStateException("Synthetic provider failure");
            if (response.equals("cancel")) throw new CancellationException("Synthetic cancellation");
            if (response.equals("empty")) return new LlmClient.StreamResult("No call", List.of());
            return new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall("compare", DocumentComparisonTool.NAME, Map.of("left", "a.txt", "right", "b.txt"))));
        }, 16_384);
        clients.add(client.client());
        var specs = initialComparison ? List.of(readSpec(), compareSpec()) : List.of(readSpec());
        client.client().setToolSpecs(specs);
        Config config = new Config(null);
        var context = new MutableContext(Context.builder(config).llm(client.client()).sandbox(new Sandbox(workspace, config.data)).build(), specs);
        var messages = new ArrayList<>(List.of(ChatMessage.user(request)));
        var initial = client.client().chatFull(messages, specs, ChatRequestControls.defaults()
                .withToolAuthorityMode(ProviderResponseCapture.ToolAuthorityMode.PROVIDER_NATIVE));
        var state = new LoopState(initial.text(), initial.toolCalls(), initial.provenance(), messages, workspace, context, null, 10, 0);
        state.iterations = 1;
        return new Fixture(state, client, context);
    }

    private static ToolSpec readSpec() {
        var descriptor = new ReadFileTool().descriptor();
        return new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema());
    }
    private static ToolSpec compareSpec() {
        var descriptor = new DocumentComparisonTool().descriptor();
        return new ToolSpec(descriptor.name(), descriptor.description(), descriptor.parametersSchema());
    }
    private record Fixture(LoopState state, ScriptedNativeLlmClient.RecordedClient client, MutableContext context) {}

    private static final class MutableContext implements RuntimeTurnContext {
        private final Context delegate;
        private List<ToolSpec> tools;
        private MutableContext(Context delegate, List<ToolSpec> tools) { this.delegate = delegate; this.tools = tools; }
        public Config cfg() { return delegate.cfg(); }
        public LlmClient llm() { return delegate.llm(); }
        public Sandbox sandbox() { return delegate.sandbox(); }
        public ExecutionPhaseState executionPhaseState() { return delegate.executionPhaseState(); }
        public List<ToolSpec> nativeToolSpecs() { return tools; }
    }
}
