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

    @Test
    void approveOnceRecordsOneTurnPromptAndCollapsesRememberResponse() {
        ScriptedApprovalGate gate = new ScriptedApprovalGate(List.of(
                ScriptedApprovalGate.Step.remember("private document model handoff", "medical-notes.docx")));

        ApprovalResponse response = gate.approveOnce(
                "private document model handoff: talos.read_file",
                "target: medical-notes.docx");

        assertEquals(ApprovalResponse.APPROVED, response);
        gate.assertExhausted();
        assertEquals(1, gate.events().size());
        assertEquals("Allow? [y=yes, N=no]", gate.events().getFirst().prompt());
        assertEquals(ApprovalResponse.APPROVED, gate.events().getFirst().response());
    }

    @Test
    void optionalDenyStepCanBeSkippedWhenNextRequiredStepMatches() {
        ScriptedApprovalGate gate = new ScriptedApprovalGate(List.of(
                ScriptedApprovalGate.Step.optionalDeny("private document model handoff", "medical-notes.docx"),
                ScriptedApprovalGate.Step.approve("talos.write_file", "notes.md")));

        ApprovalResponse response = gate.approveFull(
                "Permission policy requires approval before running talos.write_file.",
                "target: notes.md");

        assertEquals(ApprovalResponse.APPROVED, response);
        gate.assertExhausted();
        assertEquals(1, gate.events().size());
    }

    @Test
    void repeatableOptionalDenyStepCanHandleLiveModelRepeatedPrivateDocumentPrompts() {
        ScriptedApprovalGate gate = new ScriptedApprovalGate(List.of(
                ScriptedApprovalGate.Step.repeatableOptionalDeny("private document model handoff", ""),
                ScriptedApprovalGate.Step.approve("talos.write_file", "notes.md")));

        ApprovalResponse first = gate.approveOnce(
                "private document model handoff: talos.read_file",
                "target: health-summary.pdf");
        ApprovalResponse second = gate.approveOnce(
                "private document model handoff: talos.read_file",
                "target: bank-statement.docx");
        ApprovalResponse write = gate.approveFull(
                "Permission policy requires approval before running talos.write_file.",
                "target: notes.md");

        assertEquals(ApprovalResponse.DENIED, first);
        assertEquals(ApprovalResponse.DENIED, second);
        assertEquals(ApprovalResponse.APPROVED, write);
        gate.assertExhausted();
        assertEquals(3, gate.events().size());
    }
}
