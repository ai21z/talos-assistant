package dev.talos.qualification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    @Test
    void pathTransformingExecutionBindsToTheOnlyUnmatchedParsedCall() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        for (String tool : List.of("talos.rename_path", "talos.move_path", "talos.copy_path")) {
            Path trace = writeTrace(List.of(
                    parsed(tool, "source.txt"),
                    executed(tool, "destination.txt")));

            QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

            assertEquals(1, facts.calls().size(), tool);
            assertEquals("source.txt", facts.calls().getFirst().pathHint(), tool);
            assertTrue(facts.calls().getFirst().executed(), tool);
        }
    }

    @Test
    void acceptedAliasAndCanonicalExecutionShareOneToolIdentity() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        Path trace = writeTrace(List.of(
                parsed("rename", "source.txt"),
                executed("talos.rename_path", "destination.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals("talos.rename_path", facts.calls().getFirst().toolName());
        assertTrue(facts.calls().getFirst().executed());
    }

    @Test
    void uniqueExactPathWinsWhenMultipleSameToolCallsAreUnmatched() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        Path trace = writeTrace(List.of(
                parsed("talos.rename_path", "first.txt"),
                parsed("talos.rename_path", "second.txt"),
                executed("talos.rename_path", "second.txt")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertFalse(facts.calls().get(0).executed());
        assertTrue(facts.calls().get(1).executed());
    }

    @Test
    void blockedAttemptSettlesBeforeSuccessfulSameToolRetry() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A06-technical-find-port");
        Path trace = writeTrace(List.of(
                parsed("talos.grep", ""),
                blocked("talos.grep", ""),
                parsed("talos.grep", ""),
                executed("talos.grep", "")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(2, facts.calls().size());
        assertFalse(facts.calls().get(0).executed());
        assertTrue(facts.calls().get(1).executed());
    }

    @Test
    void failedExecutionSettlesBeforeSuccessfulSameToolRetry() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("A06-technical-find-port");
        Path trace = writeTrace(List.of(
                parsed("talos.grep", ""),
                failed("talos.grep", ""),
                parsed("talos.grep", ""),
                executed("talos.grep", "")));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(2, facts.calls().size());
        assertFalse(facts.calls().get(0).executed());
        assertTrue(facts.calls().get(1).executed());
    }

    @Test
    void ambiguousOrOrphanSuccessfulExecutionsRemainInvalid() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.loadDefault()
                .scenario("F08-contradictory-similar-file-names");
        Path ambiguous = writeTrace(List.of(
                parsed("talos.rename_path", "first.txt"),
                parsed("talos.rename_path", "second.txt"),
                executed("talos.rename_path", "destination.txt")));
        Path orphan = writeTrace(List.of(
                executed("talos.rename_path", "destination.txt")));
        Path orphanBlock = writeTrace(List.of(
                blocked("talos.rename_path", "destination.txt")));

        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(ambiguous, scenario));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(orphan, scenario));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(orphanBlock, scenario));
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

    private static Map<String, Object> failed(String tool, String path) {
        return event("TOOL_EXECUTED", tool, path, Map.of("success", false));
    }

    private static Map<String, Object> blocked(String tool, String path) {
        return event("TOOL_CALL_BLOCKED", tool, path, Map.of("reason", "INVALID_PARAMS"));
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
