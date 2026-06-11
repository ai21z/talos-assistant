package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandOutcomeRendererTest {
    @Test
    void failureReplacementPreservesExistingCommandFailureWording() {
        CommandOutcomeRenderer.Conclusion conclusion = CommandOutcomeRenderer.conclusion(loopResult(
                failedRunCommand("Command failed: gradle_test exited with code 1 after 25ms.\n"
                        + "profile: gradle_test\n"
                        + "stdout:\n"
                        + "FAILED")));

        assertTrue(conclusion.failed());
        assertEquals("""
                [Command failed: talos.run_command did not finish successfully.]

                Command failed: gradle_test exited with code 1 after 25ms. profile: gradle_test stdout: FAILED""",
                CommandOutcomeRenderer.failureReplacement(conclusion));
    }

    @Test
    void timedOutCommandFailureUsesExistingTimeoutPrefix() {
        CommandOutcomeRenderer.Conclusion conclusion = CommandOutcomeRenderer.conclusion(loopResult(
                failedRunCommand("Command timed out: gradle_test exceeded 1000ms.")
                        .withFailureReason(ToolFailureReason.COMMAND_TIMEOUT)));

        assertEquals("""
                [Command timed out: talos.run_command did not finish successfully.]

                Command timed out: gradle_test exceeded 1000ms.""",
                CommandOutcomeRenderer.failureReplacement(conclusion));
    }

    @Test
    void deniedCommandFailurePreservesExistingBlockedWording() {
        CommandOutcomeRenderer.Conclusion conclusion = CommandOutcomeRenderer.conclusion(loopResult(
                new ToolCallLoop.ToolOutcome(
                        "talos.run_command", "", false, false, true,
                        "", "User did not approve the talos.run_command call.")));

        assertTrue(conclusion.denied());
        assertEquals("""
                [Command not run: talos.run_command was blocked before execution.]

                User did not approve the talos.run_command call.""",
                CommandOutcomeRenderer.failureReplacement(conclusion));
    }

    @Test
    void successReplacementPreservesSummaryPunctuationRules() {
        CommandOutcomeRenderer.Conclusion missingPunctuation = CommandOutcomeRenderer.conclusion(loopResult(
                succeededRunCommand("Command succeeded: gradle_test exited with code 0 after 31ms")));
        CommandOutcomeRenderer.Conclusion existingPunctuation = CommandOutcomeRenderer.conclusion(loopResult(
                succeededRunCommand("Command succeeded?")));
        CommandOutcomeRenderer.Conclusion blankSummary = CommandOutcomeRenderer.conclusion(loopResult(
                succeededRunCommand("")));

        assertEquals(
                "Command succeeded: gradle_test exited with code 0 after 31ms.",
                CommandOutcomeRenderer.successReplacement(missingPunctuation));
        assertEquals("Command succeeded?", CommandOutcomeRenderer.successReplacement(existingPunctuation));
        assertEquals(
                "Command succeeded: talos.run_command completed.",
                CommandOutcomeRenderer.successReplacement(blankSummary));
    }

    @Test
    void conclusionUsesFirstCommandFailureBeforeLaterSuccess() {
        CommandOutcomeRenderer.Conclusion conclusion = CommandOutcomeRenderer.conclusion(loopResult(
                succeededReadFile(),
                failedRunCommand("Command failed: gradle_test exited with code 1."),
                succeededRunCommand("Command succeeded: gradle_test exited with code 0")));

        assertTrue(conclusion.failed());
        assertFalse(conclusion.succeeded());
        assertEquals("Command failed: gradle_test exited with code 1.", conclusion.outcome().errorMessage());
    }

    @Test
    void conclusionUsesFirstCommandSuccessWhenNoCommandFailureExists() {
        CommandOutcomeRenderer.Conclusion conclusion = CommandOutcomeRenderer.conclusion(loopResult(
                succeededReadFile(),
                succeededRunCommand("first success"),
                succeededRunCommand("second success")));

        assertTrue(conclusion.succeeded());
        assertFalse(conclusion.failed());
        assertEquals("first success", conclusion.outcome().summary());
    }

    @Test
    void conclusionAcceptsBackendRunCommandAlias() {
        CommandOutcomeRenderer.Conclusion conclusion = CommandOutcomeRenderer.conclusion(loopResult(
                new ToolCallLoop.ToolOutcome(
                        "tool_use:run_command", "", true, false, false,
                        "Command succeeded through alias", "")));

        assertTrue(conclusion.succeeded());
        assertEquals("Command succeeded through alias", conclusion.outcome().summary());
    }

    @Test
    void missingCommandReplacementWordingStaysRuntimeOwned() {
        assertEquals("""
                [Command not run: talos.run_command was required for this explicit command request.]

                No command result is available because the model did not call talos.run_command.""",
                CommandOutcomeRenderer.requiredButNotRunReplacement());
        assertEquals("""
                [Command not run: Python execution is outside the current bounded command profile.]

                No Python, pytest, or .py command result is available in this beta turn.""",
                CommandOutcomeRenderer.unsupportedCommandNotAvailableReplacement());
    }

    @Test
    void contractPredicatesPreserveCommandVerificationClassification() {
        TaskContract verifyOnlyCommand = new TaskContract(
                TaskType.VERIFY_ONLY,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                "Probe timeout behavior.",
                "explicit-command-verification-request");
        TaskContract unsupportedNaturalCommand = new TaskContract(
                TaskType.VERIFY_ONLY,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                "Run npm audit.",
                "unsupported-command-verification-request");
        TaskContract unsupportedPythonCommand = new TaskContract(
                TaskType.VERIFY_ONLY,
                false,
                false,
                true,
                Set.of(),
                Set.of(),
                "Run python -m pytest.",
                "unsupported-command-verification-request");

        assertTrue(CommandOutcomeRenderer.satisfiesVerifyOnlyRequest(verifyOnlyCommand));
        assertTrue(CommandOutcomeRenderer.explicitCommandVerificationRequired(verifyOnlyCommand));
        assertFalse(CommandOutcomeRenderer.unsupportedCommandVerificationRequest(verifyOnlyCommand));
        assertTrue(CommandOutcomeRenderer.unsupportedCommandVerificationRequest(unsupportedNaturalCommand));
        assertTrue(CommandOutcomeRenderer.unsupportedPythonCommandExecutionRequest(unsupportedPythonCommand));
    }

    private static ToolCallLoop.ToolOutcome failedRunCommand(String errorMessage) {
        return new ToolCallLoop.ToolOutcome(
                "talos.run_command", "", false, false, false, "", errorMessage);
    }

    private static ToolCallLoop.ToolOutcome succeededRunCommand(String summary) {
        return new ToolCallLoop.ToolOutcome(
                "talos.run_command", "", true, false, false, summary, "");
    }

    private static ToolCallLoop.ToolOutcome succeededReadFile() {
        return new ToolCallLoop.ToolOutcome(
                "talos.read_file", "README.md", true, false, false, "read README.md", "");
    }

    private static ToolCallLoop.LoopResult loopResult(ToolCallLoop.ToolOutcome... outcomes) {
        return new ToolCallLoop.LoopResult(
                "model answer",
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
