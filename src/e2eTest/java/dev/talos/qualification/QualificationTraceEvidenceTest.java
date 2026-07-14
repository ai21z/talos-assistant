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
    void queryToolExecutionKeepsEveryStructuredEvidenceSource() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        Path trace = writeTrace(List.of(
                parsed("talos.retrieve", ""),
                event(
                        "TOOL_EXECUTED",
                        "talos.retrieve",
                        "config.json",
                        Map.of(
                                "success", true,
                                "pathHints", List.of("config.json", "docs/operations.md")))));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);

        assertEquals(
                List.of("config.json", "docs/operations.md"),
                facts.calls().getFirst().sourcePaths());
        assertFalse(facts.calls().getFirst().allowed(),
                "source provenance must not rewrite preferred-tool scoring");
    }

    @Test
    void runtimeBoundedNegativeDisclosureIsParsedAsTypedTraceFact() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        Path trace = writeTrace(List.of(Map.of(
                "type", "PROTOCOL_SANITIZED",
                "timestamp", "2026-07-10T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "reason", "file-content lookup negative claim bounded to source evidence"))));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
        var method = facts.getClass().getDeclaredMethod("boundedFileLookupNegative");
        method.setAccessible(true);

        assertEquals(Boolean.TRUE, method.invoke(facts));
    }

    @Test
    void runtimeMissingEvidenceContainmentRequiresEventAndWarningTogether() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F04-technical-trace-port");
        Map<String, Object> containment = Map.of(
                "type", "PROTOCOL_SANITIZED",
                "timestamp", "2026-07-13T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "reason", "missing workspace evidence claim contained by runtime"));
        Path complete = writeTrace(
                List.of(containment),
                List.of(Map.of("code", "MISSING_EVIDENCE", "message", "Missing evidence.")));
        Path eventOnly = writeTrace(List.of(containment), List.of());
        Path warningOnly = writeTrace(
                List.of(),
                List.of(Map.of("code", "MISSING_EVIDENCE", "message", "Missing evidence.")));

        assertTrue(QualificationTraceEvidence.read(complete, scenario).missingEvidenceContained());
        assertFalse(QualificationTraceEvidence.read(eventOnly, scenario).missingEvidenceContained());
        assertFalse(QualificationTraceEvidence.read(warningOnly, scenario).missingEvidenceContained());
    }

    @Test
    void structuredGenerationAbortIsParsedOnceWithCanonicalReasonAndPartialFlag() throws Exception {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F01-simple-read-project");
        Map<String, Object> abort = Map.of(
                "type", "MODEL_GENERATION_ABORTED",
                "timestamp", "2026-07-14T00:00:00Z",
                "phase", "",
                "toolName", "",
                "data", Map.of(
                        "reasonCode", "REPETITION_LOOP",
                        "partialOutput", false));
        Path trace = writeTrace(List.of(abort));

        QualificationTraceEvidence.Facts facts = QualificationTraceEvidence.read(trace, scenario);
        var reason = facts.getClass().getDeclaredMethod("generationAbortReasonCode");
        var partial = facts.getClass().getDeclaredMethod("generationAbortPartialOutput");

        assertEquals("REPETITION_LOOP", reason.invoke(facts));
        assertEquals(Boolean.FALSE, partial.invoke(facts));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(writeTrace(List.of(abort, abort)), scenario));
        Map<String, Object> invalidReason = new java.util.LinkedHashMap<>(abort);
        invalidReason.put("data", Map.of(
                "reasonCode", "RAW_PROVIDER_DETAIL",
                "partialOutput", false));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(writeTrace(List.of(invalidReason)), scenario));
        Map<String, Object> missingPartial = new java.util.LinkedHashMap<>(abort);
        missingPartial.put("data", Map.of("reasonCode", "REPETITION_LOOP"));
        assertThrows(IllegalArgumentException.class,
                () -> QualificationTraceEvidence.read(writeTrace(List.of(missingPartial)), scenario));
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
        return writeTrace(events, List.of());
    }

    private Path writeTrace(
            List<Map<String, Object>> events,
            List<Map<String, Object>> warnings) throws Exception {
        Path trace = temp.resolve("trace-" + System.nanoTime() + ".json");
        QualificationJson.write(trace, Map.ofEntries(
                Map.entry("schemaVersion", 2),
                Map.entry("traceId", "trace-test"),
                Map.entry("model", Map.of("backend", "llama_cpp", "model", "qwen14-runtime")),
                Map.entry("taskContract", Map.of(
                        "type", "FILE_EDIT", "mutationAllowed", true, "verificationRequired", true)),
                Map.entry("promptAudit", Map.of(
                        "taskType", "FILE_EDIT",
                        "mutationAllowed", true,
                        "actionObligation", "MUTATING_TOOL_REQUIRED")),
                Map.entry("events", events),
                Map.entry("warnings", warnings),
                Map.entry("verification", Map.of("status", "PASSED")),
                Map.entry("outcome", Map.of(
                        "status", "COMPLETE", "classification", "COMPLETED_VERIFIED"))));
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
