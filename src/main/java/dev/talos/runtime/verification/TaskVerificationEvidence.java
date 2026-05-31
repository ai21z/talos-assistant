package dev.talos.runtime.verification;

import java.util.List;

/**
 * First-class verification evidence plus the legacy compatibility projection.
 *
 * <p>The compatibility result remains the existing status surface. The rich
 * report carries claim-scoped verifier evidence and must stay authoritative
 * only when it came from a real post-apply verifier.
 */
public record TaskVerificationEvidence(
        TaskVerificationResult compatibilityResult,
        VerificationReport report,
        TaskVerificationEvidenceSource source
) {
    public TaskVerificationEvidence {
        compatibilityResult = compatibilityResult == null
                ? TaskVerificationResult.notRun("Verification was not run.")
                : compatibilityResult;
        report = report == null ? VerificationReport.empty() : report;
        source = source == null ? TaskVerificationEvidenceSource.NOT_RUN : source;
    }

    public static TaskVerificationEvidence notRun(String summary) {
        return new TaskVerificationEvidence(
                TaskVerificationResult.notRun(summary),
                VerificationReport.empty(),
                TaskVerificationEvidenceSource.NOT_RUN);
    }

    public static TaskVerificationEvidence postApply(
            TaskVerificationResult compatibilityResult,
            VerificationReport report
    ) {
        return new TaskVerificationEvidence(
                compatibilityResult,
                report,
                TaskVerificationEvidenceSource.POST_APPLY_STATIC);
    }

    public static TaskVerificationEvidence embeddedAssistant(TaskVerificationResult compatibilityResult) {
        if (compatibilityResult == null || compatibilityResult.status() == TaskVerificationStatus.NOT_RUN) {
            return notRun(compatibilityResult == null
                    ? "Post-apply verification was not applicable."
                    : compatibilityResult.summary());
        }
        return new TaskVerificationEvidence(
                compatibilityResult,
                embeddedAssistantReport(compatibilityResult),
                TaskVerificationEvidenceSource.EMBEDDED_ASSISTANT_TEXT);
    }

    private static VerificationReport embeddedAssistantReport(TaskVerificationResult result) {
        return new VerificationReport(
                List.of(),
                List.of(new VerifierResult(
                        null,
                        ProofKind.LLM_ADVISORY,
                        EvidenceAuthority.ADVISORY,
                        EvidenceCoverage.BEST_EFFORT,
                        result.status() == TaskVerificationStatus.FAILED
                                ? VerificationVerdict.FAILED
                                : VerificationVerdict.UNVERIFIED,
                        List.of(),
                        result.problems(),
                        List.of("Embedded assistant-authored verification text is advisory/negative-only "
                                + "and does not provide authoritative verifier proof."))),
                List.of(),
                List.of(),
                List.of("Embedded assistant-authored verification text is advisory/negative-only "
                        + "and does not provide authoritative verifier proof."));
    }
}
