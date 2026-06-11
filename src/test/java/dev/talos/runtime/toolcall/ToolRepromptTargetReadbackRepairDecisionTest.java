package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class ToolRepromptTargetReadbackRepairDecisionTest {
    @TempDir
    Path workspace;

    @Test
    void ownsTargetReadbackRepairDecisionMechanics() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolRepromptTargetReadbackRepairDecision.java"));

        assertTrue(source.contains("TargetReadbackCompactRepairPlanner.nextAppendLinePlan("), source);
        assertTrue(source.contains("TargetReadbackCompactRepairPlanner.nextOldStringMissPlan("), source);
        assertTrue(source.contains("appendLineRepairPromptedPaths.add"), source);
        assertTrue(source.contains("oldStringMissRepairPromptedPaths.add"), source);
        assertTrue(source.contains("PendingActionObligation.appendLineTargets"), source);
        assertTrue(source.contains("PendingActionObligation.oldStringMissTargets"), source);
    }

    @Test
    void noTargetReadbackRepairPlanReturnsEmptyDecision() {
        LoopState state = state("Update README.md.", List.of(new LlmClient.StreamResult("", List.of())));

        Optional<Boolean> decision = ToolRepromptTargetReadbackRepairDecision.tryHandle(state, "Update README.md.");

        assertTrue(decision.isEmpty());
    }

    @Test
    void appendLineRepairPlanRaisesAppendObligationAndExecutesRetry() {
        ChatMessage.NativeToolCall repairCall = new ChatMessage.NativeToolCall(
                "repair-append",
                "talos.write_file",
                Map.of("path", "README.md", "content", "# Demo\nRelease gate note\n"));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(repairCall))),
                16_384);
        String request = "Read README.md, then append exactly this line to README.md: Release gate note";
        LoopState state = state(request, recorded.client());
        addReadback(state, "README.md", "1 | # Demo\n");
        state.toolOutcomes.add(appendLineFailure("README.md"));

        Optional<Boolean> decision = ToolRepromptTargetReadbackRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertTrue(state.hasPendingActionObligation());
        assertTrue(state.appendLineRepairPromptedPaths.contains("readme.md"));
        assertEquals(List.of(repairCall), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        assertTrue(recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right)
                .contains("[AppendLineRepair] Target: README.md"));
    }

    @Test
    void oldStringMissRepairPlanRaisesOldStringObligationAndExecutesRetry() {
        ChatMessage.NativeToolCall repairCall = new ChatMessage.NativeToolCall(
                "repair-old-string",
                "talos.edit_file",
                Map.of("path", "README.md", "old_string", "Original text.", "new_string", "Applied proposal."));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(repairCall))),
                16_384);
        String request = "Edit README.md by replacing Original text. with Applied proposal.";
        LoopState state = state(request, recorded.client());
        addReadback(state, "README.md", "1 | # Fixture\n2 | Original text.\n");
        state.toolOutcomes.add(oldStringMissFailure("README.md"));

        Optional<Boolean> decision = ToolRepromptTargetReadbackRepairDecision.tryHandle(state, request);

        assertEquals(Optional.of(true), decision);
        assertTrue(state.hasPendingActionObligation());
        assertTrue(state.oldStringMissRepairPromptedPaths.contains("readme.md"));
        assertEquals(List.of(repairCall), state.currentNativeCalls);
        assertEquals(1, recorded.requests().size());
        assertTrue(recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .filter(content -> content != null)
                .reduce("", (left, right) -> left + "\n" + right)
                .contains("[OldStringMissRepair] Target: README.md"));
    }

    private LoopState state(String request, List<LlmClient.StreamResult> responses) {
        return state(request, ScriptedNativeLlmClient.recordingWithContextWindow(responses, 16_384).client());
    }

    private LoopState state(String request, LlmClient llm) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        var ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
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

    private static ToolCallLoop.ToolOutcome appendLineFailure(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.write_file",
                path,
                false,
                true,
                false,
                "",
                "append-line write_file did not preserve same-turn readback",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(ToolFailureReason.WRITE_APPEND_LINE_PRESERVATION);
    }

    private static ToolCallLoop.ToolOutcome oldStringMissFailure(String path) {
        return new ToolCallLoop.ToolOutcome(
                "talos.edit_file",
                path,
                false,
                true,
                false,
                "",
                "old_string not found",
                null,
                ToolError.INVALID_PARAMS)
                .withFailureReason(ToolFailureReason.EDIT_OLD_STRING_NOT_FOUND);
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"));
    }
}
