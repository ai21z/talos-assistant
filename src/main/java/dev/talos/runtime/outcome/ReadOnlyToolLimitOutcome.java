package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;

/** Truthfulness outcome for read-only turns that exhaust the tool-call loop before producing an answer. */
public record ReadOnlyToolLimitOutcome(
        boolean withoutRuntimeAnswer,
        String replacementAnswer
) {
    public static final String REPLACEMENT_ANSWER =
            "[Read-only evidence incomplete: the tool-call limit was reached before Talos produced "
                    + "a complete grounded answer. The read-only inspection did not complete.]";

    public ReadOnlyToolLimitOutcome {
        replacementAnswer = replacementAnswer == null ? "" : replacementAnswer;
    }

    public static ReadOnlyToolLimitOutcome assess(
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            boolean runtimeGroundedOverride
    ) {
        return assess(contract, loopResult, runtimeGroundedOverride,
                loopResult == null ? null : loopResult.finalAnswer());
    }

    public static ReadOnlyToolLimitOutcome assess(
            TaskContract contract,
            ToolCallLoop.LoopResult loopResult,
            boolean runtimeGroundedOverride,
            String selectedAnswer
    ) {
        // This exempts only the exact accepted candidate from cap containment.
        // It neither clears historical hitIterLimit nor grants semantic correctness
        // to a later replacement answer; the remaining outcome guards still apply.
        boolean selectedBoundedSynthesis = loopResult != null
                && loopResult.readOnlySynthesisStatus() == ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED
                && loopResult.finalAnswer() != null && !loopResult.finalAnswer().isBlank()
                && loopResult.finalAnswer().equals(selectedAnswer)
                && !loopResult.finalAnswerOutputLimitReached()
                && !loopResult.failureDecision().shouldStop()
                && loopResult.mutatingToolSuccesses() == 0;
        boolean withoutRuntimeAnswer = loopResult != null
                && loopResult.hitIterLimit()
                && !runtimeGroundedOverride
                && !selectedBoundedSynthesis
                && (contract == null || !contract.mutationRequested());
        return new ReadOnlyToolLimitOutcome(
                withoutRuntimeAnswer,
                withoutRuntimeAnswer ? REPLACEMENT_ANSWER : "");
    }

    public boolean shouldReplaceAnswer() {
        return withoutRuntimeAnswer;
    }
}
