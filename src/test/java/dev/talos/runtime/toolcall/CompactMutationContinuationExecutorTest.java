package dev.talos.runtime.toolcall;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompactMutationContinuationExecutorTest {
    @TempDir
    Path workspace;

    @Test
    void toolCallResultAppliesCompactContinuationAndContinuesLoop() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old\n");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("", List.of(
                        new ChatMessage.NativeToolCall(
                                "compact_write",
                                "talos.write_file",
                                Map.of("path", "README.md", "content", "# New\n"))))),
                16_384);
        LoopState state = mutationState("Rewrite README.md with a short project note.", recorded.client());

        CompactMutationContinuationExecutor.Outcome outcome =
                CompactMutationContinuationExecutor.tryExecute(
                        state,
                        baseTools(),
                        "tool-call loop continuation",
                        "exceeded context budget");

        assertEquals(CompactMutationContinuationExecutor.Outcome.CONTINUE_LOOP, outcome);
        assertFalse(state.failureDecision.shouldStop());
        assertEquals(1, state.currentNativeCalls.size());
        assertEquals("talos.write_file", state.currentNativeCalls.getFirst().name());
        assertFalse(recorded.requests().isEmpty());
    }

    @Test
    void noToolResultStopsWithExistingNoActionFailure() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old\n");
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("I will update it now.", List.of())),
                16_384);
        LoopState state = mutationState("Rewrite README.md with a short project note.", recorded.client());

        CompactMutationContinuationExecutor.Outcome outcome =
                CompactMutationContinuationExecutor.tryExecute(
                        state,
                        baseTools(),
                        "tool-call loop continuation",
                        "exceeded context budget");

        assertEquals(CompactMutationContinuationExecutor.Outcome.STOP_TURN, outcome);
        assertTrue(state.failureDecision.shouldStop());
        assertEquals(FailureAction.ASK_USER, state.failureDecision.action());
        assertTrue(state.failureDecision.reason().contains("COMPACT_MUTATION_CONTINUATION_NO_TOOL"),
                state.failureDecision.reason());
        assertTrue(state.currentText.contains("no file was changed"), state.currentText);
        assertTrue(state.currentNativeCalls.isEmpty());
    }

    @Test
    void wrappedCancellationEscapesCompactMutationContinuation() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Old\n");
        RuntimeException wrapped = new RuntimeException(
                new CancellationException("cancel compact mutation continuation"));
        LoopState state = mutationState(
                "Rewrite README.md with a short project note.",
                LlmClient.scriptedFailure(wrapped));

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                CompactMutationContinuationExecutor.tryExecute(
                        state,
                        baseTools(),
                        "tool-call loop continuation",
                        "exceeded context budget"));

        assertSame(wrapped, thrown,
                "continuation cancellation must preserve the caller-owned runtime wrapper");
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
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", Map.of("path", "README.md")),
                "README.md",
                ToolResult.ok("1 | # Old\n"));
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

    private static List<ToolSpec> baseTools() {
        return List.of(
                new ToolSpec("talos.read_file", "Read", "{}"),
                new ToolSpec("talos.write_file", "Write", "{}"),
                new ToolSpec("talos.edit_file", "Edit", "{}"));
    }
}
