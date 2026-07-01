package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolMutationEvidenceBudgetGateTest {
    @TempDir
    Path workspace;

    @Test
    void nonMutationReadOnlyTurnDoesNotApply() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = compactContinuationReturningTool();
        LoopState state = readOnlyEvidenceState(
                "Read script.js and explain the selector.",
                6,
                recorded.client());

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertTrue(result.isEmpty());
        assertTrue(recorded.requests().isEmpty());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void mutationTurnBelowBudgetDoesNotApply() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = compactContinuationReturningTool();
        LoopState state = readOnlyEvidenceState(mutationRequest(), 5, recorded.client());

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertTrue(result.isEmpty());
        assertTrue(recorded.requests().isEmpty());
        assertFalse(state.failureDecision.shouldStop());
    }

    @Test
    void mutationTurnWithPriorMutationProgressDoesNotApply() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = compactContinuationReturningTool();
        LoopState state = readOnlyEvidenceState(mutationRequest(), 6, recorded.client());
        state.mutationSinceStart = true;

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertTrue(result.isEmpty());
        assertTrue(recorded.requests().isEmpty());
    }

    @Test
    void mutationTurnWithFailedCallDoesNotApply() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = compactContinuationReturningTool();
        LoopState state = readOnlyEvidenceState(mutationRequest(), 6, recorded.client());
        state.failedCalls = 1;

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertTrue(result.isEmpty());
        assertTrue(recorded.requests().isEmpty());
    }

    @Test
    void workspaceOperationMutationDoesNotApply() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = compactContinuationReturningTool();
        LoopState state = readOnlyEvidenceState(
                "Move script.js to archive/script.js.",
                6,
                recorded.client());

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertTrue(result.isEmpty());
        assertTrue(recorded.requests().isEmpty());
    }

    @Test
    void overBudgetMutationReadOnlyEvidenceContinuesWithCompactMutationToolCall() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = compactContinuationReturningTool();
        LoopState state = readOnlyEvidenceState(mutationRequest(), 6, recorded.client());

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertEquals(Optional.of(true), result);
        assertFalse(state.failureDecision.shouldStop());
        assertEquals(1, state.currentNativeCalls.size());
        assertEquals("talos.edit_file", state.currentNativeCalls.getFirst().name());
        assertEquals(1, recorded.requests().size());
        String prompt = recorded.requests().getFirst().messages.stream()
                .map(ChatMessage::content)
                .reduce("", (left, right) -> left + "\n" + right);
        assertTrue(prompt.contains("[CompactMutationContinuation]"), prompt);
        assertTrue(prompt.contains("script.js"), prompt);
    }

    @Test
    void overBudgetMutationReadOnlyEvidenceStopsWhenCompactContinuationReturnsNoTool() throws Exception {
        Files.writeString(workspace.resolve("script.js"), "document.querySelector('.missing-button');\n");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I will update it now.", List.of())),
                16_384);
        LoopState state = readOnlyEvidenceState(mutationRequest(), 6, recorded.client());

        Optional<Boolean> result = ToolMutationEvidenceBudgetGate.tryContinueOrStop(state, 6);

        assertEquals(Optional.of(false), result);
        assertTrue(state.failureDecision.shouldStop());
        assertTrue(state.failureDecision.reason().contains("COMPACT_MUTATION_CONTINUATION_NO_TOOL"),
                state.failureDecision.reason());
        assertTrue(state.currentText.contains("no file was changed"), state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
        assertEquals(1, recorded.requests().size());
    }

    @Test
    void repromptStageDelegatesMutationEvidenceBudgetGateToOwner() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/runtime/toolcall/ToolCallRepromptStage.java"));

        assertTrue(source.contains("ToolMutationEvidenceBudgetGate.tryContinueOrStop"), source);
        assertFalse(source.contains("private static boolean mutationReadOnlyBudgetExceeded"), source);
        assertFalse(source.contains("private static int readOnlyInspectionAttemptCount"), source);
        assertFalse(source.contains("private static boolean readOnlyProgressOnly"), source);
    }

    private LoopState readOnlyEvidenceState(String request, int readOnlyAttempts, LlmClient llm) {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request)));
        Context ctx = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(llm)
                .nativeToolSpecs(baseTools())
                .build();
        LoopState state = new LoopState("", List.of(), messages, workspace, ctx, null, 10, 0);
        for (int i = 0; i < readOnlyAttempts; i++) {
            state.toolNames.add("talos.read_file");
            state.pathsReadThisTurn.add("script.js");
            state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                    "talos.read_file",
                    "script.js",
                    true,
                    false,
                    false,
                    "Read script.js",
                    ""));
        }
        state.successfulReadCallBodies.put(
                "talos.read_file:path=script.js;",
                "1 | document.querySelector('.missing-button');\n");
        return state;
    }

    private static ScriptedNativeLlmClient.RecordedClient compactContinuationReturningTool() {
        return ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(new ChatMessage.NativeToolCall(
                        "compact_edit",
                        "talos.edit_file",
                        Map.of(
                                "path", "script.js",
                                "old_string", ".missing-button",
                                "new_string", ".cta-button"))))),
                16_384);
    }

    private static String mutationRequest() {
        return "Read script.js, then fix the selector bug by changing .missing-button to .cta-button.";
    }

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }
}
