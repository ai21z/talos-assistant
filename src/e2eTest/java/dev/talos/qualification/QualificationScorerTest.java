package dev.talos.qualification;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QualificationScorerTest {

    @Test
    void perfectFullPacketMeetsAcceptedThreshold() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 24, true);

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(45.0, 300.0));

        assertEquals(QualificationRecords.Disposition.ACCEPTED_BETA, scorecard.disposition());
        assertEquals(24, scorecard.successfulScenarioDefinitions());
        assertEquals(72, scorecard.validTrials());
        assertEquals(0, scorecard.invalidatedTrials());
        assertEquals(1.0, scorecard.requiredToolAccuracy());
        assertEquals(1.0, scorecard.noToolAbstentionAccuracy());
        assertTrue(scorecard.taskCompletion().wilsonLower95() > 0.94);
        assertEquals(1.0, scorecard.taskCompletion().wilsonUpper95());
        assertEquals(72, scorecard.causalOwnerCounts().get("MODEL"));
        assertEquals(0, scorecard.causalOwnerCounts().get("UNKNOWN"));
    }

    @Test
    void fullScoreIgnoresRetainedAdmissionRunsFromTheSameCampaign() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = new ArrayList<>(admissionRuns(catalog));
        runs.addAll(fullRuns(catalog, 24, true));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(45.0, 300.0));

        assertEquals(QualificationRecords.Disposition.ACCEPTED_BETA, scorecard.disposition());
        assertEquals(72, scorecard.validTrials());
    }

    @Test
    void exactTwentyScenarioBoundaryIsAccepted() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 20, true);

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(60.0, 300.0));

        assertEquals(QualificationRecords.Disposition.ACCEPTED_BETA, scorecard.disposition());
        assertEquals(20, scorecard.successfulScenarioDefinitions());
    }

    @Test
    void sixteenScenarioBoundaryIsExperimental() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 16, true);

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(150.0, 300.0));

        assertEquals(QualificationRecords.Disposition.EXPERIMENTAL_SELECTABLE, scorecard.disposition());
    }

    @Test
    void trustFailureBlocksBeforeUtilityScore() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 24, true);
        QualificationRecords.RunResult first = runs.getFirst();
        runs.set(0, first.withTrustGates(first.trustGates().withCompletionTruth(false)));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.BLOCKED_TRUST, scorecard.disposition());
        assertTrue(scorecard.findings().stream().anyMatch(finding -> finding.contains("completionTruth")));
    }

    @Test
    void missingTrialBlocksAsIncompleteEvidence() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 24, true);
        runs.removeLast();

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.BLOCKED_INCOMPLETE, scorecard.disposition());
    }

    @Test
    void invalidatedTrialCannotDisappearFromThePacket() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 24, true);
        runs.set(0, runs.getFirst().withValidity(false, "runtime defect under investigation"));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.BLOCKED_INCOMPLETE, scorecard.disposition());
        assertEquals(1, scorecard.invalidatedTrials());
    }

    @Test
    void runtimeAuthoredAnswerCannotBeScoredAsModelFailure() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = admissionRuns(catalog);
        runs.set(0, runs.getFirst().withFailure(
                QualificationRecords.FailureClass.MODEL_FAILURE,
                QualificationRecords.AnswerOrigin.RUNTIME_DETERMINISTIC,
                QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                QualificationRecords.CausalOwner.RUNTIME_POLICY,
                List.of(new QualificationRecords.CausalLedgerEntry(
                        1, QualificationRecords.CausalStage.RESPONSE_CONTRACT,
                        QualificationRecords.CausalOwner.RUNTIME_POLICY,
                        QualificationRecords.CausalFact.RESPONSE_OWNER_CONTRACT_VIOLATED,
                        QualificationRecords.CausalReason.RESPONSE_OWNER_CONTRACT_VIOLATED,
                        "trace.json#OUTCOME_RENDERED", true))));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.ADMISSION, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.BLOCKED_INCOMPLETE,
                scorecard.disposition());
        assertTrue(scorecard.findings().stream().anyMatch(finding ->
                finding.contains("MODEL_FAILURE_ANSWER_NOT_MODEL_PROVIDER")));
        assertTrue(scorecard.findings().stream().anyMatch(finding ->
                finding.contains("MODEL_FAILURE_CAUSAL_OWNER_NOT_MODEL")));
    }

    @Test
    void mixedOrUnknownCausalOwnershipBlocksComparativeScoring() throws Exception {
        for (QualificationRecords.CausalOwner owner : List.of(
                QualificationRecords.CausalOwner.MIXED,
                QualificationRecords.CausalOwner.UNKNOWN)) {
            QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
            List<QualificationRecords.RunResult> runs = admissionRuns(catalog);
            QualificationRecords.RunResult unresolved = runs.getFirst().withFailure(
                    QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE,
                    QualificationRecords.AnswerOrigin.NONE,
                    QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                    owner,
                    List.of(new QualificationRecords.CausalLedgerEntry(
                            1, QualificationRecords.CausalStage.UTILITY, owner,
                            QualificationRecords.CausalFact.CAUSE_NOT_PROVEN,
                            owner == QualificationRecords.CausalOwner.MIXED
                                    ? QualificationRecords.CausalReason.MULTIPLE_CONTRIBUTING_OWNERS
                                    : QualificationRecords.CausalReason.CAUSE_NOT_MECHANICALLY_PROVEN,
                            "trace.json#events", true)));
            ObjectNode forged = (ObjectNode) QualificationJson.readTree(
                    QualificationJson.bytes(unresolved));
            forged.put("comparativeScoreEligible", true);
            runs.set(0, QualificationJson.read(
                    QualificationJson.bytes(forged),
                    QualificationRecords.RunResult.class));

            QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                    catalog, QualificationProtocol.BankKind.ADMISSION, runs,
                    new QualificationScorer.PerformanceEnvelope(20.0, 300.0));
            QualificationRecords.Scorecard withoutNonComparativeRun = QualificationScorer.score(
                    catalog, QualificationProtocol.BankKind.ADMISSION,
                    new ArrayList<>(runs.subList(1, runs.size())),
                    new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

            assertEquals(QualificationRecords.Disposition.BLOCKED_INCOMPLETE,
                    scorecard.disposition(), owner.name());
            assertEquals(11, scorecard.validTrials(), owner.name());
            assertEquals(1, scorecard.invalidatedTrials(), owner.name());
            assertSameMetricPopulation(
                    withoutNonComparativeRun.taskCompletion(),
                    scorecard.taskCompletion(), owner.name() + ":task");
            assertSameMetricPopulation(
                    withoutNonComparativeRun.requirementCoverage(),
                    scorecard.requirementCoverage(), owner.name() + ":requirements");
            assertSameMetricPopulation(
                    withoutNonComparativeRun.requiredToolAccuracyMetric(),
                    scorecard.requiredToolAccuracyMetric(), owner.name() + ":required-tool");
            assertSameMetricPopulation(
                    withoutNonComparativeRun.noToolAbstentionAccuracyMetric(),
                    scorecard.noToolAbstentionAccuracyMetric(), owner.name() + ":no-tool");
            assertTrue(scorecard.findings().stream().anyMatch(finding ->
                            finding.contains("CAUSAL_OWNER_" + owner.name())),
                    scorecard.findings().toString());
        }
    }

    @Test
    void providerOwnedWrongScoredDecisionDoesNotCountAsTaskCompletion() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = admissionRuns(catalog);
        int scenarioIndex = java.util.stream.IntStream.range(
                        0, catalog.admission().scenarios().size())
                .filter(index -> catalog.admission().scenarios().get(index)
                        .expectedDecisions().stream()
                        .anyMatch(QualificationProtocol.ExpectedDecision::scored))
                .findFirst()
                .orElseThrow();
        QualificationProtocol.Scenario scenario =
                catalog.admission().scenarios().get(scenarioIndex);
        QualificationRecords.RunResult wrongDecision =
                QualificationTestPacket.runResult(
                        catalog, scenario, 1, true, true, false)
                .withFailure(
                        QualificationRecords.FailureClass.MODEL_FAILURE,
                        QualificationRecords.AnswerOrigin.MODEL_PROVIDER,
                        QualificationRecords.ResponseOwnerContract.MODEL_SYNTHESIS_REQUIRED,
                        QualificationRecords.CausalOwner.MODEL,
                        List.of(new QualificationRecords.CausalLedgerEntry(
                                1,
                                QualificationRecords.CausalStage.DECISION,
                                QualificationRecords.CausalOwner.MODEL,
                                QualificationRecords.CausalFact.SCORED_DECISION_FAILED,
                                QualificationRecords.CausalReason.MODEL_DECISION_FAILED,
                                "trace.json#provider-native-decision",
                                true)));
        runs.set(scenarioIndex, wrongDecision);

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.ADMISSION, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(11, scorecard.taskCompletion().numerator());
        assertEquals(12, scorecard.taskCompletion().denominator());
        assertEquals(11, scorecard.successfulScenarioDefinitions());
        assertEquals(QualificationRecords.Disposition.ADMISSION_FAILED,
                scorecard.disposition());
    }

    @Test
    void admissionTimeoutFailsAdmission() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = admissionRuns(catalog);
        runs.set(0, runs.getFirst().withTimedOut(true));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.ADMISSION, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.ADMISSION_FAILED, scorecard.disposition());
        assertEquals(1, scorecard.timeoutCount());
    }

    @Test
    void twoUnrecoveredMalformedAdmissionCallsFailAdmission() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = admissionRuns(catalog);
        runs.set(0, runs.get(0).withMalformedToolCalls(1));
        runs.set(1, runs.get(1).withMalformedToolCalls(1));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.ADMISSION, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.ADMISSION_FAILED, scorecard.disposition());
    }

    @Test
    void malformedNativeAttemptsRemainInTheNativeValidityDenominator() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = admissionRuns(catalog);
        runs.set(0, runs.getFirst().withMalformedToolCalls(1));

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.ADMISSION, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        long parsedAttempts = runs.stream().mapToLong(run -> run.toolCalls().size()).sum();
        assertEquals(parsedAttempts + 1, scorecard.nativeToolCallValidity().denominator());
        assertEquals(parsedAttempts, scorecard.nativeToolCallValidity().numerator());
    }

    @Test
    void outputCapRateAboveAcceptedThresholdCannotBeAccepted() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 24, true);
        for (int index = 0; index < 4; index++) {
            runs.set(index, runs.get(index).withOutputCapHit(true));
        }

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs,
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.EXPERIMENTAL_SELECTABLE, scorecard.disposition());
        assertTrue(scorecard.outputCapHitRate().value() > 0.05);
    }

    @Test
    void zeroDecisionDenominatorFailsClosed() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationProtocol.Scenario> scenarios = catalog.full().scenarios().stream()
                .map(scenario -> new QualificationProtocol.Scenario(
                        scenario.id(), scenario.bank(), scenario.archetype(), scenario.category(),
                        scenario.fixture(), scenario.fixtureSha256(), scenario.prompt(),
                        scenario.approvalBehavior(), scenario.responseOwnerContract(),
                        scenario.expectedTerminalStates(),
                        scenario.allowedTargets(), scenario.forbiddenTargets(), List.of(),
                        scenario.atomicRequirements()))
                .toList();
        QualificationProtocol.ScenarioBank noDecisions = new QualificationProtocol.ScenarioBank(
                QualificationProtocol.BANK_SCHEMA, QualificationProtocol.VERSION,
                "full-no-decisions", QualificationProtocol.BankKind.FULL, scenarios);
        QualificationScenarioBank.Catalog invalidCatalog = new QualificationScenarioBank.Catalog(
                catalog.protocolVersion(), catalog.admission(), noDecisions,
                catalog.fixtures(), catalog.sha256());

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                invalidCatalog, QualificationProtocol.BankKind.FULL,
                fullRuns(catalog, 24, true),
                new QualificationScorer.PerformanceEnvelope(20.0, 300.0));

        assertEquals(QualificationRecords.Disposition.BLOCKED_INCOMPLETE, scorecard.disposition());
        assertTrue(scorecard.findings().stream().anyMatch(finding -> finding.contains("DENOMINATOR")));
    }

    @Test
    void deterministicRescoringProducesIdenticalJson() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();
        List<QualificationRecords.RunResult> runs = fullRuns(catalog, 24, true);
        QualificationScorer.PerformanceEnvelope performance =
                new QualificationScorer.PerformanceEnvelope(45.0, 300.0);

        String first = QualificationJson.write(QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, runs, performance));
        String second = QualificationJson.write(QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, List.copyOf(runs), performance));

        assertEquals(first, second);
    }

    @Test
    void invalidPerformanceEnvelopeFailsClosed() {
        QualificationScenarioBank.Catalog catalog = QualificationScenarioBank.loadDefault();

        QualificationRecords.Scorecard scorecard = QualificationScorer.score(
                catalog, QualificationProtocol.BankKind.FULL, fullRuns(catalog, 24, true),
                new QualificationScorer.PerformanceEnvelope(Double.NaN, 300.0));

        assertEquals(QualificationRecords.Disposition.BLOCKED_INCOMPLETE, scorecard.disposition());
        assertTrue(scorecard.findings().contains("INVALID_PERFORMANCE_ENVELOPE"));
    }

    private static List<QualificationRecords.RunResult> fullRuns(
            QualificationScenarioBank.Catalog catalog,
            int successfulScenarios,
            boolean correctDecisions) {
        List<QualificationRecords.RunResult> runs = new ArrayList<>();
        int scenarioIndex = 0;
        for (QualificationProtocol.Scenario scenario : catalog.full().scenarios()) {
            boolean scenarioPass;
            if (successfulScenarios == 20) {
                scenarioPass = !java.util.Set.of(2, 5, 8, 11).contains(scenarioIndex);
            } else {
                scenarioPass = scenarioIndex < successfulScenarios;
            }
            scenarioIndex++;
            for (int trial = 1; trial <= 3; trial++) {
                runs.add(QualificationTestPacket.runResult(
                        catalog, scenario, trial, scenarioPass, true, correctDecisions));
            }
        }
        return runs;
    }

    private static List<QualificationRecords.RunResult> admissionRuns(
            QualificationScenarioBank.Catalog catalog) {
        List<QualificationRecords.RunResult> runs = new ArrayList<>();
        for (QualificationProtocol.Scenario scenario : catalog.admission().scenarios()) {
            runs.add(QualificationTestPacket.runResult(catalog, scenario, 1, true, true));
        }
        return runs;
    }

    private static void assertSameMetricPopulation(
            QualificationRecords.RateMetric expected,
            QualificationRecords.RateMetric actual,
            String label) {
        assertEquals(expected.numerator(), actual.numerator(), label + ":numerator");
        assertEquals(expected.denominator(), actual.denominator(), label + ":denominator");
    }
}
