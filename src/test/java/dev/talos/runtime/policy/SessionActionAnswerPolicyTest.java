package dev.talos.runtime.policy;

import dev.talos.runtime.SessionMemory;
import dev.talos.runtime.TurnRecord;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;

import java.util.List;

import static dev.talos.runtime.TurnRecord.ToolCallDisposition.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionActionAnswerPolicyTest {
    private static final String REQUEST = "Have you run tests or changed files during this conversation?";

    @Test
    void projectionPreservesDispositionInsteadOfInferringItFromSuccess() {
        var memory = new SessionMemory();
        memory.recordToolEvidence(1, List.of(
                call("talos.read_file", true, EXECUTED),
                call("talos.read_file", false, EXECUTED),
                call("talos.run_command", false, BLOCKED),
                call("talos.edit_file", false, EXECUTED),
                call("talos.run_command", true, UNKNOWN),
                call("unregistered", true, EXECUTED)));
        assertEquals(BLOCKED, memory.toolEvidence().get(2).executionDisposition());
        String answer = SessionActionAnswerPolicy.answer(REQUEST, memory).orElseThrow();
        assertTrue(answer.contains("Inspection calls: 2 reached execution, 1 reported success, 0 blocked, 0 execution unknown."), answer);
        assertTrue(answer.contains("Mutation calls: 1 reached execution, 0 reported success"), answer);
        assertTrue(answer.contains("Command calls: 0 reached execution, 0 reported success, 1 blocked, 1 execution unknown."), answer);
        assertTrue(answer.contains("Other calls: 1 reached execution, 1 reported success"), answer);
        assertTrue(answer.contains("not that a command launched or files changed"), answer);
        assertTrue(answer.contains("Test results and verification are not established"), answer);
        assertFalse(answer.contains("private.env"), answer);
    }

    @Test
    void legacySuccessCannotBecomeKnownExecution() {
        var memory = new SessionMemory();
        memory.recordToolEvidence(1, List.of(new TurnRecord.ToolCallSummary("talos.write_file", "private.env", true)));
        assertEquals(UNKNOWN, memory.toolEvidence().getFirst().executionDisposition());
        assertEquals(UNKNOWN, new SessionMemory.ToolEvidence(1, null, null, true).executionDisposition());
        assertEquals(UNKNOWN, new SessionMemory.ToolEvidence(1, null, null, true, null).executionDisposition());
        assertThrows(IllegalArgumentException.class, () ->
                new SessionMemory.ToolEvidence(1, "talos.read_file", "x", true, BLOCKED));
        assertTrue(SessionActionAnswerPolicy.answer(REQUEST, memory).orElseThrow()
                .contains("Mutation calls: 0 reached execution, 0 reported success, 0 blocked, 1 execution unknown."));
    }

    @Test
    void missingRestoredAndEvictedRecordsDoNotProveNoEarlierActions() {
        assertTrue(SessionActionAnswerPolicy.answer(REQUEST, null).orElseThrow().contains("unavailable"));
        var memory = new SessionMemory();
        memory.update("Prior task", "The files were changed and tests passed.");
        assertTrue(SessionActionAnswerPolicy.answer(REQUEST, memory).orElseThrow()
                .contains("not proof that no earlier action occurred"));
        for (int i = 0; i < 801; i++) {
            memory.recordToolEvidence(i, List.of(call("talos.read_file", true, EXECUTED)));
        }
        String answer = SessionActionAnswerPolicy.answer(REQUEST, memory).orElseThrow();
        assertTrue(answer.contains("Earlier records evicted: 1."), answer);
        assertTrue(answer.contains("Inspection calls: 800 reached execution"), answer);
        memory.clear();
        assertTrue(SessionActionAnswerPolicy.answer(REQUEST, memory).orElseThrow()
                .contains("Inspection calls: 0 reached execution"));
    }

    @Test
    void requestedResultsBeyondTheActionLedgerAreNotSwallowed() {
        assertTrue(SessionActionAnswerPolicy.answer(REQUEST + " Read README.md.", new SessionMemory()).isEmpty());
        assertTrue(SessionActionAnswerPolicy.answer("Have you run tests? Describe the failures.", new SessionMemory()).isEmpty());
    }

    private TurnRecord.ToolCallSummary call(String name, boolean success, TurnRecord.ToolCallDisposition disposition) {
        return new TurnRecord.ToolCallSummary(name, "private.env", List.of(), success, "",
                ToolMutationEffects.none(), disposition);
    }
}
