package dev.talos.runtime.outcome;

import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NoToolAnswerTruthfulnessGuardTest {

    @Test
    void workspaceLocalAccessDenialGetsCapabilityCorrection() {
        CurrentTurnPlan plan = plan(
                TaskType.WORKSPACE_EXPLAIN,
                false,
                "Explain this workspace.");
        List<ChatMessage> messages = List.of(ChatMessage.user("Explain this workspace."));

        String answer = NoToolAnswerTruthfulnessGuard.correctNegativeLocalAccessClaimIfNeeded(
                "I cannot inspect your local files unless you paste them here.",
                plan,
                messages);

        assertEquals(NoToolAnswerTruthfulnessGuard.LOCAL_ACCESS_CAPABILITY_CORRECTION, answer);
    }

    @Test
    void streamingNoToolMutationNarrativeIsReplaced() {
        CurrentTurnPlan plan = plan(
                TaskType.FILE_EDIT,
                true,
                "Update script.js.");
        List<ChatMessage> messages = List.of(ChatMessage.user("Update script.js."));

        String answer = NoToolAnswerTruthfulnessGuard.enforceStreamingNoToolTruthfulness(
                "Updated `script.js` and verified the changes.",
                plan,
                messages);

        assertEquals(NoToolAnswerTruthfulnessGuard.STREAMING_NO_TOOL_MUTATION_REPLACEMENT, answer);
    }

    @Test
    void streamingEvidenceClaimGetsUngroundedAnnotation() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Inspect the files and explain the architecture.");
        List<ChatMessage> messages = List.of(ChatMessage.user("Inspect the files and explain the architecture."));
        String answer = "I inspected the repository and found a layered Java CLI architecture. "
                + "The runtime owns task execution, the CLI owns presentation, and the tools package owns "
                + "filesystem actions. ".repeat(40);

        String guarded = NoToolAnswerTruthfulnessGuard.enforceStreamingNoToolTruthfulness(
                answer,
                plan,
                messages);

        assertTrue(guarded.startsWith(NoToolAnswerTruthfulnessGuard.UNGROUNDED_ANNOTATION), guarded);
    }

    private static CurrentTurnPlan plan(TaskType type, boolean mutationRequested, String request) {
        return CurrentTurnPlan.compatibility(
                new TaskContract(type, mutationRequested, mutationRequested, false, Set.of(), Set.of(), request),
                ExecutionPhase.INSPECT,
                List.of(),
                List.of(),
                List.of());
    }
}
