package dev.talos.cli.modes;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.llm.ScriptedNativeLlmClient;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ToolSpec;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RetryCompletionPresentationTest {
    @Test
    void speculativeAnswerRetriesRejectUncommittedTextBeforeSelection() {
        for (String reason : List.of("length", "content_filter")) {
            var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                    List.of(new LlmClient.StreamResult("SYNTHETIC_PRIVATE_PARTIAL", List.of(), reason)), 4096);
            var context = Context.builder(new Config()).llm(recorded.client()).build();
            String request = "Explain recursion.";
            var plan = CurrentTurnPlan.create(TaskContractResolver.resolveFromUserRequest(request).contract(),
                    ExecutionPhase.INSPECT, List.of(), List.of(), List.of());
            var failure = assertThrows(IllegalStateException.class, () -> TurnModelDispatcher.dispatchBufferedRetry(
                    context, List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request)),
                    plan, RetryDispatchPurpose.POST_TOOL_SYNTHESIS));
            assertEquals("An uncommitted retry answer was withheld.", failure.getMessage());
            assertFalse(failure.getMessage().contains("SYNTHETIC_PRIVATE"));
            assertEquals(1, recorded.requests().size());
        }
    }

    @Test
    void mutationRetryRejectsIncompleteCallsWithoutExpandingItsAuthority() {
        var call = new ChatMessage.NativeToolCall("candidate", "talos.write_file",
                java.util.Map.of("path", "notes.txt", "content", "ready"));
        var recorded = ScriptedNativeLlmClient.recordingWithContextWindow(
                List.of(new LlmClient.StreamResult("Partial proposal", List.of(call), "length")), 4096);
        var specs = List.of(new ToolSpec("talos.write_file", "Write", "{}"));
        var context = Context.builder(new Config()).llm(recorded.client()).nativeToolSpecs(specs).build();
        String request = "Create notes.txt containing ready.";
        var plan = CurrentTurnPlan.create(TaskContractResolver.resolveFromUserRequest(request).contract(),
                ExecutionPhase.APPLY, List.of("talos.write_file"), List.of(), List.of());
        assertThrows(IllegalStateException.class, () -> TurnModelDispatcher.dispatchEscalatedRetry(
                context, List.of(ChatMessage.system("You are Talos."), ChatMessage.user(request)), plan,
                specs, RetryDispatchPurpose.MISSING_MUTATION));
        assertEquals(1, recorded.requests().size());
        assertEquals(specs, recorded.requests().getFirst().tools);
    }
}
