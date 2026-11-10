package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallDispatch;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.MutationFailureAnswerRenderer;
import dev.talos.runtime.outcome.TruthWarningType;
import dev.talos.runtime.toolcall.ToolMutationEvidence;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import dev.talos.tools.ToolFailureReason;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LA-F7: the claims of model prose stop deciding the outcome only when the recorded outcome replaces that prose. The
 * loop outcomes are synthetic and carry the producers' typed dispatch facts.
 */
class ExecutionOutcomeNotRunProposalTest {
    private static final String CLAIM = "I updated b.txt.";
    private static final String NOT_RUN_ROW =
            "- a.txt: talos.edit_file not run, refused before approval (old_string and new_string were identical).";

    @Test
    void replacedProseNoLongerDecidesTheOutcome() {
        ExecutionOutcome outcome = outcome(CLAIM);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().startsWith(MutationFailureAnswerRenderer.CHANGES_WITH_NOT_RUN_ANNOTATION),
                outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains(NOT_RUN_ROW), outcome.finalAnswer());
        assertFalse(outcome.finalAnswer().contains(CLAIM), outcome.finalAnswer());
        // The warning keeps the raw claim fact.
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FALSE_MUTATION_CLAIM), outcome.finalAnswer());
    }

    @Test
    void proseThatCopiesTheActionObligationMarkerIsKeptAndStillDecidesTheOutcome() {
        ExecutionOutcome outcome = outcome(CLAIM + " [Action obligation failed: none]");

        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains(CLAIM), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains(NOT_RUN_ROW), outcome.finalAnswer());
        assertTrue(outcome.taskOutcome().hasWarning(TruthWarningType.FALSE_MUTATION_CLAIM), outcome.finalAnswer());
    }

    /**
     * Second review, defect F: when a failure with file effects decides the answer, the delivered answer still names
     * the requested file whose later change did not run.
     */
    @Test
    void theDeliveredEffectsAnswerNamesTheRequestedFileWhoseLaterChangeDidNotRun() {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("Change the function name get_qty to quantity_on_hand in inventory.py, orders.py "
                + "and report.py, including its definition, imports and calls, so that the code works as before."));
        List<ToolCallLoop.ToolOutcome> outcomes = List.of(
                edited("inventory.py", "call-1-1"),
                edited("orders.py", "call-1-2"),
                new ToolCallLoop.ToolOutcome("talos.edit_file", "report.py", false, true, false, "", "PRODUCER TEXT",
                        null, ToolError.TOOL_ERROR, null, ToolMutationEvidence.none(), ToolFailureReason.NONE,
                        List.of(), "call-1-3")
                        .withMutationEffects(new dev.talos.tools.ToolMutationEffects(List.of("report.py"), List.of()))
                        .withDispatch(ToolCallDispatch.executed()),
                new ToolCallLoop.ToolOutcome("talos.edit_file", "orders.py", false, true, true, "", "PRODUCER TEXT",
                        null, ToolError.DENIED, null, ToolMutationEvidence.none(),
                        ToolFailureReason.USER_APPROVAL_DENIED, List.of(), "call-1-4")
                        .withDispatch(ToolCallDispatch.blocked("orders.py")));
        var loopResult = new ToolCallLoop.LoopResult("Renamed it everywhere.", 2, 4,
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(), List.of(), 2, 0, false, 2,
                List.of(), 0, 0, 0, 0, FailureDecision.continueLoop(), outcomes);

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(loopResult.finalAnswer(), messages, loopResult, null, 0);

        assertTrue(outcome.finalAnswer().contains("The operation did not complete successfully."), outcome.finalAnswer());
        assertTrue(outcome.finalAnswer().contains("Requested files with a later change that did not run:\n"
                + "- orders.py: changed earlier in this turn, but a later proposed change to it did not run."),
                outcome.finalAnswer());
        assertNotEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus(), outcome.finalAnswer());
    }

    private static ToolCallLoop.ToolOutcome edited(String path, String toolCallId) {
        return new ToolCallLoop.ToolOutcome("talos.edit_file", path, true, true, false, "Edited " + path, "", null, "",
                null, ToolMutationEvidence.exactEdit("get_qty", "quantity_on_hand"), ToolFailureReason.NONE,
                List.of(), toolCallId).withDispatch(ToolCallDispatch.executed());
    }

    private static ExecutionOutcome outcome(String answer) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user("In a.txt, replace foo with bar."));
        List<ToolCallLoop.ToolOutcome> outcomes = List.of(
                new ToolCallLoop.ToolOutcome("talos.edit_file", "a.txt", true, true, false, "Edited a.txt", "", null,
                        "", null, ToolMutationEvidence.exactEdit("foo", "bar"), ToolFailureReason.NONE, List.of(),
                        "call-1-1").withDispatch(ToolCallDispatch.executed()),
                new ToolCallLoop.ToolOutcome("talos.edit_file", "a.txt", false, true, false, "",
                        "Invalid talos.edit_file call. No approval was requested and no file was changed.", null,
                        ToolError.INVALID_PARAMS, null, ToolMutationEvidence.none(), ToolFailureReason.EDIT_NO_CHANGE,
                        List.of(), "call-1-2").withDispatch(ToolCallDispatch.blocked("a.txt")));
        var loopResult = new ToolCallLoop.LoopResult(answer, 2, 2,
                List.of("talos.edit_file", "talos.edit_file"), List.of(), 1, 0, false, 1, List.of(), 0, 0, 0, 0,
                FailureDecision.continueLoop(), outcomes);
        return ExecutionOutcome.fromToolLoop(loopResult.finalAnswer(), messages, loopResult, null, 0);
    }
}
