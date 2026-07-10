package dev.talos.qualification;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class QualificationScorer {
    private static final int ADMISSION_TRIALS = 12;
    private static final int FULL_TRIALS = 72;

    private QualificationScorer() {}

    record PerformanceEnvelope(double doctorProjectionSeconds, double configuredTimeoutSeconds) {}

    static QualificationRecords.Scorecard score(
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.BankKind bankKind,
            List<QualificationRecords.RunResult> inputRuns,
            PerformanceEnvelope performance) {
        QualificationProtocol.ScenarioBank bank = catalog.bank(bankKind);
        List<QualificationRecords.RunResult> runs = inputRuns == null ? List.of() : List.copyOf(inputRuns);
        List<String> findings = new ArrayList<>();
        if (performance == null || !Double.isFinite(performance.doctorProjectionSeconds())
                || !Double.isFinite(performance.configuredTimeoutSeconds())
                || performance.doctorProjectionSeconds() < 0
                || performance.configuredTimeoutSeconds() <= 0) {
            findings.add("INVALID_PERFORMANCE_ENVELOPE");
            return scorecard(bankKind, QualificationRecords.Disposition.BLOCKED_INCOMPLETE,
                    bankKind == QualificationProtocol.BankKind.ADMISSION ? ADMISSION_TRIALS : FULL_TRIALS,
                    inputRuns == null ? List.of() : List.copyOf(inputRuns), catalog.bank(bankKind),
                    new PerformanceEnvelope(0.0, 1.0), findings);
        }
        int expectedTrials = bankKind == QualificationProtocol.BankKind.ADMISSION
                ? ADMISSION_TRIALS : FULL_TRIALS;

        List<String> trustFailures = trustFailures(runs);
        if (!trustFailures.isEmpty()) {
            findings.addAll(trustFailures);
            return scorecard(bankKind, QualificationRecords.Disposition.BLOCKED_TRUST,
                    expectedTrials, runs, bank, performance, findings);
        }

        findings.addAll(completenessFindings(catalog, bankKind, runs, expectedTrials));
        if (!findings.isEmpty()) {
            return scorecard(bankKind, QualificationRecords.Disposition.BLOCKED_INCOMPLETE,
                    expectedTrials, runs, bank, performance, findings);
        }

        ScoreFacts facts = facts(bank, runs);
        QualificationRecords.Disposition disposition;
        if (bankKind == QualificationProtocol.BankKind.ADMISSION) {
            boolean passed = facts.passedTrials >= 10
                    && facts.requiredToolAccuracy.value() >= (5.0 / 6.0)
                    && facts.noToolAccuracy.numerator() == 3
                    && facts.noToolAccuracy.denominator() == 3
                    && facts.unrecoveredMalformed.numerator() <= 1
                    && facts.timeoutCount == 0;
            disposition = passed
                    ? QualificationRecords.Disposition.ADMISSION_PASSED
                    : QualificationRecords.Disposition.ADMISSION_FAILED;
            if (!passed) findings.addAll(admissionMisses(facts));
        } else {
            boolean accepted = facts.successfulScenarioDefinitions >= 20
                    && facts.successfulByArchetype.values().stream().allMatch(count -> count >= 2)
                    && facts.requiredToolAccuracy.value() >= 0.90
                    && facts.noToolAccuracy.value() >= 0.95
                    && facts.unrecoveredMalformed.value() <= 0.02
                    && facts.requirementCoverage.value() >= 0.85
                    && facts.outputCapRate.value() <= 0.05
                    && facts.timeoutCount == 0
                    && performance.doctorProjectionSeconds() <= 60.0
                    && performance.doctorProjectionSeconds() <= performance.configuredTimeoutSeconds() * 0.50;
            boolean experimental = facts.successfulScenarioDefinitions >= 16
                    && facts.requiredToolAccuracy.value() >= 0.75
                    && facts.noToolAccuracy.value() >= 0.90
                    && facts.unrecoveredMalformed.value() <= 0.10
                    && facts.outputCapRate.value() <= 0.15
                    && facts.timeoutCount <= 1
                    && performance.doctorProjectionSeconds() <= 150.0;
            if (accepted) {
                disposition = QualificationRecords.Disposition.ACCEPTED_BETA;
            } else if (experimental) {
                disposition = QualificationRecords.Disposition.EXPERIMENTAL_SELECTABLE;
                findings.addAll(acceptedMisses(facts, performance));
            } else {
                disposition = QualificationRecords.Disposition.REJECTED;
                findings.addAll(acceptedMisses(facts, performance));
                findings.addAll(experimentalMisses(facts, performance));
            }
        }

        return scorecard(bankKind, disposition, expectedTrials, runs, bank, performance, findings);
    }

    private static List<String> admissionMisses(ScoreFacts facts) {
        List<String> misses = new ArrayList<>();
        if (facts.passedTrials < 10) misses.add("ADMISSION_USEFUL_OUTCOMES_BELOW_10_OF_12");
        if (facts.requiredToolAccuracy.value() < (5.0 / 6.0)) misses.add("ADMISSION_REQUIRED_TOOL_BELOW_5_OF_6");
        if (facts.noToolAccuracy.numerator() != 3 || facts.noToolAccuracy.denominator() != 3) {
            misses.add("ADMISSION_ABSTENTION_NOT_3_OF_3");
        }
        if (facts.unrecoveredMalformed.numerator() > 1) misses.add("ADMISSION_MALFORMED_CALLS_ABOVE_1");
        if (facts.timeoutCount > 0) misses.add("ADMISSION_TIMEOUT_PRESENT");
        return misses;
    }

    private static List<String> acceptedMisses(ScoreFacts facts, PerformanceEnvelope performance) {
        List<String> misses = new ArrayList<>();
        if (facts.successfulScenarioDefinitions < 20) misses.add("ACCEPTED_SCENARIOS_BELOW_20_OF_24");
        facts.successfulByArchetype.forEach((archetype, count) -> {
            if (count < 2) misses.add("ACCEPTED_ARCHETYPE_BELOW_2_OF_3:" + archetype);
        });
        if (facts.requiredToolAccuracy.value() < 0.90) misses.add("ACCEPTED_REQUIRED_TOOL_BELOW_0.90");
        if (facts.noToolAccuracy.value() < 0.95) misses.add("ACCEPTED_ABSTENTION_BELOW_0.95");
        if (facts.unrecoveredMalformed.value() > 0.02) misses.add("ACCEPTED_MALFORMED_ABOVE_0.02");
        if (facts.requirementCoverage.value() < 0.85) misses.add("ACCEPTED_REQUIREMENTS_BELOW_0.85");
        if (facts.outputCapRate.value() > 0.05) misses.add("ACCEPTED_CAP_HITS_ABOVE_0.05");
        if (facts.timeoutCount > 0) misses.add("ACCEPTED_TIMEOUT_PRESENT");
        if (performance.doctorProjectionSeconds() > 60.0) misses.add("ACCEPTED_PROJECTION_ABOVE_60_SECONDS");
        if (performance.doctorProjectionSeconds() > performance.configuredTimeoutSeconds() * 0.50) {
            misses.add("ACCEPTED_PROJECTION_ABOVE_HALF_TIMEOUT");
        }
        return misses;
    }

    private static List<String> experimentalMisses(ScoreFacts facts, PerformanceEnvelope performance) {
        List<String> misses = new ArrayList<>();
        if (facts.successfulScenarioDefinitions < 16) misses.add("EXPERIMENTAL_SCENARIOS_BELOW_16_OF_24");
        if (facts.requiredToolAccuracy.value() < 0.75) misses.add("EXPERIMENTAL_REQUIRED_TOOL_BELOW_0.75");
        if (facts.noToolAccuracy.value() < 0.90) misses.add("EXPERIMENTAL_ABSTENTION_BELOW_0.90");
        if (facts.unrecoveredMalformed.value() > 0.10) misses.add("EXPERIMENTAL_MALFORMED_ABOVE_0.10");
        if (facts.outputCapRate.value() > 0.15) misses.add("EXPERIMENTAL_CAP_HITS_ABOVE_0.15");
        if (facts.timeoutCount > 1) misses.add("EXPERIMENTAL_REPEATED_TIMEOUT");
        if (performance.doctorProjectionSeconds() > 150.0) misses.add("EXPERIMENTAL_PROJECTION_ABOVE_150_SECONDS");
        return misses;
    }

    private static QualificationRecords.Scorecard scorecard(
            QualificationProtocol.BankKind bankKind,
            QualificationRecords.Disposition disposition,
            int expectedTrials,
            List<QualificationRecords.RunResult> runs,
            QualificationProtocol.ScenarioBank bank,
            PerformanceEnvelope performance,
            List<String> findings) {
        ScoreFacts facts = facts(bank, runs);
        return new QualificationRecords.Scorecard(
                QualificationRecords.SCORECARD_SCHEMA,
                QualificationRecords.SCHEMA_VERSION,
                bankKind,
                disposition,
                expectedTrials,
                (int) runs.stream().filter(run -> run.valid() && run.evidenceComplete()).count(),
                (int) runs.stream().filter(run -> !run.valid() || !run.evidenceComplete()).count(),
                bank.scenarios().size(),
                facts.successfulScenarioDefinitions,
                facts.taskCompletion,
                facts.requirementCoverage,
                facts.requiredToolAccuracy,
                facts.noToolAccuracy,
                facts.nativeToolValidity,
                facts.unnecessaryToolRate,
                facts.unrecoveredMalformed,
                facts.outputCapRate,
                facts.timeoutCount,
                facts.repairCount,
                performance.doctorProjectionSeconds(),
                performance.configuredTimeoutSeconds(),
                facts.successfulByArchetype,
                failureClassCounts(runs),
                List.copyOf(findings));
    }

    private static Map<String, Integer> failureClassCounts(List<QualificationRecords.RunResult> runs) {
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (QualificationRecords.FailureClass failureClass : QualificationRecords.FailureClass.values()) {
            counts.put(failureClass.name(), 0);
        }
        runs.forEach(run -> counts.merge(run.failureClass().name(), 1, Integer::sum));
        return counts;
    }

    private static List<String> trustFailures(List<QualificationRecords.RunResult> runs) {
        List<String> findings = new ArrayList<>();
        for (QualificationRecords.RunResult run : runs) {
            for (String gate : run.trustGates().failures()) {
                findings.add("TRUST_GATE_FAILED:" + run.scenarioId() + ":" + run.repetitionId() + ":" + gate);
            }
            if (run.failureClass() == QualificationRecords.FailureClass.TALOS_RUNTIME_DEFECT) {
                findings.add("TALOS_RUNTIME_DEFECT:" + run.scenarioId() + ":" + run.repetitionId());
            }
        }
        return findings.stream().sorted().distinct().toList();
    }

    private static List<String> completenessFindings(
            QualificationScenarioBank.Catalog catalog,
            QualificationProtocol.BankKind bankKind,
            List<QualificationRecords.RunResult> runs,
            int expectedTrials) {
        List<String> findings = new ArrayList<>();
        if (runs.size() != expectedTrials) {
            findings.add("TRIAL_COUNT_MISMATCH:expected=" + expectedTrials + ":actual=" + runs.size());
        }
        int repetitions = bankKind == QualificationProtocol.BankKind.ADMISSION ? 1 : 3;
        Set<String> expected = new HashSet<>();
        for (QualificationProtocol.Scenario scenario : catalog.bank(bankKind).scenarios()) {
            for (int repetition = 1; repetition <= repetitions; repetition++) {
                expected.add(scenario.id() + "#" + repetition);
            }
        }
        Set<String> actual = new HashSet<>();
        for (QualificationRecords.RunResult run : runs) {
            String identity = run.scenarioId() + "#" + run.repetition();
            if (!actual.add(identity)) findings.add("DUPLICATE_TRIAL:" + identity);
            if (!expected.contains(identity)) findings.add("UNEXPECTED_TRIAL:" + identity);
            if (!run.valid()) findings.add("INVALIDATED_TRIAL:" + identity + ":" + run.invalidReason());
            if (!run.evidenceComplete()) findings.add("INCOMPLETE_RUN_EVIDENCE:" + identity);
            if (!QualificationRecords.RUN_SCHEMA.equals(run.schemaName())
                    || run.schemaVersion() != QualificationRecords.SCHEMA_VERSION) {
                findings.add("RUN_SCHEMA_MISMATCH:" + identity);
            }
            if (!catalog.sha256().equalsIgnoreCase(run.scenarioBankSha256())) {
                findings.add("RUN_BANK_HASH_MISMATCH:" + identity);
            }
            if (run.failureClass() == QualificationRecords.FailureClass.INCOMPLETE_EVIDENCE) {
                findings.add("INCOMPLETE_EVIDENCE_CLASS:" + identity);
            }
        }
        for (String missing : expected) {
            if (!actual.contains(missing)) findings.add("MISSING_TRIAL:" + missing);
        }
        if (runs.stream().map(QualificationRecords.RunResult::campaignId).distinct().count() > 1
                || runs.stream().map(QualificationRecords.RunResult::candidateAlias).distinct().count() > 1
                || runs.stream().map(QualificationRecords.RunResult::machineId).distinct().count() > 1
                || runs.stream().map(QualificationRecords.RunResult::engineLaneId).distinct().count() > 1
                || runs.stream().map(QualificationRecords.RunResult::configId).distinct().count() > 1) {
            findings.add("MIXED_PACKET_IDENTITY");
        }
        ScoreFacts facts = facts(catalog.bank(bankKind), runs);
        if (facts.requiredToolAccuracy.denominator() == 0) findings.add("ZERO_REQUIRED_TOOL_DENOMINATOR");
        if (facts.noToolAccuracy.denominator() == 0) findings.add("ZERO_NO_TOOL_DENOMINATOR");
        if (facts.requirementCoverage.denominator() == 0) findings.add("ZERO_REQUIREMENT_DENOMINATOR");
        return findings.stream().sorted().distinct().toList();
    }

    private static ScoreFacts facts(
            QualificationProtocol.ScenarioBank bank,
            List<QualificationRecords.RunResult> runs) {
        Map<String, QualificationProtocol.Scenario> scenarios = new HashMap<>();
        bank.scenarios().forEach(scenario -> scenarios.put(scenario.id(), scenario));

        long passedTrials = 0;
        long requirementPassed = 0;
        long requirementTotal = 0;
        long requiredCorrect = 0;
        long requiredTotal = 0;
        long noToolCorrect = 0;
        long noToolTotal = 0;
        long nativeValid = 0;
        long nativeAttempts = 0;
        long unnecessary = 0;
        long executedCalls = 0;
        long malformed = 0;
        long toolEligibleGenerations = 0;
        long capHits = 0;
        long invalidated = 0;
        int timeoutCount = 0;
        int repairCount = 0;
        Map<String, Integer> trialPasses = new HashMap<>();

        for (QualificationRecords.RunResult run : runs) {
            QualificationProtocol.Scenario scenario = scenarios.get(run.scenarioId());
            if (scenario == null) continue;
            if (!run.valid() || !run.evidenceComplete()) {
                invalidated++;
                continue;
            }
            boolean requirementsPass = run.requirements().size() == scenario.atomicRequirements().size()
                    && run.requirements().stream().allMatch(QualificationRecords.RequirementResult::passed);
            boolean terminalPass = scenario.expectedTerminalStates().contains(run.terminalState());
            boolean trialPass = run.valid() && run.evidenceComplete() && run.trustGates().passed()
                    && !run.timedOut() && requirementsPass && terminalPass;
            if (trialPass) {
                passedTrials++;
                trialPasses.merge(scenario.id(), 1, Integer::sum);
            }

            Map<String, QualificationRecords.DecisionResult> decisionResults = new HashMap<>();
            run.decisions().forEach(decision -> decisionResults.putIfAbsent(decision.id(), decision));
            for (QualificationProtocol.ExpectedDecision expected : scenario.expectedDecisions()) {
                if (!expected.scored()) continue;
                boolean correct = decisionResults.getOrDefault(
                        expected.id(), new QualificationRecords.DecisionResult(expected.id(), false)).correct();
                if (expected.kind() == QualificationProtocol.DecisionKind.REQUIRED_TOOL) {
                    requiredTotal++;
                    if (correct) requiredCorrect++;
                } else {
                    noToolTotal++;
                    if (correct) noToolCorrect++;
                }
            }
            Map<String, QualificationRecords.RequirementResult> requirementResults = new HashMap<>();
            run.requirements().forEach(result -> requirementResults.putIfAbsent(result.id(), result));
            for (QualificationProtocol.AtomicRequirement requirement : scenario.atomicRequirements()) {
                requirementTotal++;
                if (requirementResults.getOrDefault(
                        requirement.id(), new QualificationRecords.RequirementResult(requirement.id(), false)).passed()) {
                    requirementPassed++;
                }
            }
            for (QualificationRecords.ToolCallResult call : run.toolCalls()) {
                nativeAttempts++;
                if (call.schemaValid()) nativeValid++;
                if (call.executed()) {
                    executedCalls++;
                    if (!call.allowed()) unnecessary++;
                }
            }
            malformed += Math.max(0, run.malformedToolCalls());
            toolEligibleGenerations += Math.max(0, run.toolEligibleGenerations());
            if (run.outputCapHit()) capHits++;
            if (run.timedOut()) timeoutCount++;
            repairCount += Math.max(0, run.repairAttempts());
        }

        int repetitionThreshold = bank.kind() == QualificationProtocol.BankKind.ADMISSION ? 1 : 2;
        int successfulDefinitions = (int) bank.scenarios().stream()
                .filter(scenario -> trialPasses.getOrDefault(scenario.id(), 0) >= repetitionThreshold)
                .count();
        EnumMap<QualificationProtocol.Archetype, Integer> byArchetype =
                new EnumMap<>(QualificationProtocol.Archetype.class);
        for (QualificationProtocol.Archetype archetype : QualificationProtocol.Archetype.values()) {
            byArchetype.put(archetype, 0);
        }
        for (QualificationProtocol.Scenario scenario : bank.scenarios()) {
            if (trialPasses.getOrDefault(scenario.id(), 0) >= repetitionThreshold) {
                byArchetype.merge(scenario.archetype(), 1, Integer::sum);
            }
        }
        Map<String, Integer> serializedArchetypes = new LinkedHashMap<>();
        byArchetype.forEach((key, value) -> serializedArchetypes.put(key.name(), value));

        return new ScoreFacts(
                (int) passedTrials,
                successfulDefinitions,
                serializedArchetypes,
                rate(passedTrials, runs.size() - invalidated, invalidated, "TASK_COMPLETION"),
                rate(requirementPassed, requirementTotal, invalidated, "REQUIREMENT_COVERAGE"),
                rate(requiredCorrect, requiredTotal, invalidated, "REQUIRED_TOOL_ACCURACY"),
                rate(noToolCorrect, noToolTotal, invalidated, "NO_TOOL_ABSTENTION_ACCURACY"),
                rate(nativeValid, nativeAttempts, invalidated, "NATIVE_TOOL_CALL_VALIDITY"),
                rate(unnecessary, executedCalls, invalidated, "UNNECESSARY_TOOL_CALL_RATE"),
                rate(malformed, toolEligibleGenerations, invalidated, "UNRECOVERED_MALFORMED_CALL_RATE"),
                rate(capHits, runs.size() - invalidated, invalidated, "OUTPUT_CAP_HIT_RATE"),
                timeoutCount,
                repairCount);
    }

    private static QualificationRecords.RateMetric rate(
            long numerator,
            long denominator,
            long invalidated,
            String reason) {
        double value = denominator == 0 ? 0.0 : (double) numerator / denominator;
        double[] interval = wilson(numerator, denominator);
        List<String> reasons;
        if (denominator == 0) {
            reasons = List.of(reason + "_ZERO_DENOMINATOR");
        } else if (invalidated > 0) {
            reasons = List.of("INVALIDATED_TRIALS_PRESENT");
        } else {
            reasons = List.of();
        }
        return new QualificationRecords.RateMetric(
                numerator, denominator, invalidated, value, interval[0], interval[1], reasons);
    }

    private static double[] wilson(long numerator, long denominator) {
        if (denominator <= 0) return new double[]{0.0, 0.0};
        double z = 1.959963984540054;
        double n = denominator;
        double p = numerator / n;
        double zSquared = z * z;
        double denominatorTerm = 1.0 + zSquared / n;
        double center = (p + zSquared / (2.0 * n)) / denominatorTerm;
        double margin = z * Math.sqrt((p * (1.0 - p) + zSquared / (4.0 * n)) / n)
                / denominatorTerm;
        return new double[]{Math.max(0.0, center - margin), Math.min(1.0, center + margin)};
    }

    private record ScoreFacts(
            int passedTrials,
            int successfulScenarioDefinitions,
            Map<String, Integer> successfulByArchetype,
            QualificationRecords.RateMetric taskCompletion,
            QualificationRecords.RateMetric requirementCoverage,
            QualificationRecords.RateMetric requiredToolAccuracy,
            QualificationRecords.RateMetric noToolAccuracy,
            QualificationRecords.RateMetric nativeToolValidity,
            QualificationRecords.RateMetric unnecessaryToolRate,
            QualificationRecords.RateMetric unrecoveredMalformed,
            QualificationRecords.RateMetric outputCapRate,
            int timeoutCount,
            int repairCount) {}
}
