package dev.talos.harness;

import dev.talos.runtime.ApprovalResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ScriptedApprovalGateTest {

    @Test
    void optionalApprovalStepCanBeSkippedWhenNextRequiredStepMatches() {
        ScriptedApprovalGate gate = new ScriptedApprovalGate(List.of(
                ScriptedApprovalGate.Step.optionalApprove("talos.mkdir", "notes"),
                ScriptedApprovalGate.Step.approve("talos.write_file", "notes/generated-summary.md")));

        ApprovalResponse response = gate.approveFull(
                "Permission policy requires approval before running talos.write_file.",
                "target: notes/generated-summary.md");

        assertEquals(ApprovalResponse.APPROVED, response);
        gate.assertExhausted();
        assertEquals(1, gate.events().size());
        assertTrue(gate.events().getFirst().detail().contains("notes/generated-summary.md"));
    }

    @Test
    void optionalApprovalStepIsConsumedWhenItMatches() {
        ScriptedApprovalGate gate = new ScriptedApprovalGate(List.of(
                ScriptedApprovalGate.Step.optionalApprove("talos.mkdir", "notes"),
                ScriptedApprovalGate.Step.approve("talos.write_file", "notes/generated-summary.md")));

        ApprovalResponse mkdirResponse = gate.approveFull(
                "Permission policy requires approval before running talos.mkdir.",
                "target: notes");
        ApprovalResponse writeResponse = gate.approveFull(
                "Permission policy requires approval before running talos.write_file.",
                "target: notes/generated-summary.md");

        assertEquals(ApprovalResponse.APPROVED, mkdirResponse);
        assertEquals(ApprovalResponse.APPROVED, writeResponse);
        gate.assertExhausted();
        assertEquals(2, gate.events().size());
    }
}
