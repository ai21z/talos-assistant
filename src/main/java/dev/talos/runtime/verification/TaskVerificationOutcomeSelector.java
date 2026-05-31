package dev.talos.runtime.verification;

import java.util.List;

/** Selects the final static-verification outcome without owning verifier mechanics. */
final class TaskVerificationOutcomeSelector {

    private TaskVerificationOutcomeSelector() {}

    static TaskVerificationResult select(
            List<String> facts,
            List<String> problems,
            int mutatedTargetCount,
            boolean webCoherenceRequired,
            TaskExpectationStaticVerifier.Result expectationVerification,
            ExactEditReplacementVerifier.Result exactEditVerification,
            SourceDerivedArtifactVerifier.Result sourceDerivedVerification
    ) {
        List<String> safeFacts = facts == null ? List.of() : facts;
        List<String> safeProblems = problems == null ? List.of() : problems;
        TaskExpectationStaticVerifier.Result expectation = expectationVerification == null
                ? TaskExpectationStaticVerifier.Result.empty()
                : expectationVerification;
        ExactEditReplacementVerifier.Result exactEdit = exactEditVerification == null
                ? new ExactEditReplacementVerifier.Result(false, false, false, List.of(), List.of())
                : exactEditVerification;
        SourceDerivedArtifactVerifier.Result sourceDerived = sourceDerivedVerification == null
                ? SourceDerivedArtifactVerifier.Result.notRequired()
                : sourceDerivedVerification;

        if (!safeProblems.isEmpty()) {
            return TaskVerificationResult.failed(
                    sourceDerived.required() && !webCoherenceRequired
                            ? "Source-derived artifact verification failed."
                    : exactEdit.verifiedAny() && exactEdit.hasProblem()
                            ? "Exact edit replacement verification failed."
                    : expectation.replacementRequired() && safeProblems.stream()
                            .anyMatch(TaskVerificationOutcomeSelector::isReplacementProblem)
                            ? "Replacement verification failed."
                    : expectation.appendLineRequired() && safeProblems.stream()
                            .anyMatch(TaskVerificationOutcomeSelector::isAppendLineProblem)
                            ? "Append line verification failed."
                    : expectation.bulletCountRequired() && safeProblems.stream()
                            .anyMatch(TaskVerificationOutcomeSelector::isBulletCountProblem)
                            ? "Bullet count verification failed."
                    : expectation.verifiedAny() && safeProblems.stream()
                            .anyMatch(TaskVerificationOutcomeSelector::isExactContentProblem)
                            ? "Exact content verification failed."
                            : firstProblemSummary(safeProblems),
                    safeFacts,
                    safeProblems);
        }
        if (expectation.verifiedAny() && !webCoherenceRequired) {
            if (expectation.replacementRequired()) {
                return TaskVerificationResult.passed(
                        "Replacement verification passed.",
                        safeFacts);
            }
            if (expectation.appendLineRequired()) {
                return TaskVerificationResult.passed(
                        "Append line verification passed.",
                        safeFacts);
            }
            if (expectation.bulletCountRequired()) {
                return TaskVerificationResult.passed(
                        "Bullet count verification passed.",
                        safeFacts);
            }
            return TaskVerificationResult.passed(
                    "Exact content verification passed.",
                    safeFacts);
        }
        if (exactEdit.coversAllSuccessfulMutations() && !webCoherenceRequired) {
            return TaskVerificationResult.passed(
                    "Exact edit replacement verification passed.",
                    safeFacts);
        }
        if (sourceDerived.required() && !webCoherenceRequired) {
            return TaskVerificationResult.passed(
                    "Source-derived artifact verification passed.",
                    safeFacts);
        }
        if (webCoherenceRequired) {
            if (hasContextualStaticWebFindings(safeFacts)) {
                return TaskVerificationResult.passed(
                        "Scoped static web checks passed for " + mutatedTargetCount
                                + " mutated target(s); contextual static-web findings remain outside this turn.",
                        safeFacts);
            }
            return TaskVerificationResult.passed(
                    "Static web coherence checks passed for " + mutatedTargetCount + " mutated target(s).",
                    safeFacts);
        }
        return TaskVerificationResult.readbackOnly(
                "Target/readback checks passed for " + mutatedTargetCount
                        + " mutated target(s); no task-specific static verifier was applicable.",
                safeFacts);
    }

    private static boolean isExactContentProblem(String problem) {
        return problem != null
                && (problem.contains("exact content mismatch")
                || problem.contains("exact content verification"));
    }

    private static boolean isAppendLineProblem(String problem) {
        return problem != null
                && (problem.contains("appended line")
                || problem.contains("append-only preservation"));
    }

    private static boolean isReplacementProblem(String problem) {
        return problem != null && problem.contains("replacement ");
    }

    private static boolean isBulletCountProblem(String problem) {
        return problem != null && (problem.contains("bullet count") || problem.contains("bullet list"));
    }

    private static String firstProblemSummary(List<String> problems) {
        if (problems == null || problems.isEmpty()) return "Static verification failed.";
        String summary = String.join("; ", problems.subList(0, Math.min(3, problems.size())));
        if (summary.length() > 220) summary = summary.substring(0, 217) + "...";
        return summary;
    }

    private static boolean hasContextualStaticWebFindings(List<String> facts) {
        if (facts == null || facts.isEmpty()) return false;
        return facts.stream().anyMatch(StaticWebProblemScope::isContextualFact);
    }
}
