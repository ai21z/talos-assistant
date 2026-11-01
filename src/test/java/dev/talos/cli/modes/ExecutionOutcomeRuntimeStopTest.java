package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.TaskCompletionStatus;
import dev.talos.runtime.toolcall.TerminalReadOnlyStopAnswer;
import dev.talos.spi.types.ChatMessage;
import dev.talos.tools.ToolError;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * A runtime stop is a status fact. A turn whose tool loop the runtime stopped is never reported as a
 * completed answer (live session 2026-10-27, turn 4: an executable authority rejection was shown
 * as COMPLETE / READ_ONLY_ANSWERED).
 */
class ExecutionOutcomeRuntimeStopTest {

    private static final String WHERE = "print the working directory and tell me where we are please";

    private static List<ChatMessage> turn(String request) {
        var messages = new ArrayList<ChatMessage>();
        messages.add(ChatMessage.system("sys"));
        messages.add(ChatMessage.user(request));
        return messages;
    }

    private static ToolCallLoop.LoopResult stopped(String answer, FailureDecision decision, List<ToolCallLoop.ToolOutcome> outcomes) {
        int failed = (int) outcomes.stream().filter(o -> !o.success()).count();
        int mutations = (int) outcomes.stream().filter(o -> o.success() && o.mutating()).count();
        return new ToolCallLoop.LoopResult(
                answer, 1, outcomes.size(), outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(), failed, 0, false, mutations, List.of(), 0, 0, 0, 0,
                decision, outcomes);
    }

    private static ToolCallLoop.ToolOutcome rejectedListing() {
        return new ToolCallLoop.ToolOutcome("talos.list_dir", ".", false, false, false, "",
                "Tool call blocked because executable authority is invalid (PROVIDER_TOOL_MODE_MISMATCH).",
                null, ToolError.INVALID_AUTHORITY);
    }

    @Test
    void anAuthorityRejectionStopIsBlockedByPolicyNotAnAnsweredQuestion() {
        String notice = "[Tool execution was blocked because executable tool authority was invalid "
                + "(PROVIDER_TOOL_MODE_MISMATCH). No follow-up provider request was sent.]";
        var loop = stopped(notice,
                FailureDecision.stop(FailureAction.ASK_USER, "tool-call execution authority was rejected"),
                List.of(rejectedListing()));

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(notice, turn(WHERE), loop, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.BLOCKED, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.BLOCKED_BY_POLICY, outcome.taskOutcome().completionStatus());
    }

    @Test
    void anotherUnlistedRuntimeStopOnAQuestionIsNotComplete() {
        String notice = "[Context budget prevented the follow-up request. No further tools were executed.]";
        var loop = stopped(notice,
                FailureDecision.stop(FailureAction.ASK_USER, "Context budget prevented the follow-up request. detail"),
                List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(notice, turn(WHERE), loop, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
    }

    @Test
    void aListedReadOnlyLimitationKeepsItsOwnClassification() {
        String notice = TerminalReadOnlyStopAnswer.synthesisUnavailableAnswer();
        var loop = stopped(notice,
                FailureDecision.stop(FailureAction.ASK_USER, TerminalReadOnlyStopAnswer.SYNTHESIS_UNAVAILABLE_REASON),
                List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(notice, turn("what does README.md say?"), loop, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.ADVISORY_ONLY, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.ADVISORY_ONLY, outcome.taskOutcome().completionStatus());
    }

    @Test
    void aStoppedTurnThatChangedAFileIsClassifiedByItsChange() {
        String notice = "[Context budget prevented the follow-up request. No further tools were executed.]";
        var write = new ToolCallLoop.ToolOutcome("talos.write_file", "notes.txt", true, true, false,
                "Updated notes.txt", "", null, "");
        var withoutStop = ExecutionOutcome.fromToolLoop(notice, turn("update notes.txt to say hi"),
                stopped(notice, FailureDecision.continueLoop(), List.of(write)), null, 0);
        var withStop = ExecutionOutcome.fromToolLoop(notice, turn("update notes.txt to say hi"),
                stopped(notice, FailureDecision.stop(FailureAction.ASK_USER, "Context budget prevented the follow-up request."),
                        List.of(write)), null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, withoutStop.completionStatus(),
                "precondition: the change alone completes, so the stop is what is under test");
        assertEquals(withoutStop.completionStatus(), withStop.completionStatus());
        assertEquals(withoutStop.taskOutcome().completionStatus(), withStop.taskOutcome().completionStatus());
    }

    @Test
    void aQuestionAnsweredWithoutAStopStaysAnswered() {
        String answer = "The workspace is test001.";
        var loop = stopped(answer, FailureDecision.continueLoop(), List.of());

        ExecutionOutcome outcome = ExecutionOutcome.fromToolLoop(answer, turn("hey, how are you?"), loop, null, 0);

        assertEquals(ExecutionOutcome.CompletionStatus.COMPLETE, outcome.completionStatus());
        assertEquals(TaskCompletionStatus.READ_ONLY_ANSWERED, outcome.taskOutcome().completionStatus());
    }
}
