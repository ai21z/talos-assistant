package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ContextLedgerCapture;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.Session;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnSourceEvidenceCapture;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.failure.FailurePolicy;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
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

/** Real file reads use runtime-owned native proposals through parsing, execution and binding. */
class ReadOnlyEvidenceProgressTest {
    private static final String REQUEST = "Read retry.py and settings.json and explain the behavior. Do not edit files.";
    @TempDir Path workspace;

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        TurnSourceEvidenceCapture.clear();
        LocalTurnTraceCapture.clear();
        ContextLedgerCapture.clear();
    }

    @Test void executedSameFileAliasesReachNoProgressBeforeTheSharedCap() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        for (int index = 1; index <= 3; index++)
            Files.createLink(workspace.resolve("alias-" + index + ".py"), workspace.resolve("retry.py"));
        var harness = harness(TaskType.READ_ONLY_QA, false);
        var policy = FailurePolicy.defaults(harness.state().maxIterations);
        var first = read(harness, Map.of("path", "retry.py"));
        assertEquals(1, first.progressSuccessesThisIteration());
        assertFalse(policy.afterIteration(harness.state(), first).shouldStop());
        for (int index = 1; index <= 3; index++) {
            var outcome = read(harness, Map.of("path", "alias-" + index + ".py"));
            assertEquals(1, outcome.successesThisIteration(), "The alias must execute rather than be suppressed");
            assertEquals(1, outcome.unchangedFullReadsThisIteration());
            assertEquals(0, outcome.progressSuccessesThisIteration());
            assertEquals(index == 3, policy.afterIteration(harness.state(), outcome).shouldStop());
            assertEquals(index, harness.state().noProgressIterations);
        }
        assertEquals(4, harness.state().totalToolsInvoked);
        assertEquals(0, harness.state().cushionFiresRedundantRead);
        assertTrue(harness.state().iterations < harness.state().maxIterations);
        var selected = ReadOnlyEvidenceContext.select(harness.state());
        assertTrue(selected.available());
        assertFalse(selected.complete(), "settings.json is still required");
        assertEquals(1, selected.carriers().size());
        assertEquals("native-4", selected.carriers().getFirst().message().toolCallId(),
                "The newest authenticated native binding is retained even without evidence progress");
    }

    @Test void fullReadParameterVariantsDoNotRenewProgress() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        var harness = harness(TaskType.DIAGNOSE_ONLY, false);
        assertEquals(1, read(harness, Map.of("path", "retry.py")).progressSuccessesThisIteration());
        for (int maxLines : List.of(10, 20, 30)) {
            var outcome = read(harness, Map.of("path", "retry.py", "max_lines", maxLines));
            assertEquals(1, outcome.successesThisIteration());
            assertEquals(1, outcome.unchangedFullReadsThisIteration());
            assertEquals(0, outcome.progressSuccessesThisIteration());
        }
        assertEquals(4, harness.state().totalToolsInvoked);
        assertEquals(0, harness.state().cushionFiresRedundantRead);
    }

    @Test void aFreshNativeIdIsNotProgressWhenStrictModeExecutesTheSameCall() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        var harness = harness(TaskType.DIAGNOSE_ONLY, true);
        assertEquals(1, read(harness, Map.of("path", "retry.py")).progressSuccessesThisIteration());
        var repeated = read(harness, Map.of("path", "retry.py"));
        assertEquals(1, repeated.successesThisIteration());
        assertEquals(0, repeated.progressSuccessesThisIteration());
        assertEquals(0, harness.state().cushionFiresRedundantRead);
        var latest = harness.state().readEvidence.latestReadFileForPath("retry.py", true);
        assertEquals("native-2", harness.state().readEvidence.toolResult(latest).message().toolCallId());
    }

    @Test void distinctPhysicalSourcesWithIdenticalBodiesBothAdvanceProgress() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        Files.writeString(workspace.resolve("other.py"), "attempts = 2\n");
        assertFalse(Files.isSameFile(workspace.resolve("retry.py"), workspace.resolve("other.py")));
        var harness = harness(TaskType.READ_ONLY_QA, false);
        assertEquals(1, read(harness, Map.of("path", "retry.py")).progressSuccessesThisIteration());
        var second = read(harness, Map.of("path", "other.py"));
        assertEquals(1, second.progressSuccessesThisIteration());
        assertEquals(0, second.unchangedFullReadsThisIteration());
        assertEquals(2, ReadOnlyEvidenceContext.select(harness.state()).carriers().size());
    }

    @Test void changedBodyThroughAnAliasAdvancesProgressAndResetsTheStreak() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        Files.createLink(workspace.resolve("alias.py"), workspace.resolve("retry.py"));
        var harness = harness(TaskType.DIAGNOSE_ONLY, false);
        var policy = FailurePolicy.defaults(10);
        policy.afterIteration(harness.state(), read(harness, Map.of("path", "retry.py")));
        policy.afterIteration(harness.state(), read(harness, Map.of("path", "retry.py", "max_lines", 10)));
        assertEquals(1, harness.state().noProgressIterations);
        Files.writeString(workspace.resolve("alias.py"), "attempts = 3\n");
        var changed = read(harness, Map.of("path", "alias.py"));
        assertEquals(1, changed.progressSuccessesThisIteration());
        assertEquals(0, changed.unchangedFullReadsThisIteration());
        assertFalse(policy.afterIteration(harness.state(), changed).shouldStop());
        assertEquals(0, harness.state().noProgressIterations);
    }

    @Test void fullerCoverageAdvancesProgressEvenWhenAnExplicitRangeReturnedTheSameBody() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\nretry = True\n");
        for (Map<String, Object> initial : List.<Map<String, Object>>of(
                Map.of("path", "retry.py", "max_lines", 1),
                Map.of("path", "retry.py", "offset", 1))) {
            var harness = harness(TaskType.DIAGNOSE_ONLY, false);
            read(harness, initial);
            var prior = harness.state().readEvidence.latestReadFileForPath("retry.py", true);
            assertTrue(prior.ranged() || prior.truncated());
            var full = read(harness, Map.of("path", "retry.py"));
            assertEquals(1, full.progressSuccessesThisIteration());
            assertEquals(0, full.unchangedFullReadsThisIteration());
            var latest = harness.state().readEvidence.latestReadFileForPath("retry.py", true);
            assertFalse(latest.ranged());
            assertFalse(latest.truncated());
            if (initial.containsKey("offset")) assertEquals(prior.body(), latest.body());
        }
    }

    @Test void aNewRangedReadIsNeverClassifiedAsAnUnchangedFullObservation() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        var harness = harness(TaskType.DIAGNOSE_ONLY, false);
        read(harness, Map.of("path", "retry.py"));
        var ranged = read(harness, Map.of("path", "retry.py", "offset", 1));
        assertEquals(1, ranged.successesThisIteration());
        assertEquals(0, ranged.unchangedFullReadsThisIteration());
        assertFalse(ReadOnlyEvidenceContext.select(harness.state()).available());
    }

    @Test void aLedgerEntryWithoutANativeBindingCannotSuppressEvidenceProgress() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        var harness = harness(TaskType.DIAGNOSE_ONLY, true);
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(harness.state(),
                new ToolCall("talos.read_file", Map.of("path", "retry.py")), "retry.py",
                ToolResult.ok("1 | attempts = 2\n"));
        var firstBoundRead = read(harness, Map.of("path", "retry.py"));
        assertEquals(1, firstBoundRead.progressSuccessesThisIteration());
        assertEquals(0, firstBoundRead.unchangedFullReadsThisIteration());
    }

    @Test void handoffEvidenceIsTheBaselineForAnExecutedAliasRead() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        Files.createLink(workspace.resolve("alias.py"), workspace.resolve("retry.py"));
        var source = harness(TaskType.READ_ONLY_QA, false);
        read(source, Map.of("path", "retry.py"));
        var handoff = ReadOnlyEvidenceHandoff.capture(source.state());
        var retryState = new LoopState("", List.of(), new ArrayList<>(source.state().messages),
                workspace, source.state().ctx, source.state().toolSession, 9, 0);
        handoff.seed(retryState);
        assertFalse(retryState.readEvidence.isEmpty());
        assertEquals(0, retryState.totalToolsInvoked);
        var repeated = read(new Harness(source.stage(), retryState), Map.of("path", "alias.py"));
        assertEquals(1, repeated.successesThisIteration());
        assertEquals(0, repeated.progressSuccessesThisIteration());
        assertFalse(FailurePolicy.defaults(9).afterIteration(retryState, repeated).shouldStop());
        assertEquals(1, retryState.noProgressIterations);
    }

    @Test void unrelatedOrMutationPermittingContractsKeepExistingSuccessSemantics() throws Exception {
        Files.writeString(workspace.resolve("retry.py"), "attempts = 2\n");
        for (boolean mutationAllowed : List.of(false, true)) {
            var harness = harness(TaskType.WORKSPACE_EXPLAIN, true);
            if (mutationAllowed) TurnTaskContractCapture.set(new TaskContract(TaskType.DIAGNOSE_ONLY,
                    true, true, false, Set.of("retry.py"), Set.of(), REQUEST));
            read(harness, Map.of("path", "retry.py"));
            var repeated = read(harness, Map.of("path", "retry.py"));
            assertEquals(1, repeated.successesThisIteration());
            assertEquals(0, repeated.unchangedFullReadsThisIteration());
            harness.state().noProgressIterations = 2;
            assertFalse(FailurePolicy.defaults(10).afterIteration(harness.state(), repeated).shouldStop());
            assertEquals(0, harness.state().noProgressIterations);
        }
    }

    @Test void otherSuccessfulToolsAndMutationEffectsStillResetNoProgress() {
        var harness = harness(TaskType.READ_ONLY_QA, false);
        // Synthetic mixed iteration outcomes isolate the unchanged-read subtraction from other success.
        for (var outcome : List.of(
                new ToolCallExecutionStage.IterationOutcome(0, List.of(), 0, false, false, false,
                        2, List.of(), false, 1),
                new ToolCallExecutionStage.IterationOutcome(1, List.of(), 0, false, false, false,
                        1, List.of(), false, 1))) {
            harness.state().noProgressIterations = 2;
            assertFalse(FailurePolicy.defaults(10).afterIteration(harness.state(), outcome).shouldStop());
            assertEquals(0, harness.state().noProgressIterations);
        }
    }

    private Harness harness(TaskType type, boolean strict) {
        TurnTaskContractCapture.set(new TaskContract(type, false, false, false,
                Set.of("retry.py", "settings.json"), Set.of(), REQUEST));
        LocalTurnTraceCapture.begin("read-evidence-progress", "tests", 1, "2026-09-27T00:00:00Z",
                "workspace", "auto", "scripted", "fixture", REQUEST);
        Config config = new Config(null);
        var registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of()))
                .toolRegistry(registry).build();
        var state = new LoopState("", List.of(), new ArrayList<>(List.of(
                ChatMessage.system("Inspect the requested source files."), ChatMessage.user(REQUEST))),
                workspace, context, new Session(workspace, config), 10, 0);
        return new Harness(new ToolCallExecutionStage(
                new TurnProcessor(null, new NoOpApprovalGate(), registry), null, strict), state);
    }

    private static ToolCallExecutionStage.IterationOutcome read(Harness harness, Map<String, Object> arguments) {
        var state = harness.state();
        state.iterations++;
        state.setRuntimeNativeCalls("", List.of(new ChatMessage.NativeToolCall(
                        "native-" + (state.messages.size() / 2), "talos.read_file", arguments)),
                LocalTurnTraceCapture.RuntimeToolAuthorityReason.DIRECT_RUNTIME_API);
        var parsed = new ToolCallParseStage().parse(state, state.iterations);
        assertEquals(1, parsed.calls().size());
        var outcome = harness.stage().execute(state, parsed);
        assertEquals(0, outcome.failuresThisIteration());
        assertFalse(outcome.executionAuthorityRejectedThisIteration());
        assertEquals(0, outcome.mutationsThisIteration());
        return outcome;
    }

    private record Harness(ToolCallExecutionStage stage, LoopState state) {}
}
