package dev.talos.runtime.outcome;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import org.junit.jupiter.api.Test;

import java.util.List;
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
