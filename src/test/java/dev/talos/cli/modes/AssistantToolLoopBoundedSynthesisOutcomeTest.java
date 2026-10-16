package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantToolLoopBoundedSynthesisOutcomeTest {
    @Test
    void nestedInspectRetryPreservesItsSynthesisAndDoesNotDispatchAfterward(@TempDir Path workspace)
            throws Exception {
        resolveNestedInspectRetry(workspace, 2);
    }

    @Test
    void exhaustedTurnAllowanceDoesNotDispatchNestedInspectionOrSynthesis(@TempDir Path workspace)
            throws Exception {
        resolveNestedInspectRetry(workspace, 1);
    }

    private static void resolveNestedInspectRetry(Path workspace, int totalIterations) throws Exception {
        String html = "<html><body><script src=\"script.js\"></script></body></html>\n";
        Files.writeString(workspace.resolve("index.html"), html);
        Files.writeString(workspace.resolve("script.js"), "const value = 72 / (1.8 * 1.8);\n");
        String request = "Read index.html and script.js and diagnose this website without editing files.";
        var messages = new ArrayList<>(List.of(ChatMessage.system("system"), ChatMessage.user(request)));
        var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of("index.html", "script.js"), Set.of(), request);
        var plan = CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT,
                List.of("talos.read_file"), List.of("talos.read_file"), List.of());
        var original = new ToolCallLoop.LoopResult("Only the HTML was inspected.", 1, 1,
                List.of("talos.read_file"), messages, 0, 0, false, 0, List.of("index.html"),
                0, 0, 0, 0, FailureDecision.continueLoop(),
                List.of(new ToolCallLoop.ToolOutcome("talos.read_file", "index.html", true, false, false,
                        html, "", null)), Map.of("index.html", html), false);
        String selectedAnswer = "The supplied HTML loads script.js. The script computes BMI as about 22.22.";
        var provider = ScriptedNativeLlmClient.recordingWithContextWindow(List.of(
                new LlmClient.StreamResult("", List.of(
                        new ChatMessage.NativeToolCall("r-html", "talos.read_file", Map.of("path", "index.html")),
                        new ChatMessage.NativeToolCall("r-js", "talos.read_file", Map.of("path", "script.js")))),
                new LlmClient.StreamResult(selectedAnswer, List.of())), 16_384);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        var processor = new TurnProcessor(null, new NoOpApprovalGate(), registry);
        var ctx = Context.builder(new Config()).llm(provider.client())
                .sandbox(new Sandbox(workspace, Map.of())).toolRegistry(registry)
                .toolCallLoop(new ToolCallLoop(processor, totalIterations))
                .nativeToolSpecs(registry.descriptors().stream().map(descriptor -> new ToolSpec(
                        descriptor.name(), descriptor.description(), descriptor.parametersSchema())).toList())
                .build();
        var phases = new ArrayList<ToolCallLoop.LoopResult>();
        LocalTurnTraceCapture.begin("nested-read-synthesis", "tests", 1, "2026-09-09T00:00:00Z",
                "workspace", "auto", "llama_cpp", "fixture", request);
        TurnTaskContractCapture.set(contract);
        try {
            var resolution = AssistantToolLoopOutcomeResolver.resolveSelected(
                    original.finalAnswer(), messages, plan, original, workspace, ctx,
                    (selected, mutations) -> phases.add(selected),
                    (answer, selected, mutations, failed) -> {
                        if (totalIterations == 1) {
                            assertSame(original, selected);
                            assertEquals(original.finalAnswer(), answer);
                            assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED,
                                    selected.readOnlySynthesisStatus());
                            assertEquals(1, selected.iterations());
                            assertEquals(1, selected.toolsInvoked());
                            return new AnswerOutputLimit.Presentation(answer, true);
                        }
                        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, selected.readOnlySynthesisStatus());
                        assertEquals(selectedAnswer, selected.finalAnswer());
                        assertTrue(selected.hitIterLimit());
                        assertEquals(2, selected.iterations(), "One original plus one nested iteration");
                        assertEquals(3, selected.toolsInvoked());
                        return new AnswerOutputLimit.Presentation(answer, true);
                    });
            assertEquals(1, phases.size());
            if (totalIterations == 1) {
                assertEquals(original.finalAnswer(), resolution.answer());
                assertSame(original, phases.getFirst());
                assertTrue(provider.requests().isEmpty(), "Exhausted allowance cannot reopen dispatch");
                return;
            }
            assertEquals(selectedAnswer, resolution.answer());
            assertEquals(2, phases.getFirst().iterations());
            assertEquals(2, provider.requests().size(), "Inspection request plus one synthesis, no later recovery");
            assertTrue(provider.requests().getLast().tools.isEmpty());
            assertTrue(provider.requests().getLast().controls.singleAttempt());
        } finally {
            provider.client().close();
            TurnTaskContractCapture.clear();
            LocalTurnTraceCapture.clear();
        }
    }

    @Test
    void acceptedSynthesisStillRunsPhaseAndAuthoritativeAnswerShaping() {
        var loop = loop("Evidence-based answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, false);
        var calls = new ArrayList<String>();
        var resolution = AssistantToolLoopOutcomeResolver.resolveSelected(
                loop.finalAnswer(), messages(), plan(), loop, null, null,
                (selected, mutations) -> {
                    assertSame(loop, selected);
                    assertEquals(0, mutations);
                    calls.add("phase");
                },
                (answer, selected, mutations, failedObligation) -> {
                    assertEquals(List.of("phase"), calls);
                    assertSame(loop, selected);
                    assertEquals(loop.finalAnswer(), answer);
                    assertEquals(0, mutations);
                    assertFalse(failedObligation);
                    calls.add("shape");
                    return new AnswerOutputLimit.Presentation("Safety-shaped answer.", false);
                });

        assertEquals(List.of("phase", "shape"), calls);
        assertEquals("Safety-shaped answer.", resolution.answer());
        assertFalse(resolution.outputLimitReached());
        assertTrue(resolution.extraSummary().contains("reached"));
        assertTrue(loop.hitIterLimit());
    }

    @Test
    void rejectedBlankSynthesisDoesNotDispatchAnySpeculativeRecovery() {
        var loop = loop("", ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, false);
        var calls = new ArrayList<String>();
        // A null context is deliberate: any speculative dispatch would dereference
        // it. Even a blank answer and missing required reads cannot reopen recovery.
        var resolution = AssistantToolLoopOutcomeResolver.resolveSelected(
                "", messages(), plan(), loop, null, null,
                (selected, mutations) -> calls.add("phase"),
                (answer, selected, mutations, failedObligation) -> {
                    calls.add("shape");
                    return new AnswerOutputLimit.Presentation("Truthful incomplete answer.", false);
                });

        assertEquals(List.of("phase", "shape"), calls);
        assertEquals("Truthful incomplete answer.", resolution.answer());
        assertTrue(resolution.extraSummary().contains("failure policy stopped"));
    }

    @Test
    void synthesisStatusDoesNotRollBackAnAlreadyChangedInputCandidate() {
        var loop = loop("Original accepted answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, false);
        var resolution = AssistantToolLoopOutcomeResolver.resolveSelected(
                "Existing containment answer.", messages(), plan(), loop, null, null,
                (selected, mutations) -> {},
                (answer, selected, mutations, failedObligation) -> {
                    assertEquals("Existing containment answer.", answer);
                    return new AnswerOutputLimit.Presentation(answer, true);
                });
        assertEquals("Existing containment answer.", resolution.answer());
    }

    @Test
    void shapingKeepsOrClearsOutputLimitWithoutLosingItsOwnership() {
        var loop = loop("Incomplete answer.", ToolCallLoop.ReadOnlySynthesisStatus.REJECTED, true);
        var retained = AssistantToolLoopOutcomeResolver.resolveSelected(
                loop.finalAnswer(), messages(), plan(), loop, null, null,
                (selected, mutations) -> {},
                (answer, selected, mutations, failedObligation) -> new AnswerOutputLimit.Presentation(answer, true));
        var replaced = AssistantToolLoopOutcomeResolver.resolveSelected(
                loop.finalAnswer(), messages(), plan(), loop, null, null,
                (selected, mutations) -> {},
                (answer, selected, mutations, failedObligation) -> new AnswerOutputLimit.Presentation("Contained.", false));
        assertTrue(retained.outputLimitReached());
        assertFalse(replaced.outputLimitReached());
    }

    @Test
    void shapingControlFlowFailurePropagatesWithoutAnotherRecovery() {
        var loop = loop("Answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED, false);
        var cancellation = new java.util.concurrent.CancellationException("cancelled while shaping");
        var thrown = assertThrows(java.util.concurrent.CancellationException.class,
                () -> AssistantToolLoopOutcomeResolver.resolveSelected(
                        loop.finalAnswer(), messages(), plan(), loop, null, null,
                        (selected, mutations) -> {},
                        (answer, selected, mutations, failedObligation) -> { throw cancellation; }));
        assertSame(cancellation, thrown);
    }

    private static ToolCallLoop.LoopResult loop(String answer, ToolCallLoop.ReadOnlySynthesisStatus status,
                                               boolean outputLimited) {
        return new ToolCallLoop.LoopResult(answer, 10, 4, List.of("talos.read_file"), messages(),
                0, 0, true, 0, List.of(), 0, 0, 0, 0,
                status == ToolCallLoop.ReadOnlySynthesisStatus.REJECTED
                        ? FailureDecision.stop(FailureAction.ASK_USER, "READ_ONLY_SYNTHESIS_UNAVAILABLE")
                        : FailureDecision.continueLoop(),
                List.of(), Map.of(), outputLimited, status);
    }

    private static List<ChatMessage> messages() {
        return List.of(ChatMessage.user("Diagnose notes.md and details.md without changing files."));
    }

    private static CurrentTurnPlan plan() {
        var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                Set.of("notes.md", "details.md"), Set.of(), messages().getFirst().content());
        return CurrentTurnPlan.create(contract, ExecutionPhase.INSPECT, List.of("talos.read_file"),
                List.of(), List.of());
    }
}
