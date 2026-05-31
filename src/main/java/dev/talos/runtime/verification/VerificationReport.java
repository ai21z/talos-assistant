package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.List;

public record VerificationReport(
        List<ClaimResult> claimResults,
        List<VerifierResult> verifierResults,
        List<String> facts,
        List<String> problems,
        List<String> limitations
) {
    private static final VerificationReport EMPTY = new VerificationReport(
            List.of(), List.of(), List.of(), List.of(), List.of());

    public VerificationReport {
        claimResults = claimResults == null ? List.of() : List.copyOf(claimResults);
        verifierResults = verifierResults == null ? List.of() : List.copyOf(verifierResults);
        facts = facts == null ? List.of() : List.copyOf(facts);
        problems = problems == null ? List.of() : List.copyOf(problems);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public static VerificationReport empty() {
        return EMPTY;
    }

    public static VerificationReport ofClaim(ClaimResult result) {
        if (result == null) return empty();
        List<String> facts = new ArrayList<>(result.facts());
        List<String> problems = new ArrayList<>(result.problems());
        List<String> limitations = new ArrayList<>(result.limitations());
        return new VerificationReport(List.of(result), List.of(), facts, problems, limitations);
    }

    public boolean hasRequiredClaims() {
        return claimResults.stream().anyMatch(ClaimResult::required);
    }

    public boolean requiredClaimsSatisfied() {
        return hasRequiredClaims()
                && claimResults.stream()
                .filter(ClaimResult::required)
                .allMatch(ClaimResult::satisfied);
    }

    public boolean hasRequiredFailure() {
        return claimResults.stream()
                .filter(ClaimResult::required)
                .anyMatch(result -> result.verdict() == VerificationVerdict.FAILED);
    }

    public boolean hasRequiredUnavailable() {
        return claimResults.stream()
                .filter(ClaimResult::required)
                .anyMatch(result -> result.verdict() == VerificationVerdict.UNAVAILABLE);
    }

    public boolean hasRequiredUnsupported() {
        return claimResults.stream()
                .filter(ClaimResult::required)
                .anyMatch(result -> result.verdict() == VerificationVerdict.UNSUPPORTED);
    }
}
