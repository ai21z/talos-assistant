package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class VerificationOutcomeGate {
    private VerificationOutcomeGate() {}

    static Optional<TaskVerificationResult> compatibilityOverride(
            VerificationReport report,
            List<String> baseFacts
    ) {
        if (report == null || !report.hasRequiredClaims()) return Optional.empty();
        List<String> facts = merged(baseFacts, report.facts(), report.limitations());
        if (report.hasRequiredFailure()) {
            return Optional.of(TaskVerificationResult.failed(
                    requiredSummary(report, "Required interaction verification failed."),
                    facts,
                    report.problems().isEmpty() ? report.limitations() : report.problems()));
        }
        if (report.hasRequiredUnavailable()) {
            return Optional.of(TaskVerificationResult.unavailable(
                    requiredSummary(report, "Required verification was unavailable."),
                    facts,
                    report.limitations()));
        }
        if (!report.requiredClaimsSatisfied()) {
            return Optional.of(TaskVerificationResult.readbackOnly(
                    requiredSummary(report, "Required interaction verification was not satisfied."),
                    facts));
        }
        return Optional.empty();
    }

    private static String requiredSummary(VerificationReport report, String fallback) {
        if (report == null) return fallback;
        return report.claimResults().stream()
                .filter(ClaimResult::required)
                .findFirst()
                .map(result -> result.claim() == null || result.claim().description().isBlank()
                        ? fallback
                        : result.claim().description() + " " + fallback)
                .orElse(fallback);
    }

    private static List<String> merged(List<String> first, List<String> second, List<String> third) {
        List<String> out = new ArrayList<>();
        if (first != null) out.addAll(first);
        if (second != null) out.addAll(second);
        if (third != null) out.addAll(third);
        return List.copyOf(out);
    }
}
