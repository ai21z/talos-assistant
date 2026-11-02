package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

final class VerificationOutcomeGate {
    private static final boolean CASE_INSENSITIVE_PATHS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private VerificationOutcomeGate() {}

    /** A turn without changed files, such as a verify-only turn, has no change outside the claims' proof. */
    static Optional<TaskVerificationResult> compatibilityOverride(
            VerificationReport report,
            List<String> baseFacts
    ) {
        return compatibilityOverride(report, baseFacts, List.of());
    }

    static Optional<TaskVerificationResult> compatibilityOverride(
            VerificationReport report,
            List<String> baseFacts,
            Collection<String> changedFiles
    ) {
        if (report == null || !report.hasRequiredClaims()) return Optional.empty();
        List<String> facts = merged(baseFacts, report.facts(), report.limitations());
        if (report.hasRequiredFailure()) {
            return Optional.of(TaskVerificationResult.failed(
                    requiredSummary(report, "Required verification failed."),
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
                    requiredSummary(report, "Required verification was not satisfied."),
                    facts));
        }
        // Required claims come from the request, so their pass is request-bound when their proof read every changed
        // file. A change outside that proof was checked only, as with an expectation that does not cover a mutation.
        if (!claimsCover(report, changedFiles)) {
            return Optional.of(TaskVerificationResult.passed(
                    requiredSummary(report, "Required verification passed.")
                            + " Changes outside the required claims were checked only.",
                    facts));
        }
        return Optional.of(TaskVerificationResult.passedForRequest(
                requiredSummary(report, "Required verification passed."),
                facts));
    }

    private static boolean claimsCover(VerificationReport report, Collection<String> changedFiles) {
        if (changedFiles == null || changedFiles.isEmpty()) return true;
        Set<String> evaluated = new HashSet<>();
        for (ClaimResult result : report.claimResults()) {
            if (!result.required() || result.claim() == null) continue;
            result.claim().evaluatedFiles().forEach(file -> evaluated.add(normalized(file)));
        }
        for (String changed : changedFiles) {
            if (!evaluated.contains(normalized(changed))) return false;
        }
        return true;
    }

    /** Matches StaticTaskVerifier path checks, which fold case on Windows. */
    private static String normalized(String path) {
        String out = path == null ? "" : path.strip().replace('\\', '/');
        while (out.startsWith("./")) out = out.substring(2);
        return CASE_INSENSITIVE_PATHS ? out.toLowerCase(Locale.ROOT) : out;
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
