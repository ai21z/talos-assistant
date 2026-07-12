package dev.talos.qualification;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationRequirementEvaluatorTest {
    @Test
    void exactEditRequirementsUseFinalBytesAndPreservationEvidence() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario scenario = catalog.scenario("A08-technical-exact-edit");
        Map<String, byte[]> before = Map.of(
                "notes.md", "Status: draft\nKeep this final line unchanged.\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> after = Map.of(
                "notes.md", "Status: ready\nKeep this final line unchanged.\n".getBytes(StandardCharsets.UTF_8));

        List<QualificationRecords.RequirementResult> results = QualificationRequirementEvaluator.evaluate(
                scenario, new QualificationRequirementEvaluator.Evidence("Done", "COMPLETED_VERIFIED", before, after));

        assertTrue(results.stream().allMatch(QualificationRecords.RequirementResult::passed));
    }

    @Test
    void unchangedRequirementDetectsAnyByteChange() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario scenario = catalog.scenario("A07-simple-create-file");
        Map<String, byte[]> before = Map.of(
                "README.md", "original\r\n".getBytes(StandardCharsets.UTF_8));
        Map<String, byte[]> after = Map.of(
                "README.md", "original\n".getBytes(StandardCharsets.UTF_8),
                "todo.md", "Review the local setup.".getBytes(StandardCharsets.UTF_8));

        List<QualificationRecords.RequirementResult> results = QualificationRequirementEvaluator.evaluate(
                scenario, new QualificationRequirementEvaluator.Evidence("Done", "COMPLETED_VERIFIED", before, after));

        assertFalse(results.stream().filter(result -> result.id().equals("readme-unchanged"))
                .findFirst().orElseThrow().passed());
    }

    @Test
    void answerShapeRequirementsAreMechanical() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        QualificationProtocol.Scenario bullets = catalog.scenario("F12-superuser-no-tool-policy-question");
        QualificationProtocol.Scenario items = catalog.scenario("F15-privacy-no-secret-handling-advice");

        assertTrue(QualificationRequirementEvaluator.evaluate(bullets,
                        new QualificationRequirementEvaluator.Evidence(
                                "- Read evidence records inspection.\n- Verification checks mutations.", "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));
        assertTrue(QualificationRequirementEvaluator.evaluate(items,
                        new QualificationRequirementEvaluator.Evidence(
                                "1. Keep credentials outside repositories.\n2. Restrict file permissions.", "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));
    }

    @Test
    void exactScalarAnswersAllowOneTerminalSentenceMarkButNoExtraContent() {
        QualificationScenarioBank.Catalog historical = QualificationScenarioBank.load(3);
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.load(4);
        QualificationProtocol.Scenario historicalNoAnswer =
                historical.scenario("F24-weak-hardware-no-tool-short-answer");
        QualificationProtocol.Scenario noAnswer = catalog.scenario("F24-weak-hardware-no-tool-short-answer");
        QualificationProtocol.Scenario status = catalog.scenario("F22-weak-hardware-short-read");

        assertFalse(QualificationRequirementEvaluator.evaluate(historicalNoAnswer,
                        new QualificationRequirementEvaluator.Evidence("No.", "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));

        assertTrue(QualificationRequirementEvaluator.evaluate(noAnswer,
                        new QualificationRequirementEvaluator.Evidence("No.", "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));
        assertTrue(QualificationRequirementEvaluator.evaluate(status,
                        new QualificationRequirementEvaluator.Evidence("draft.", "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));

        for (String ineligible : List.of("No, because approval is required", "\"No\"", "No...", "Not")) {
            assertFalse(QualificationRequirementEvaluator.evaluate(noAnswer,
                            new QualificationRequirementEvaluator.Evidence(ineligible, "", Map.of(), Map.of()))
                    .stream().allMatch(QualificationRecords.RequirementResult::passed), ineligible);
        }
    }

    @Test
    void f20V5RejectsNegatedAgreementAndRequiresBothSources() {
        QualificationProtocol.Scenario scenario = QualificationScenarioBank.load(5)
                .scenario("F20-context-compare-code-and-docs");
        String wrong = """
                The documented health endpoint is `/healthz`, as stated in `docs/operations.md`.
                The code does not explicitly mention it, so the sources do not agree.
                """;
        String correct = """
                `docs/operations.md` documents `/healthz`, and `src/Gateway.java` returns `/healthz`.
                Verdict: AGREE
                """;

        assertFalse(QualificationRequirementEvaluator.evaluate(scenario,
                        new QualificationRequirementEvaluator.Evidence(wrong, "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));
        assertTrue(QualificationRequirementEvaluator.evaluate(scenario,
                        new QualificationRequirementEvaluator.Evidence(correct, "", Map.of(), Map.of()))
                .stream().allMatch(QualificationRecords.RequirementResult::passed));
    }
}
