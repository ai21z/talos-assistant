package dev.talos.runtime.failure;

import dev.talos.runtime.toolcall.LoopState;
import dev.talos.runtime.toolcall.ToolCallExecutionStage;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailurePolicyTest {

    @Test
    void repeatedSamePathFailureStopsWithAskUserWhenNoMutationSucceeded() {
        LoopState state = state();
        state.failureCountsByPath.put("missing.txt", 3);

        FailureDecision decision = policy().afterIteration(state, failedIteration());

        assertTrue(decision.shouldStop());
        assertEquals(FailureAction.ASK_USER, decision.action());
        assertTrue(decision.reason().contains("path `missing.txt`"));
    }

    @Test
    void repeatedSameToolFailureStopsWithPartialWhenMutationAlreadySucceeded() {
        LoopState state = state();
        state.mutatingToolSuccesses = 1;
        state.failureCountsByTool.put("talos.edit_file", 3);

        FailureDecision decision = policy().afterIteration(state, failedIteration());

        assertTrue(decision.shouldStop());
        assertEquals(FailureAction.STOP_WITH_PARTIAL, decision.action());
        assertTrue(decision.reason().contains("tool `talos.edit_file`"));
    }

    @Test
    void noProgressIterationsStopAtThreshold() {
        LoopState state = state();
        FailurePolicy policy = policy();

        assertFalse(policy.afterIteration(state, failedIteration()).shouldStop());
        assertFalse(policy.afterIteration(state, failedIteration()).shouldStop());
        FailureDecision decision = policy.afterIteration(state, failedIteration());

        assertTrue(decision.shouldStop());
        assertEquals(FailureAction.ASK_USER, decision.action());
        assertTrue(decision.reason().contains("no-progress"));
    }

    @Test
    void repeatedEmptyEditArgsAfterReadStopBeforeGenericPathThreshold() {
        LoopState state = state();
        state.pathsReadThisTurn.add("index.html");
        state.emptyEditArgumentFailuresByPath.put("index.html", 2);
        state.failureCountsByPath.put("index.html", 2);

        FailureDecision decision = policy().afterIteration(state, failedIteration());

        assertTrue(decision.shouldStop());
        assertEquals(FailureAction.ASK_USER, decision.action());
        assertTrue(decision.reason().contains("empty talos.edit_file argument"));
        assertTrue(decision.reason().contains("No approval was requested"));
    }

    @Test
    void emptyEditArgsDoNotSpecialStopBeforeFileWasRead() {
        LoopState state = state();
        state.emptyEditArgumentFailuresByPath.put("index.html", 2);
        state.failureCountsByPath.put("index.html", 2);

        FailureDecision decision = policy().afterIteration(state, failedIteration());

        assertFalse(decision.shouldStop());
    }

    @Test
    void successfulIterationResetsNoProgressCounter() {
        LoopState state = state();
        FailurePolicy policy = policy();

        policy.afterIteration(state, failedIteration());
        policy.afterIteration(state, successIteration());

        assertEquals(0, state.noProgressIterations);
        assertFalse(policy.afterIteration(state, failedIteration()).shouldStop());
    }

    private static FailurePolicy policy() {
        return new FailurePolicy(10, 3, 3, 3, true, false);
    }

    private static ToolCallExecutionStage.IterationOutcome failedIteration() {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), 1, false, false, 0);
    }

    private static ToolCallExecutionStage.IterationOutcome successIteration() {
        return new ToolCallExecutionStage.IterationOutcome(0, List.of(), 0, false, false, 1);
    }

    private static LoopState state() {
        return new LoopState(
                "",
                List.of(),
                new ArrayList<>(),
                Path.of(".").toAbsolutePath().normalize(),
                null,
                null,
                10,
                0);
    }
}
