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
        TaskExpectationStaticVerifier.Result expectation = expectationVerification == null
                ? TaskExpectationStaticVerifier.Result.empty()
                : expectationVerification;
        TaskVerificationResult selected = selectAsserted(
                facts,
                problems,
                mutatedTargetCount,
                webCoherenceRequired,
                expectation,
                exactEditVerification,
                sourceDerivedVerification,
                verificationReport,
                changedFiles);
        return withNotAssertedReplacements(selected, expectation.notAssertedReplacements());
    }

    /**
     * A replacement that was not asserted is stated with its counts on a result that did not fail, and the result is
     * not request-bound, because part of the request was not verified (P17, D11 option A). A failed result keeps its
     * summary, so its problem selection and repair offer are unchanged. The production caller applies this after the
     * command upgrade, so the statement survives a summary that the upgrade replaces.
     */
    static TaskVerificationResult withNotAssertedReplacements(
            TaskVerificationResult selected,
            List<ReplacementOccurrenceScope.NotAsserted> notAsserted
    ) {
        if (selected == null || notAsserted == null || notAsserted.isEmpty()
                || selected.status() == TaskVerificationStatus.FAILED) {
            return selected;
        }
        StringBuilder summary = new StringBuilder(selected.summary());
        for (ReplacementOccurrenceScope.NotAsserted item : notAsserted) {
            if (!summary.isEmpty()) summary.append(' ');
            summary.append(notAssertedSentence(item));
        }
        return new TaskVerificationResult(
                selected.status(),
                summary.toString(),
                selected.facts(),
                selected.problems(),
                selected.proofScope() == TaskVerificationResult.ProofScope.REQUEST_BOUND
                        ? TaskVerificationResult.ProofScope.CHECKS
                        : selected.proofScope(),
                true,
                selected.exactEditNotAsserted());
    }

    /** Built only from the typed record, so no request prose enters the answer. */
    static String notAssertedSentence(ReplacementOccurrenceScope.NotAsserted item) {
        int remaining = item.remainingOccurrences();
        return "Requested replacement not verified: " + item.pathHint()
                + " held the old text " + item.priorOccurrences() + " times before the change and "
                + (remaining == 0 ? "none remain." : remaining == 1 ? "1 remains." : remaining + " remain.");
    }

    /** The selection without the statement of replacements that were not asserted. */
    static TaskVerificationResult selectAsserted(
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
        TaskExpectationStaticVerifier.Result expectation = expectationVerification == null
                ? TaskExpectationStaticVerifier.Result.empty()
                : expectationVerification;
        List<String> safeFacts = facts == null ? List.of() : facts;
        List<String> safeProblems = problems == null ? List.of() : problems;
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
        // A replacement verifier that applied but asserted nothing is stated by the caller, and an exact edit that a
        // later change of its target replaced is not asserted (P19), so in both cases the summary does not claim that
        // no verifier applied.
        String readbackSummary = expectation.notAssertedReplacements().isEmpty() && !exactEdit.leftEditUnasserted()
                ? "Target/readback checks passed for " + mutatedTargetCount
                        + " mutated target(s); no task-specific static verifier was applicable."
                : "Target/readback checks passed for " + mutatedTargetCount + " mutated target(s).";
        return new TaskVerificationResult(
                TaskVerificationStatus.READBACK_ONLY,
                readbackSummary,
                safeFacts,
                List.of(),
                TaskVerificationResult.ProofScope.NONE,
                false,
                exactEdit.leftEditUnasserted());
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
