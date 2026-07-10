package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationTraceEvidenceTest {
    @TempDir
    Path temp;

    @Test
    void readFirstIsAnAllowedAuxiliaryBeforeTheRequiredMutationDecision() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A08-technical-exact-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.read_file", "notes.md"),
                executed("talos.read_file", "notes.md"),
                parsed("talos.edit_file", "notes.md"),
                approval("APPROVAL_GRANTED", "talos.edit_file", "notes.md"),
                executed("talos.edit_file", "notes.md")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult("edit-status", true)), facts.decisions());
        assertEquals(0, facts.unnecessaryExecutedCalls());
        assertTrue(facts.calls().stream().allMatch(QualificationTraceEvidence.Call::allowed));
    }

    @Test
    void aWrongMutationToolIsNotRewrittenByALaterCorrectTool() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A08-technical-exact-edit");
        Path trace = writeTrace(List.of(
                parsed("talos.write_file", "notes.md"),
                executed("talos.write_file", "notes.md"),
                parsed("talos.edit_file", "notes.md"),
                executed("talos.edit_file", "notes.md")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult("edit-status", false)), facts.decisions());
        assertEquals(1, facts.unnecessaryExecutedCalls());
    }

    @Test
    void deniedProposalStillCountsAsTheRequiredToolDecision() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F09-contradictory-deny-after-proposal");
        Path trace = writeTrace(List.of(
                parsed("talos.write_file", "decision.txt"),
                approval("APPROVAL_DENIED", "talos.write_file", "decision.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(List.of(new QualificationRecords.DecisionResult("propose-create", true)), facts.decisions());
        assertFalse(facts.calls().getFirst().executed());
        assertTrue(facts.approvalDenied());
    }

    private Path writeTrace(List<Map<String, Object>> events) throws Exception {
        Path trace = temp.resolve("trace-" + System.nanoTime() + ".json");
        QualificationJson.write(trace, Map.of(
                "schemaVersion", 2,
                "traceId", "trace-test",
                "model", Map.of("backend", "llama_cpp", "model", "qwen14-runtime"),
                "taskContract", Map.of(
                        "type", "FILE_EDIT", "mutationAllowed", true, "verificationRequired", true),
                "promptAudit", Map.of(
                        "taskType", "FILE_EDIT",
                        "mutationAllowed", true,
                        "actionObligation", "MUTATING_TOOL_REQUIRED"),
                "events", events,
                "verification", Map.of("status", "PASSED"),
                "outcome", Map.of("status", "COMPLETE", "classification", "COMPLETED_VERIFIED")));
        return trace;
    }

    private static Map<String, Object> parsed(String tool, String path) {
        return event("TOOL_CALL_PARSED", tool, path, Map.of());
    }

    private static Map<String, Object> executed(String tool, String path) {
        return event("TOOL_EXECUTED", tool, path, Map.of("success", true));
    }

    private static Map<String, Object> approval(String type, String tool, String path) {
        return event(type, tool, path, Map.of());
    }

    private static Map<String, Object> event(
            String type,
            String tool,
            String path,
            Map<String, Object> extra) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("pathHint", path);
        data.putAll(extra);
        return Map.of(
                "type", type,
                "timestamp", "2026-07-10T00:00:00Z",
                "phase", "APPLY",
                "toolName", tool,
                "data", data);
    }
}
