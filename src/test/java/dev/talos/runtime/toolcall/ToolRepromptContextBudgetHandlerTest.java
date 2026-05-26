package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.spi.EngineException;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolRepromptContextBudgetHandlerTest {
    @TempDir
    Path workspace;

    @Test
    void contextBudgetWithoutCompactFallbackStopsWithDeterministicAnswer() {
        LoopState state = state("What files are relevant?", LlmClient.scripted("unused"));

        boolean continueLoop = ToolRepromptContextBudgetHandler.handle(
                state,
                budget(),
                "tool-call loop continuation");

        assertFalse(continueLoop);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(FailureAction.ASK_USER, state.failureDecision.action());
        assertTrue(state.failureDecision.reason().contains("Context budget prevented tool-call loop continuation"),
                state.failureDecision.reason());
        assertTrue(state.currentText.toLowerCase().contains("context budget"), state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void pendingActionObligationBreachWinsBeforeFallbacks() {
        LoopState state = state("Create README.md.", LlmClient.scripted("unused"));
        state.setPendingActionObligation(PendingActionObligation.expectedTargets(List.of("README.md")));

        boolean continueLoop = ToolRepromptContextBudgetHandler.handle(
                state,
                budget(),
                "tool-call loop continuation");

        assertFalse(continueLoop);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(FailureAction.ASK_USER, state.failureDecision.action());
        assertTrue(state.failureDecision.reason().contains("EXPECTED_TARGETS_REMAINING"),
                state.failureDecision.reason());
        assertTrue(state.currentText.toLowerCase().contains("context budget"), state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void compactMutationContinuationReturningToolCallsContinuesLoop() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old\n");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(
                        new ChatMessage.NativeToolCall(
                                "compact_write",
                                "talos.write_file",
                                Map.of("path", "README.md", "content", "# New\n"))))),
                16_384);
        LoopState state = mutationState("Rewrite README.md with a short project note.", recorded.client());

        boolean continueLoop = ToolRepromptContextBudgetHandler.handle(
                state,
                budget(),
                "tool-call loop continuation");

        assertTrue(continueLoop);
        assertFalse(state.failureDecision.shouldStop());
        assertEquals(1, state.currentNativeCalls.size());
        assertEquals("talos.write_file", state.currentNativeCalls.get(0).name());
        assertFalse(recorded.requests().isEmpty());
    }

    @Test
    void compactMutationContinuationWithoutToolCallsStopsWithNoActionAnswer() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old\n");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I will update it now.", List.of())),
                16_384);
        LoopState state = mutationState("Rewrite README.md with a short project note.", recorded.client());

        boolean continueLoop = ToolRepromptContextBudgetHandler.handle(
                state,
                budget(),
                "tool-call loop continuation");

        assertFalse(continueLoop);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(FailureAction.ASK_USER, state.failureDecision.action());
        assertTrue(state.failureDecision.reason().contains("COMPACT_MUTATION_CONTINUATION_NO_TOOL"),
                state.failureDecision.reason());
        assertTrue(state.currentText.contains("no file was changed"), state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void repromptStageDelegatesContextBudgetHandlingToOwner() throws Exception {
        String stage = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String overlayContinuation = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptOverlayContinuation.java"));

        assertFalse(stage.contains("ToolRepromptContextBudgetHandler.handle"), stage);
        assertTrue(overlayContinuation.contains("ToolRepromptContextBudgetHandler.handle"), overlayContinuation);
        assertFalse(stage.contains("tryCompactMutationContinuation"), stage);
        assertFalse(stage.contains("CompactMutationContinuationOutcome"), stage);
        assertFalse(stage.contains("private static boolean stopAfterContextBudgetExceeded"), stage);
        assertFalse(stage.contains("private static CompactMutationContinuationOutcome tryCompactMutationContinuation"),
                stage);
        assertFalse(stage.contains("private enum CompactMutationContinuationOutcome"), stage);
    }

    private LoopState mutationState(String request, LlmClient llm) {
        LoopState state = state(request, llm);
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                "README.md",
                true,
                false,
                false,
                "Read README.md",
                ""));
        state.successfulReadCallBodies.put(
                "talos.read_file:path=README.md;",
                "1 | # Old\n");
        return state;
    }

    private LoopState state(String request, LlmClient llm) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .build();
        return new LoopState("", List.of(), messages, workspace, ctx, null, 5, 0);
    }

    private static EngineException.ContextBudgetExceeded budget() {
        return new EngineException.ContextBudgetExceeded(5_946, 5_635, 8_192, 0);
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }
}
