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
    void testRunOutputWithoutSuccessfulRunCommandIsWithheld() {
        String answer = """
                BUILD SUCCESSFUL in 12s
                5378 tests completed, 0 failed
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Did the project tests pass?"),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertEquals(CommandOutputTruthfulnessGuard.UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT, result.answer());
    }

    @Test
    void successfulRunCommandGroundsTestRunOutput() {
        String answer = """
                BUILD SUCCESSFUL in 12s
                5378 tests completed, 0 failed
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Did the project tests pass?"),
                        loopResult(succeededRunCommand("Command succeeded: gradle_test exited with code 0")));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void processListOutputWithoutSuccessfulRunCommandIsWithheld() {
        String answer = """
                PID   PPID  COMMAND
                1001  1     java
                1002  1     llama-server
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Show the current process list."),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertEquals(CommandOutputTruthfulnessGuard.UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT, result.answer());
    }

    @Test
    void successfulRunCommandGroundsProcessListOutput() {
        String answer = """
                PID   PPID  COMMAND
                1001  1     java
                1002  1     llama-server
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Show the current process list."),
                        loopResult(succeededRunCommand("Command succeeded: process list captured")));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void shellListingOutputWithoutSuccessfulRunCommandIsWithheld() {
        String answer = """
                $ ls
                README.md
                src
                build.gradle.kts
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Run ls and report the output."),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertEquals(CommandOutputTruthfulnessGuard.UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT, result.answer());
    }

    @Test
    void successfulRunCommandGroundsShellListingOutput() {
        String answer = """
                $ ls
                README.md
                src
                build.gradle.kts
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Run ls and report the output."),
                        loopResult(succeededRunCommand("Command succeeded: ls output captured")));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void shellCatOutputWithoutSuccessfulRunCommandIsWithheld() {
        String answer = """
                $ cat README.md
                # Demo
                Local-first workspace assistant.
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Run cat README.md and report the output."),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertEquals(CommandOutputTruthfulnessGuard.UNSUPPORTED_COMMAND_OUTPUT_REPLACEMENT, result.answer());
    }

    @Test
    void successfulRunCommandGroundsShellCatOutput() {
        String answer = """
                $ cat README.md
                # Demo
                Local-first workspace assistant.
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Run cat README.md and report the output."),
                        loopResult(succeededRunCommand("Command succeeded: cat output captured")));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void fileContentClaimWithoutSuccessfulReadFileIsWithheld() {
        String answer = """
                README.md contains:
                # Demo
                Local-first workspace assistant.
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("What does README.md contain?"),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertTrue(result.answer().startsWith("[Workspace content truth check:"), result.answer());
    }

    @Test
    void honestCommandUnavailableSentenceDoesNotMaskUngroundedFileContentClaim() {
        String answer = """
                I did not run the command.

                README.md contains:
                # Demo
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("What does README.md contain?"),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertTrue(result.answer().startsWith("[Workspace content truth check:"), result.answer());
    }

    @Test
    void successfulReadFileGroundsFileContentClaim() {
        String answer = """
                README.md contains:
                # Demo
                Local-first workspace assistant.
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("What does README.md contain?"),
                        loopResult(succeededReadFile("README.md", "1 | # Demo\n2 | Local-first workspace assistant.")));

        assertFalse(result.unsupportedCommandOutputClaim());
        assertEquals(answer, result.answer());
    }

    @Test
    void readOfOneFileDoesNotGroundSeparateFileContentClaim() {
        String answer = """
                README.md contains:
                # Demo

                notes.md contains:
                PROJECT_CODENAME = Aster-7
                """;

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("What do README.md and notes.md contain?"),
                        loopResult(succeededReadFile("README.md", "1 | # Demo")));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertTrue(result.answer().startsWith("[Workspace content truth check:"), result.answer());
    }

    @Test
    void commandApprovalDenialClaimWithoutDeniedRunCommandIsWithheld() {
        String answer = "The command was not executed because approval was denied.";

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Run the command Get-ChildItem -Name to list workspace file names. "
                                + "I will deny command approval."),
                        loopResult(succeededListDir()));

        assertTrue(result.unsupportedCommandOutputClaim());
        assertTrue(result.answer().startsWith(
                "[Command approval truth check: no talos.run_command approval denial was recorded.]"),
                result.answer());
        String lower = result.answer().toLowerCase(java.util.Locale.ROOT);
        assertFalse(lower.contains("approval was denied"), result.answer());
        assertFalse(lower.contains("approval denied"), result.answer());
    }

    @Test
    void commandApprovalDenialClaimWithDeniedRunCommandPassesUnchanged() {
        String answer = "The command was not executed because approval was denied.";

        CommandOutputTruthfulnessGuard.Result result =
                CommandOutputTruthfulnessGuard.withholdUnsupportedCommandOutputIfNeeded(
                        answer,
                        plan("Run the approved Gradle command profile."),
                        loopResult(deniedRunCommand()));

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

    private static ToolCallLoop.ToolOutcome succeededReadFile(String path, String summary) {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file", path, true, false, false, summary, "");
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

    private static ToolCallLoop.ToolOutcome deniedRunCommand() {
        return new ToolCallLoop.ToolOutcome(
                "talos.run_command", "", false, false, true, "", "User did not approve the command.");
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
