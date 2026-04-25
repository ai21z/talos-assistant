package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContractResolver;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MutationOutcomeTest {

    @Test
    void noMutationRequestedIsNotRequested() {
        var contract = TaskContractResolver.fromUserRequest("Check the workspace. Do not change anything.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of()), 0);

        assertEquals(MutationOutcomeStatus.NOT_REQUESTED, outcome.status());
        assertEquals(0, outcome.successCount());
        assertEquals(0, outcome.failureCount());
    }

    @Test
    void mutationRequestedButNoMutatingOutcomeIsNotAttempted() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of()), 0);

        assertEquals(MutationOutcomeStatus.NOT_ATTEMPTED, outcome.status());
    }

    @Test
    void deniedOnlyMutationIsDenied() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", false, true, true, "", "approval denied")
        )), 0);

        assertEquals(MutationOutcomeStatus.DENIED, outcome.status());
        assertEquals(1, outcome.denied().size());
    }

    @Test
    void mixedMutationSuccessAndFailureIsPartial() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html and style.css.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", true, true, false, "edited", ""),
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "style.css", false, true, false, "", "old_string not found")
        )), 0);

        assertEquals(MutationOutcomeStatus.PARTIAL, outcome.status());
        assertEquals(1, outcome.successCount());
        assertEquals(1, outcome.failureCount());
    }

    @Test
    void successfulMutationIsSucceeded() {
        var contract = TaskContractResolver.fromUserRequest("Edit index.html.");

        MutationOutcome outcome = MutationOutcome.from(contract, loopResult(List.of(
                new ToolCallLoop.ToolOutcome(
                        "talos.edit_file", "index.html", true, true, false, "edited", "")
        )), 0);

        assertEquals(MutationOutcomeStatus.SUCCEEDED, outcome.status());
        assertEquals(1, outcome.successCount());
    }

    private static ToolCallLoop.LoopResult loopResult(List<ToolCallLoop.ToolOutcome> outcomes) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(),
                0,
                0,
                false,
                (int) outcomes.stream().filter(outcome -> outcome.mutating() && outcome.success()).count(),
                List.of(),
                0,
                0,
                0,
                0,
                outcomes
        );
    }
}
