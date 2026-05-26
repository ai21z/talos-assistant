package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptPathPolicyBlockedDecisionTest {
    @TempDir
    Path workspace;

    @Test
    void ownsPathPolicyBlockedDecisionMechanics() throws Exception {
        String stageSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));
        String decisionSource = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptPathPolicyBlockedDecision.java"));

        assertTrue(stageSource.contains("ToolRepromptPathPolicyBlockedDecision.tryHandle("), stageSource);
        assertFalse(stageSource.contains("ExpectedTargetScopeRepairPlanner.nextPlan("), stageSource);
        assertFalse(stageSource.contains("LocalTurnTraceCapture.recordRepair("), stageSource);
        assertFalse(stageSource.contains(
                "mutating path was blocked by workspace policy before approval"), stageSource);

        assertTrue(decisionSource.contains("ExpectedTargetScopeRepairPlanner.nextPlan("), decisionSource);
        assertTrue(decisionSource.contains("LocalTurnTraceCapture.recordRepair("), decisionSource);
        assertTrue(decisionSource.contains(
                "mutating path was blocked by workspace policy before approval"), decisionSource);
    }

    @Test
    void noPathPolicyBlockReturnsEmptyDecision() {
        LoopState state = loopState("Update README.md.", null);
        var outcome = outcome(false);

        Optional<Boolean> decision = ToolRepromptPathPolicyBlockedDecision.tryHandle(state, outcome);

        assertTrue(decision.isEmpty());
    }

    @Test
    void pathPolicyBlockWithoutRepairPlanStopsWithExistingFailureDecision() {
        LoopState state = loopState("Update README.md.", null);
        state.failureDecision = FailureDecision.stop(FailureAction.ASK_USER, "blocked before approval");
        state.currentNativeCalls = List.of(new ChatMessage.NativeToolCall(
                "stale", "talos.write_file", Map.of("path", "README.md")));

        Optional<Boolean> decision = ToolRepromptPathPolicyBlockedDecision.tryHandle(state, outcome(true));

        assertEquals(Optional.of(false), decision);
        assertEquals(
                "[Tool loop stopped by failure policy: blocked before approval Review the latest tool errors before retrying.]",
                state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void pathPolicyBlockWithExactReplacementRepairSchedulesNativeCall() {
        String request = "Read script.js, then fix the selector bug by changing .missing-button to .cta-button. "
                + "Do not edit scripts.js.";
        LoopState state = loopState(request, null);
        addReadback(state, "script.js", "1 | document.querySelector('.missing-button')\n");
        state.toolOutcomes.add(expectedTargetFailure("scripts.js"));

        Optional<Boolean> decision = ToolRepromptPathPolicyBlockedDecision.tryHandle(state, outcome(true));

        assertEquals(Optional.of(true), decision);
        assertFalse(state.failureDecision.shouldStop());
        assertTrue(state.hasPendingActionObligation());
        assertTrue(state.expectedTargetScopeRepairPromptedKeys.contains("scripts.js->script.js"));
        assertEquals("", state.currentText);
        assertEquals(1, state.currentNativeCalls.size());
        ChatMessage.NativeToolCall repair = state.currentNativeCalls.getFirst();
        assertEquals("runtime_expected_target_repair", repair.id());
        assertEquals("talos.edit_file", repair.name());
        assertEquals("script.js", repair.arguments().get("path"));
        assertEquals(".missing-button", repair.arguments().get("old_string"));
        assertEquals(".cta-button", repair.arguments().get("new_string"));
    }

    private LoopState loopState(String request, LlmClient llm) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm == null
                        ? ScriptedNativeLlmClient.recordingWithContextWindow(
                        List.of(new LlmClient.StreamResult("", List.of())),
                        16_384).client()
                        : llm)
                .nativeToolSpecs(baseTools())
                .build();
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                ctx,
                null,
                10,
                0);
    }

    private static ToolCallExecutionStage.IterationOutcome outcome(boolean pathPolicyBlocked) {
        return new ToolCallExecutionStage.IterationOutcome(
                0,
                List.of(),
                pathPolicyBlocked ? 1 : 0,
                false,
                false,
                pathPolicyBlocked,
                0);
    }

    private static void addReadback(LoopState state, String path, String readback) {
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                path,
                true,
                false,
                false,
                "Read " + path,
                ""));
        state.successfulReadCallBodies.put("talos.read_file:path=" + path + ";", readback);
    }

    private static ToolCallLoop.ToolOutcome expectedTargetFailure(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "Target outside expected targets before approval: attempted `" + path
                        + "` while current expected target set: script.js. Similar filenames are not interchangeable.",
                null,
                ToolError.INVALID_PARAMS);
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"));
    }
}
