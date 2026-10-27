package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.failure.FailureAction;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReadOnlyToolLimitOutcomeTest {

    @Test
    void readOnlyIterationLimitWithoutRuntimeGroundingReplacesAnswer() {
        ReadOnlyToolLimitOutcome outcome = ReadOnlyToolLimitOutcome.assess(
                readOnlyContract(),
                loopResult(true),
                false);

        assertTrue(outcome.withoutRuntimeAnswer());
        assertTrue(outcome.shouldReplaceAnswer());
        assertEquals(
                "[Read-only evidence incomplete: the tool-call limit was reached before Talos produced "
                        + "a complete grounded answer. The read-only inspection did not complete.]",
                outcome.replacementAnswer());
    }

    @Test
    void nullContractPreservesLegacyReadOnlyDefault() {
        ReadOnlyToolLimitOutcome outcome = ReadOnlyToolLimitOutcome.assess(
                null,
                loopResult(true),
                false);

        assertTrue(outcome.withoutRuntimeAnswer());
        assertTrue(outcome.shouldReplaceAnswer());
    }

    @Test
    void runtimeGroundedOverrideSuppressesReplacement() {
        ReadOnlyToolLimitOutcome outcome = ReadOnlyToolLimitOutcome.assess(
                readOnlyContract(),
                loopResult(true),
                true);

        assertFalse(outcome.withoutRuntimeAnswer());
        assertFalse(outcome.shouldReplaceAnswer());
    }

    @Test
    void mutationRequestSuppressesReadOnlyReplacement() {
        ReadOnlyToolLimitOutcome outcome = ReadOnlyToolLimitOutcome.assess(
                mutationContract(),
                loopResult(true),
                false);

        assertFalse(outcome.withoutRuntimeAnswer());
        assertFalse(outcome.shouldReplaceAnswer());
    }

    @Test
    void nonLimitLoopDoesNotReplaceAnswer() {
        ReadOnlyToolLimitOutcome outcome = ReadOnlyToolLimitOutcome.assess(
                readOnlyContract(),
                loopResult(false),
                false);

        assertFalse(outcome.withoutRuntimeAnswer());
        assertFalse(outcome.shouldReplaceAnswer());
    }

    @Test
    void acceptedSynthesisExemptsOnlyItsExactAnswerAndKeepsHistoricalLimit() {
        var loop = synthesizedLoop("Grounded answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                false, FailureDecision.continueLoop(), 0);

        assertFalse(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), loop, false,
                "Grounded answer.").shouldReplaceAnswer());
        assertTrue(loop.hitIterLimit(), "accepted synthesis must not erase the prior processing cap");
        assertTrue(loop.summary().contains("reached"), "the historical cap remains visible in telemetry");
        assertTrue(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), loop, false,
                "A different, unbound answer.").shouldReplaceAnswer());
        assertTrue(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), loop, false, null).shouldReplaceAnswer());
    }

    @Test
    void rejectedSynthesisDoesNotExemptTheLimit() {
        var loop = synthesizedLoop("The answer is unavailable.", ToolCallLoop.ReadOnlySynthesisStatus.REJECTED,
                false, FailureDecision.continueLoop(), 0);
        assertTrue(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), loop, false).shouldReplaceAnswer());
    }

    @Test
    void acceptedMarkerDoesNotExemptEmptyLimitedFailedOrMutatingResults() {
        var stopped = FailureDecision.stop(FailureAction.ASK_USER, "READ_ONLY_SYNTHESIS_UNAVAILABLE");
        for (var loop : List.of(
                synthesizedLoop(" ", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                        false, FailureDecision.continueLoop(), 0),
                synthesizedLoop("Answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                        true, FailureDecision.continueLoop(), 0),
                synthesizedLoop("Answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                        false, stopped, 0),
                synthesizedLoop("Answer.", ToolCallLoop.ReadOnlySynthesisStatus.ACCEPTED,
                        false, FailureDecision.continueLoop(), 1))) {
            assertTrue(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), loop, false).shouldReplaceAnswer(),
                    loop.toString());
        }
    }

    @Test
    void legacyAndNullStatusShapesDefaultToNoSynthesisAttempt() {
        var legacy = new ToolCallLoop.LoopResult("answer", 10, 1, List.of("talos.read_file"),
                List.of(), 0, 0, true, 0, List.of("README.md"), 0, 0, 0, 0,
                FailureDecision.continueLoop(), List.of(), Map.of(), false);
        var nullStatus = synthesizedLoop("answer", null, false, FailureDecision.continueLoop(), 0);
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, legacy.readOnlySynthesisStatus());
        assertEquals(ToolCallLoop.ReadOnlySynthesisStatus.NOT_ATTEMPTED, nullStatus.readOnlySynthesisStatus());
        assertTrue(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), legacy, false).shouldReplaceAnswer());
        assertTrue(ReadOnlyToolLimitOutcome.assess(readOnlyContract(), nullStatus, false).shouldReplaceAnswer());
    }

    private static ToolCallLoop.LoopResult synthesizedLoop(String answer,
            ToolCallLoop.ReadOnlySynthesisStatus status, boolean outputLimited,
            FailureDecision failure, int mutationSuccesses) {
        return new ToolCallLoop.LoopResult(answer, 10, 1, List.of("talos.read_file"), List.of(),
                0, 0, true, mutationSuccesses, List.of("README.md"), 0, 0, 0, 0,
                failure, List.of(), Map.of(), outputLimited, status);
    }

    private static ToolCallLoop.LoopResult loopResult(boolean hitIterLimit) {
        return new ToolCallLoop.LoopResult(
                "answer",
                10,
                10,
                List.of("talos.read_file"),
                List.of(),
                0,
                0,
                hitIterLimit,
                0,
                List.of("README.md"),
                0,
                0,
                0,
                0,
                List.of());
    }

    private static TaskContract readOnlyContract() {
        return new TaskContract(
                TaskType.READ_ONLY_QA,
                false,
                false,
                false,
                Set.of("README.md"),
                Set.of(),
                "read README.md");
    }

    private static TaskContract mutationContract() {
        return new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                true,
                Set.of("index.html"),
                Set.of(),
                "edit index.html");
    }
}
