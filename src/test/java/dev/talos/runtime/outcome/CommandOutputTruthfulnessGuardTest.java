package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOutputTruthfulnessGuardTest {

    @Test
    void gitStatusOutputWithoutSuccessfulRunCommandIsWithheld() {
        String answer = """
                On branch main
                Your branch is up to date with 'origin/main'.

                Changes not staged for commit:
                  modified: README.md
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("what is the git status of this workspace?"),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertEquals(CommandOutputTruthfulnessGuard.UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT, result.answer());
    }

    @Test
    void successfulRunCommandGroundsGitStatusOutput() {
        String answer = """
                On branch main
                nothing to commit, working tree clean
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("what is the git status of this workspace?"),
                        loopResult(succeededRunCommand("git status completed")));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void honestUnavailableCommandAnswerPassesUnchanged() {
        String answer = "I did not run git status because talos.run_command was not available this turn.";

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("what is the git status of this workspace?"),
                        loopResult(succeededListDir()));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void labeledDirectoryListingPassesUnchanged() {
        String answer = """
                I could not run git status, but talos.list_dir returned this directory listing:
                README.md
                src
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("what is the git status of this workspace?"),
                        loopResult(succeededListDir()));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void gitStatusLookingTextOutsideGitStatusRequestPassesUnchanged() {
        String answer = """
                The inspected fixture text says:
                On branch main
                Changes not staged for commit:
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Read status-fixture.txt and summarize it."),
                        loopResult(succeededListDir()));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    private static ToolCallLoop.ToolOutcome succeededListDir() {
        return new ToolCallLoop.ToolOutcome(
                "talos.list_dir", ".", true, false, false, "README.md\nsrc", "");
    }

    private static CurrentTurnPlan plan(String request) {
        return CurrentTurnPlan.compatibility(
                new TaskContract(
                        TaskType.READ_ONLY_QA,
                        false,
                        false,
                        false,
                        Set.of(),
                        Set.of(),
                        request),
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file", "talos.grep"),
                List.of("talos.list_dir", "talos.read_file", "talos.grep"),
                List.of());
    }

    private static ToolCallLoop.ToolOutcome succeededRunCommand(String summary) {
        return new ToolCallLoop.ToolOutcome(
                "talos.run_command", "", true, false, false, summary, "");
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome... outcomes) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                outcomes.length,
                List.of(),
                List.of(),
                0,
                0,
                false,
                0,
                List.of(),
                0,
                0,
                0,
                0,
                List.of(outcomes));
    }
}
