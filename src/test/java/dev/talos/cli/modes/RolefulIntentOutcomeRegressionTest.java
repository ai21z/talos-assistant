package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RolefulIntentOutcomeRegressionTest {

    @Test
    void blockedAfterSuccessfulMutationReportsChangedTargetAndStaysBlocked() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(
                "Rewrite styles.css so index.html still works. Do not edit scripts.js."));

        String staleBlockedAnswer = """
                [Action obligation failed: expected-target progress was not satisfied.]

                Remaining target(s): scripts.js.
                The model attempted talos.write_file(styles.css) instead.
                No approval was requested and no additional file was changed.
                """;
        var loopResult = new ToolCallLoop.LoopResult(
                staleBlockedAnswer,
                2,
                1,
                List.of("talos.write_file"),
                List.of(),
                0,
                0,
                false,
                1,
                List.of(),
                0,
                0,
                0,
                0,
                FailureDecision.stop(
                        FailureAction.ASK_USER,
                        "Pending action obligation EXPECTED_TARGETS_REMAINING was ignored after a progress reprompt."),
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.write_file",
                        "styles.css",
                        true,
                        true,
                        false,
                        "wrote styles.css",
                        "",
                        dev.talos.tools.VerificationStatus.PASS)));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(
                loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FAILED_ACTION_OBLIGATION));
        assertTrue(outcome.finalAnswer().contains("Changed target(s) before the block: styles.css."),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("No approval was requested"),
                outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains("no additional file was changed"),
                outcome.finalAnswer());
    }
}
