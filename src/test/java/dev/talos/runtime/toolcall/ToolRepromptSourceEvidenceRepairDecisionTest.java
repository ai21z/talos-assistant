package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Session;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ObservedProviderTransportFixture;
import dev.talos.spi.types.ProviderResponseCapture;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.ToolChoiceMode;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptSourceEvidenceRepairDecisionTest {
    @TempDir
    Path workspace;

    @AfterEach
    void clearTurnCaptures() {
        LocalTurnTraceCapture.clear();
        dev.talos.runtime.TurnTaskContractCapture.clear();
        ProviderResponseCapture.clear();
    }

    @Test
    void ownsSourceEvidenceRepairDecisionMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptSourceEvidenceRepairDecision.java"));

        assertTrue(source.contains("SourceEvidenceExactRepairPlanner.nextPlan("), source);
        assertTrue(source.contains("sourceEvidenceExactRepairPromptedKeys.add"), source);
        assertTrue(source.contains("PendingActionObligation.expectedTargets"), source);
        assertTrue(source.contains("source-evidence exact compact repair"), source);
    }

    @Test
    void noSourceEvidenceRepairPlanReturnsEmptyDecision() {
        LoopState state = state("Update README.md.", List.of(new LlmClient.StreamResult("", List.of())));

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, "Update README.md.");

        assertTrue(decision.isEmpty());
    }

    @Test
    void sourceEvidenceRepairPlanRaisesObligationAndExecutesCompactRetry() {
        ChatMessage.NativeToolCall repairCall = new ChatMessage.NativeToolCall(
                "repair-1",
                "talos.write_file",
                Map.of("path", "office-summary.md", "content", "Board brief marker: ORBITAL-DECK-71."));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(repairCall))),
                16_384);
        String request = sourceEvidenceRequest();
        LoopState state = state(request, recorded.client());
        addSourceReadbacks(state);
        state.toolOutcomes.add(failedSourceEvidenceWrite("office-summary.md"));

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertTrue(state.hasPendingActionObligation());
        assertEquals(1, state.sourceEvidenceExactRepairPromptedKeys.size());
        assertTrue(state.sourceEvidenceExactRepairPromptedKeys.iterator().next()
                .startsWith("office-summary.md->"), state.sourceEvidenceExactRepairPromptedKeys.toString());
        assertEquals(List.of(repairCall), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[SourceEvidenceExactRepair] Target: office-summary.md"), prompt);
        assertTrue(prompt.contains("Board brief marker: ORBITAL-DECK-71."), prompt);
    }

    @Test
    void sourceEvidenceWriteBeforeReadRepromptsForMissingSourceReadFirst() {
        ChatMessage.NativeToolCall readCall = new ChatMessage.NativeToolCall(
                "read-1",
                "talos.read_file",
                Map.of("path", "problem.md", "max_lines", 200));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(readCall))),
                16_384);
        String request = "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        LoopState state = state(request, recorded.client());
        state.toolOutcomes.add(failedSourceEvidenceReadBeforeWrite("dijkstra.py"));
        state.toolOutcomes.add(failedSourceEvidenceReadBeforeWrite("test_dijkstra.py"));

        var contract =
                dev.talos.runtime.TurnTaskContractCapture
                        .currentOrResolve(state.messages);
        assertEquals(
                java.util.Set.of("problem.md"),
                contract.sourceEvidenceTargets());
        assertEquals(
                java.util.Set.of("dijkstra.py", "test_dijkstra.py"),
                contract.expectedTargets());
        assertEquals(
                List.of("problem.md"),
                SourceDerivedEvidenceGuard
                        .missingSourceEvidenceTargets(state, contract));
        assertEquals(
                List.of("dijkstra.py", "test_dijkstra.py"),
                ExpectedTargetProgressAccounting
                        .remainingExpectedMutationTargets(state));
        assertTrue(state.ctx.hasNativeToolSpecOverride());
        assertEquals(
                List.of(
                        "talos.read_file",
                        "talos.write_file",
                        "talos.edit_file"),
                state.ctx.nativeToolSpecs()
                        .stream()
                        .map(ToolSpec::name)
                        .toList());
        assertTrue(
                state.currentResponseReceipt
                        .toolAuthorityContract()
                        .bound());
        assertEquals(
                List.of(
                        "talos.read_file",
                        "talos.write_file",
                        "talos.edit_file"),
                state.currentResponseReceipt
                        .toolAuthorityContract()
                        .selectedToolNames());
        assertEquals(
                List.of(
                        "talos.read_file",
                        "talos.write_file",
                        "talos.edit_file"),
                ToolRepromptRequestBuilder
                        .currentNativeToolSpecs(state)
                        .stream()
                        .map(ToolSpec::name)
                        .toList());

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertFalse(state.hasPendingActionObligation(), "source-read repair must not raise a mutation obligation");
        assertEquals(List.of(readCall), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[SourceEvidenceReadBeforeWriteRepair]"), prompt);
        assertTrue(prompt.contains("Missing source target(s): problem.md"), prompt);
        assertTrue(prompt.contains("Call talos.read_file for the missing source target(s) first"), prompt);
        assertFalse(prompt.contains("[Expected target progress]"), prompt);
        assertEquals(
                List.of("talos.read_file", "talos.write_file"),
                recorded.requests().getFirst().tools.stream()
                        .map(ToolSpec::name)
                        .toList());
        assertEquals(
                List.of("talos.read_file", "talos.write_file"),
                state.currentResponseReceipt.toolAuthorityContract()
                        .selectedToolNames());
        assertEquals(
                List.of("talos.read_file"),
                state.currentResponseReceipt.toolAuthorityContract()
                        .authorizedToolNames());
    }

    @Test
    void sourceEvidenceAfterReadRepromptsForRemainingExpectedWrites() {
        ChatMessage.NativeToolCall writeDijkstra = new ChatMessage.NativeToolCall(
                "write-1",
                "talos.write_file",
                Map.of(
                        "path", "dijkstra.py",
                        "content", "def shortest_path(graph, start, goal):\n    return 5\n"));
        ChatMessage.NativeToolCall writeTest = new ChatMessage.NativeToolCall(
                "write-2",
                "talos.write_file",
                Map.of(
                        "path", "test_dijkstra.py",
                        "content", "from dijkstra import shortest_path\n\n"
                                + "def test_sample_graph():\n    assert shortest_path({}, 'A', 'C') == 5\n"));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(writeDijkstra, writeTest))),
                16_384);
        String request = "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        LoopState state = state(request, recorded.client());
        state.toolOutcomes.add(failedSourceEvidenceReadBeforeWrite("dijkstra.py"));
        state.toolOutcomes.add(failedSourceEvidenceReadBeforeWrite("test_dijkstra.py"));
        state.toolOutcomes.add(readOutcome("problem.md"));
        addReadback(state, "problem.md",
                "1 | Implement Dijkstra shortest path for a small weighted directed graph.\n"
                        + "2 | Sample graph A->B cost 2, B->C cost 3, A->C cost 10; expected A to C distance is 5.");

        Optional<Boolean> decision = ToolRepromptSourceEvidenceRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertTrue(state.hasPendingActionObligation(), "post-read repair must require the remaining mutation");
        assertEquals(List.of(writeDijkstra, writeTest), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[SourceEvidencePostReadWriteRepair]"), prompt);
        assertTrue(prompt.contains("Remaining expected target(s): dijkstra.py, test_dijkstra.py"), prompt);
        assertTrue(prompt.contains("Implement Dijkstra shortest path"), prompt);
        assertFalse(prompt.contains("Required exact source evidence phrases"), prompt);
    }

    @Test
    void namedReadAttemptCarriesTheExactContinuationSurfaceButCannotExecuteWrite()
            throws Exception {
        String request =
                "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        ChatMessage.NativeToolCall prematureWrite =
                new ChatMessage.NativeToolCall(
                        "premature-write",
                        "talos.write_file",
                        Map.of(
                                "path",
                                "dijkstra.py",
                                "content",
                                "must not execute during the named read attempt"));
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(
                                new LlmClient.StreamResult(
                                        "",
                                        List.of(prematureWrite))),
                        16_384);
        ToolRegistry registry = sourceTools();
        LoopState state = state(request, recorded.client(), registry);
        state.toolOutcomes.add(
                failedSourceEvidenceReadBeforeWrite("dijkstra.py"));

        beginTrace("named-read-rejects-write");
        Optional<Boolean> decision =
                ToolRepromptSourceEvidenceRepairDecision.tryHandle(
                        state,
                        request);

        assertEquals(Optional.of(true), decision);
        assertEquals(1, recorded.requests().size());
        var dispatched = recorded.requests().getFirst();
        assertEquals(ToolChoiceMode.NAMED, dispatched.controls.toolChoice());
        assertEquals("talos.read_file", dispatched.controls.namedTool());
        assertEquals(
                List.of("talos.read_file", "talos.write_file"),
                dispatched.tools.stream().map(ToolSpec::name).toList());
        String writeSchema =
                dispatched.tools.stream()
                        .filter(tool -> "talos.write_file".equals(tool.name()))
                        .findFirst()
                        .orElseThrow()
                        .parametersSchemaJson();
        assertTrue(writeSchema.contains("\"dijkstra.py\""), writeSchema);
        assertTrue(
                writeSchema.contains("\"test_dijkstra.py\""),
                writeSchema);
        assertFalse(
                dispatched.tools.stream()
                        .map(ToolSpec::name)
                        .anyMatch("talos.edit_file"::equals),
                dispatched.tools.toString());

        ProviderResponseCapture.ToolAuthorityContract authority =
                state.currentResponseReceipt.toolAuthorityContract();
        assertEquals(
                List.of("talos.read_file", "talos.write_file"),
                authority.selectedToolNames());
        assertEquals(
                List.of("talos.read_file"),
                authority.authorizedToolNames());
        assertFalse(
                authority.authorizesExactToolName("talos.write_file"));

        ToolCallExecutionStage.IterationOutcome outcome =
                executeCurrentCalls(state, registry);
        assertEquals(0, outcome.successesThisIteration());
        assertEquals(1, outcome.failuresThisIteration());
        assertFalse(Files.exists(workspace.resolve("dijkstra.py")));
    }

    @Test
    void nonNamedReadAttemptNeverSelectsOrAuthorizesTheFutureWrite() {
        String request =
                "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        var recorded =
                ScriptedNativeLlmClient
                        .recordingTextToolFallbackWithContextWindow(
                                List.of(
                                        new LlmClient.StreamResult(
                                                "{\"name\":\"talos.read_file\","
                                                        + "\"arguments\":{\"path\":\"problem.md\"}}",
                                                List.of())),
                                16_384);
        LoopState state = state(request, recorded.client());
        state.toolOutcomes.add(
                failedSourceEvidenceReadBeforeWrite("dijkstra.py"));

        assertEquals(
                Optional.of(true),
                ToolRepromptSourceEvidenceRepairDecision.tryHandle(
                        state,
                        request));
        ProviderResponseCapture.ToolAuthorityContract authority =
                state.currentResponseReceipt.toolAuthorityContract();
        assertEquals(
                List.of("talos.read_file"),
                authority.selectedToolNames());
        assertEquals(
                List.of("talos.read_file"),
                authority.authorizedToolNames());
        assertFalse(
                authority.authorizesExactToolName("talos.write_file"));
    }

    @Test
    void postReadWriteAuthorityComesOnlyFromTheBoundReadReceiptAndNarrowsToWrite()
            throws Exception {
        Files.writeString(
                workspace.resolve("problem.md"),
                "Implement Dijkstra. The expected A to C distance is 5.\n");
        String request =
                "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        ChatMessage.NativeToolCall read =
                new ChatMessage.NativeToolCall(
                        "read-source",
                        "talos.read_file",
                        Map.of("path", "problem.md", "max_lines", 200));
        ChatMessage.NativeToolCall writeDijkstra =
                new ChatMessage.NativeToolCall(
                        "write-dijkstra",
                        "talos.write_file",
                        Map.of(
                                "path",
                                "dijkstra.py",
                                "content",
                                "def shortest_path(graph, start, goal):\n    return 5\n"));
        ChatMessage.NativeToolCall writeTest =
                new ChatMessage.NativeToolCall(
                        "write-test",
                        "talos.write_file",
                        Map.of(
                                "path",
                                "test_dijkstra.py",
                                "content",
                                "from dijkstra import shortest_path\n"
                                        + "def test_sample():\n"
                                        + "    assert shortest_path({}, 'A', 'C') == 5\n"));
        var recorded =
                ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(
                                new LlmClient.StreamResult("", List.of(read)),
                                new LlmClient.StreamResult(
                                        "",
                                        List.of(
                                                writeDijkstra,
                                                writeTest))),
                        16_384);
        ToolRegistry registry = sourceTools();
        LoopState state = state(request, recorded.client(), registry);
        state.toolOutcomes.add(
                failedSourceEvidenceReadBeforeWrite("dijkstra.py"));
        state.toolOutcomes.add(
                failedSourceEvidenceReadBeforeWrite("test_dijkstra.py"));

        beginTrace("receipt-bound-source-transition");
        assertEquals(
                Optional.of(true),
                ToolRepromptSourceEvidenceRepairDecision.tryHandle(
                        state,
                        request));
        assertEquals(
                1,
                executeCurrentCalls(state, registry).successesThisIteration());
        assertFalse(Files.exists(workspace.resolve("dijkstra.py")));
        ProviderResponseCapture.ToolAuthorityContract readAuthority =
                state.currentResponseReceipt.toolAuthorityContract();
        assertEquals(
                List.of("talos.read_file", "talos.write_file"),
                readAuthority.selectedToolNames());
        assertEquals(
                List.of("talos.read_file"),
                readAuthority.authorizedToolNames());

        assertEquals(
                Optional.of(true),
                ToolRepromptSourceEvidenceRepairDecision.tryHandle(
                        state,
                        request));
        assertEquals(2, recorded.requests().size());
        var postReadRequest = recorded.requests().get(1);
        assertEquals(
                List.of("talos.write_file"),
                postReadRequest.tools.stream().map(ToolSpec::name).toList());
        assertEquals(
                ToolChoiceMode.NAMED,
                postReadRequest.controls.toolChoice());
        assertEquals(
                "talos.write_file",
                postReadRequest.controls.namedTool());
        String postReadSchema =
                postReadRequest.tools.getFirst().parametersSchemaJson();
        assertTrue(
                postReadSchema.contains("\"dijkstra.py\""),
                postReadSchema);
        assertTrue(
                postReadSchema.contains("\"test_dijkstra.py\""),
                postReadSchema);
        assertFalse(
                postReadSchema.contains("problem.md"),
                postReadSchema);
        assertEquals(
                List.of("talos.write_file"),
                state.currentResponseReceipt.toolAuthorityContract()
                        .selectedToolNames());
        assertEquals(
                List.of("talos.write_file"),
                state.currentResponseReceipt.toolAuthorityContract()
                        .authorizedToolNames());

        ToolCallExecutionStage.IterationOutcome writes =
                executeCurrentCalls(state, registry);
        assertEquals(2, writes.successesThisIteration());
        assertEquals(2, writes.mutationsThisIteration());
        assertTrue(
                Files.readString(workspace.resolve("dijkstra.py"))
                        .contains("def shortest_path"));
        assertTrue(
                Files.readString(workspace.resolve("test_dijkstra.py"))
                        .contains("== 5"));
    }

    @Test
    void postReadContinuationFailsClosedWhenTheReadReceiptIsMissing() {
        String request =
                "Create dijkstra.py and test_dijkstra.py according to problem.md, then run pytest if available.";
        LoopState state =
                state(
                        request,
                        ScriptedNativeLlmClient.recordingWithContextWindow(
                                        List.of(
                                                new LlmClient.StreamResult(
                                                        "",
                                                        List.of())),
                                        16_384)
                                .client());
        state.toolOutcomes.add(
                failedSourceEvidenceReadBeforeWrite("dijkstra.py"));
        state.toolOutcomes.add(readOutcome("problem.md"));
        addReadback(
                state,
                "problem.md",
                "1 | Implement Dijkstra; expected A to C distance is 5.");
        state.currentResponseReceipt = ProviderResponseCapture.Receipt.none();

        assertTrue(
                ToolRepromptSourceEvidenceRepairDecision.tryHandle(
                                state,
                                request)
                        .isEmpty(),
                "a registry/context surface without a bound read receipt must not reopen write authority");
    }

    private LoopState state(String request, List<LlmClient.StreamResult> responses) {
        return state(request, ScriptedNativeLlmClient.recordingWithContextWindow(responses, 16_384).client());
    }

    private LoopState state(String request, LlmClient llm) {
        return state(request, llm, sourceTools());
    }

    private LoopState state(
            String request,
            LlmClient llm,
            ToolRegistry registry
    ) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Config config = new Config();
        var ctx = Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .toolRegistry(registry)
                .build();
        return new LoopState(
                "",
                List.of(),
                boundProviderReceipt(
                        baseTools().stream().map(ToolSpec::name).toList()),
                messages,
                workspace,
                ctx,
                new Session(workspace, config),
                10,
                0);
    }

    private static String sourceEvidenceRequest() {
        return "Create office-summary.md summarizing board-brief.md, client-notes.md, and revenue.csv. "
                + "Include one distinctive exact evidence phrase from each source so I can audit source coverage.";
    }

    private static void addSourceReadbacks(LoopState state) {
        state.toolOutcomes.add(readOutcome("board-brief.md"));
        state.toolOutcomes.add(readOutcome("client-notes.md"));
        state.toolOutcomes.add(readOutcome("revenue.csv"));
        addReadback(state, "board-brief.md", "1 | Board brief marker: ORBITAL-DECK-71.");
        addReadback(state, "client-notes.md", "1 | Client note marker: NEON-RESPONSE-44.");
        addReadback(state, "revenue.csv", "1 | Revenue marker: LASER-LEDGER-19");
    }

    private static void addReadback(LoopState state, String path, String readback) {
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", path)),
                path,
                ToolResult.ok(readback));
    }

    private static ToolCallLoop.ToolOutcome readOutcome(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "Read " + path,
                "");
    }

    private static ToolCallLoop.ToolOutcome failedSourceEvidenceWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Source-derived write blocked before approval: " + path
                        + " does not include required exact evidence phrase(s).");
    }

    private static ToolCallLoop.ToolOutcome failedSourceEvidenceReadBeforeWrite(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Source-derived artifact write blocked before approval: the current task requires reading "
                        + "source target(s) problem.md before writing `" + path + "`. Call talos.read_file "
                        + "for the source target(s) first, then retry the write. No approval was requested "
                        + "and no file was changed.");
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }

    private static ToolRegistry sourceTools() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new dev.talos.tools.impl.ReadFileTool());
        registry.register(new dev.talos.tools.impl.FileWriteTool());
        registry.register(new dev.talos.tools.impl.FileEditTool());
        return registry;
    }

    private static ProviderResponseCapture.Receipt boundProviderReceipt(
            List<String> selectedToolNames
    ) {
        ProviderResponseCapture.begin(
                new ProviderResponseCapture.Listener() {},
                "t1196-source-evidence");
        ProviderResponseCapture.Generation generation =
                ProviderResponseCapture.openGeneration(
                        ProviderResponseCapture.Source.FOREGROUND_PROVIDER,
                        ProviderResponseCapture.Purpose.TURN_RESPONSE,
                        ProviderResponseCapture.ToolAuthorityContract.bound(
                                ProviderResponseCapture.ToolAuthorityMode
                                        .PROVIDER_NATIVE,
                                selectedToolNames,
                                ToolChoiceMode.AUTO,
                                ""));
        ProviderResponseCapture.Attempt attempt =
                generation.startAttempt(
                        ProviderResponseCapture.AttemptKind.PRIMARY,
                        ProviderResponseCapture.RetryReason.NONE);
        ObservedProviderTransportFixture.completeTerminal(
                attempt,
                new ProviderTransportHandoff.DispatchEvidence(
                        "sha256:" + "a".repeat(64),
                        selectedToolNames));
        ProviderResponseCapture.Receipt receipt =
                attempt.respond(
                        new ProviderResponseCapture.Response(
                                "",
                                List.of(new ChatMessage.NativeToolCall(
                                        "fixture-prior-call",
                                        "talos.read_file",
                                        Map.of())),
                                ProviderResponseCapture.Source
                                        .FOREGROUND_PROVIDER,
                                ProviderResponseCapture.Completion.PROVIDER_EOS,
                                false,
                                false,
                                false));
        generation.complete(receipt);
        return receipt;
    }

    private static void beginTrace(String suffix) {
        LocalTurnTraceCapture.begin(
                "t1196-source-" + suffix,
                "t1196-source-session",
                1,
                "2026-07-30T00:00:00Z",
                "workspace-hash",
                "test",
                "llama_cpp",
                "test-model",
                suffix);
    }

    private static ToolCallExecutionStage.IterationOutcome executeCurrentCalls(
            LoopState state,
            ToolRegistry registry
    ) {
        ToolCallParseStage.ParsedCalls parsed =
                new ToolCallParseStage().parse(
                        state,
                        state.iterations + 1);
        return new ToolCallExecutionStage(
                        new TurnProcessor(
                                null,
                                new NoOpApprovalGate(),
                                registry),
                        null,
                        false)
                .execute(state, parsed);
    }
}
