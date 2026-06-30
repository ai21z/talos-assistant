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
    void workspaceMutationCapabilityDenialGetsCapabilityCorrection() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Why can't you make it?");
        List<ChatMessage> messages = List.of(ChatMessage.user("Why can't you make it?"));

        String answer = NoToolAnswerTruthfulnessGuard.correctNegativeMutationCapabilityClaimIfNeeded(
                "I currently don't have the capability to directly create or write files into your workspace.",
                plan,
                messages);

        assertEquals(NoToolAnswerTruthfulnessGuard.MUTATION_CAPABILITY_CORRECTION, answer);
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

    @Test
    void workspaceNoResultsNegativeWithoutToolGroundingIsReplaced() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Are there any secrets or API keys in this workspace?");
        List<ChatMessage> messages = List.of(ChatMessage.user(
                "Are there any secrets or API keys in this workspace?"));

        String guarded = NoToolAnswerTruthfulnessGuard.enforceNoToolTruthfulness(
                "No results found. There are no secrets or API keys in this workspace.",
                plan,
                messages);

        assertEquals(NoToolAnswerTruthfulnessGuard.UNGROUNDED_NEGATIVE_WORKSPACE_RESULT_REPLACEMENT, guarded);
    }

    @Test
    void honestNoSearchDisclosureIsNotReplacedAsNegativeResult() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Are there any secrets or API keys in this workspace?");
        List<ChatMessage> messages = List.of(ChatMessage.user(
                "Are there any secrets or API keys in this workspace?"));
        String answer = "I did not search the workspace in this turn, so I cannot say whether secrets exist.";

        String guarded = NoToolAnswerTruthfulnessGuard.enforceNoToolTruthfulness(
                answer,
                plan,
                messages);

        assertEquals(answer, guarded);
    }

    @Test
    void genericNonWorkspaceFindRequestIsNotReplacedAsNegativeResult() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Find a concise name for the new CLI mode.");
        List<ChatMessage> messages = List.of(ChatMessage.user(
                "Find a concise name for the new CLI mode."));
        String answer = "No results found that fit better than Agent.";

        String guarded = NoToolAnswerTruthfulnessGuard.enforceNoToolTruthfulness(
                answer,
                plan,
                messages);

        assertEquals(answer, guarded);
    }

    @Test
    void genericCredentialWordBrainstormIsNotReplacedAsWorkspaceSearch() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Find a concise name for a password manager feature.");
        List<ChatMessage> messages = List.of(ChatMessage.user(
                "Find a concise name for a password manager feature."));
        String answer = "No results found that fit better than Vault.";

        String guarded = NoToolAnswerTruthfulnessGuard.enforceNoToolTruthfulness(
                answer,
                plan,
                messages);

        assertEquals(answer, guarded);
    }

    @Test
    void profileDoesNotCountAsFileScopeForWorkspaceNegativeGuard() {
        CurrentTurnPlan plan = plan(
                TaskType.READ_ONLY_QA,
                false,
                "Find a concise profile name.");
        List<ChatMessage> messages = List.of(ChatMessage.user(
                "Find a concise profile name."));
        String answer = "No results found that fit better than Local Agent.";

        String guarded = NoToolAnswerTruthfulnessGuard.enforceNoToolTruthfulness(
                answer,
                plan,
                messages);

        assertEquals(answer, guarded);
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
