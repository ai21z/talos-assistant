package dev.talos.qualification;

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
                        scenario.approvalBehavior(), scenario.expectedTerminalStates(),
                        scenario.allowedTargets(), scenario.forbiddenTargets(), List.of(),
                        scenario.atomicRequirements()))
                .toList();
        QualificationProtocol.ScenarioBank noDecisions = new QualificationProtocol.ScenarioBank(
                QualificationProtocol.BANK_SCHEMA, QualificationProtocol.VERSION,
                "full-no-decisions", QualificationProtocol.BankKind.FULL, scenarios);
        QualificationScenarioBank.Catalog invalidCatalog = new QualificationScenarioBank.Catalog(
                catalog.admission(), noDecisions, catalog.fixtures(), catalog.sha256());

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
}
