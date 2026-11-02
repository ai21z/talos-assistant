package dev.talos.runtime.verification;

import java.util.Collection;
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
        return select(
                facts,
                problems,
                mutatedTargetCount,
                webCoherenceRequired,
                expectationVerification,
                exactEditVerification,
                sourceDerivedVerification,
                VerificationReport.empty(),
                List.of());
    }

    /**
     * Request-derived expectations and required claims give a request-bound pass. Exact edit replacement compares the
     * file with the edit tool's own strings and static web coherence checks structure, so both give a checks pass
     * (MICA invariant I3, decision D5).
     */
    static TaskVerificationResult select(
            List<String> facts,
            List<String> problems,
            int mutatedTargetCount,
            boolean webCoherenceRequired,
            TaskExpectationStaticVerifier.Result expectationVerification,
            ExactEditReplacementVerifier.Result exactEditVerification,
            SourceDerivedArtifactVerifier.Result sourceDerivedVerification,
            VerificationReport verificationReport,
            Collection<String> changedFiles
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
                    : expectation.appendSectionRequired() && safeProblems.stream()
                            .anyMatch(TaskVerificationOutcomeSelector::isAppendSectionProblem)
                            ? "Append section verification failed."
                    : expectation.appendStatementRequired() && safeProblems.stream()
                            .anyMatch(TaskVerificationOutcomeSelector::isAppendStatementProblem)
                            ? "Append statement verification failed."
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
        java.util.Optional<TaskVerificationResult> claimOverride =
                VerificationOutcomeGate.compatibilityOverride(verificationReport, safeFacts, changedFiles);
        if (claimOverride.isPresent()) {
            return claimOverride.get();
        }
        if (expectation.verifiedAny() && expectation.coversAllSuccessfulMutations() && !webCoherenceRequired) {
            if (expectation.replacementRequired()) {
                return TaskVerificationResult.passedForRequest(
                        "Replacement verification passed.",
                        safeFacts);
            }
            if (expectation.appendLineRequired()) {
                return TaskVerificationResult.passedForRequest(
                        "Append line verification passed.",
                        safeFacts);
            }
            if (expectation.appendSectionRequired()) {
                return TaskVerificationResult.passedForRequest(
                        "Append section verification passed.",
                        safeFacts);
            }
            if (expectation.appendStatementRequired()) {
                return TaskVerificationResult.passedForRequest(
                        "Append statement verification passed.",
                        safeFacts);
            }
            if (expectation.bulletCountRequired()) {
                return TaskVerificationResult.passedForRequest(
                        "Bullet count verification passed.",
                        safeFacts);
            }
            return TaskVerificationResult.passedForRequest(
                    "Exact content verification passed.",
                    safeFacts);
        }
        if (exactEdit.coversAllSuccessfulMutations() && !webCoherenceRequired) {
            return TaskVerificationResult.passed(
                    "Exact edit replacement checks passed.",
                    safeFacts);
        }
        if (sourceDerived.required() && !webCoherenceRequired) {
            return TaskVerificationResult.readbackOnly(
                    "Source-derived coverage checks passed, but required summary verification was not satisfied; "
                            + "summary semantics were not fully verified.",
                    safeFacts);
        }
        if (webCoherenceRequired) {
            // Coherence alone checks structure. A request-derived expectation that also covered every change keeps
            // the pass request-bound.
            boolean requestBound = expectation.verifiedAny() && expectation.coversAllSuccessfulMutations();
            String summary = hasContextualStaticWebFindings(safeFacts)
                    ? "Scoped static web checks passed for " + mutatedTargetCount
                            + " mutated target(s); contextual static-web findings remain outside this turn."
                    : "Static web coherence checks passed for " + mutatedTargetCount + " mutated target(s).";
            return requestBound
                    ? TaskVerificationResult.passedForRequest(summary, safeFacts)
                    : TaskVerificationResult.passed(summary, safeFacts);
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

    private static boolean isAppendSectionProblem(String problem) {
        return problem != null
                && (problem.contains("append-section")
                || problem.contains("append section")
                || problem.contains("requested Markdown section")
                || problem.contains("requested section"));
    }

    private static boolean isAppendStatementProblem(String problem) {
        return problem != null
                && (problem.contains("append-statement")
                || problem.contains("append statement")
                || problem.contains("appended statement"));
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
