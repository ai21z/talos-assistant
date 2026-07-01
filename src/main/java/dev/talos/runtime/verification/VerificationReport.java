package dev.talos.runtime.verification;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

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

    public static VerificationReport merge(VerificationReport first, VerificationReport second) {
        if ((first == null || first == empty()) && (second == null || second == empty())) return empty();
        List<ClaimResult> claims = new ArrayList<>();
        List<VerifierResult> verifiers = new ArrayList<>();
        List<String> facts = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> limitations = new ArrayList<>();
        append(claims, verifiers, facts, problems, limitations, first);
        append(claims, verifiers, facts, problems, limitations, second);
        return new VerificationReport(claims, verifiers, facts, problems, limitations);
    }

    public boolean hasRequiredClaims() {
        return claimResults.stream().anyMatch(ClaimResult::required);
    }

    public int requiredClaimCount() {
        return requiredClaimGroups().size();
    }

    public int unsatisfiedRequiredClaimCount() {
        return (int) requiredClaimGroups().values().stream()
                .map(VerificationReport::controllingResults)
                .filter(results -> results.stream().noneMatch(ClaimResult::satisfied))
                .count();
    }

    public List<String> authoritativeProofKinds() {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        claimResults.stream()
                .filter(result -> result.authority() == EvidenceAuthority.AUTHORITATIVE)
                .filter(result -> result.verdict() == VerificationVerdict.VERIFIED)
                .map(result -> result.proofKind().name())
                .forEach(out::add);
        verifierResults.stream()
                .filter(result -> result.authority() == EvidenceAuthority.AUTHORITATIVE)
                .filter(result -> result.verdict() == VerificationVerdict.VERIFIED)
                .map(result -> result.proofKind().name())
                .forEach(out::add);
        return List.copyOf(out);
    }

    public List<String> unsatisfiedRequiredDetails() {
        List<String> out = new ArrayList<>();
        requiredClaimGroups().values().stream()
                .map(VerificationReport::controllingResults)
                .filter(results -> results.stream().noneMatch(ClaimResult::satisfied))
                .flatMap(List::stream)
                .forEach(result -> {
                    out.addAll(result.problems());
                    out.addAll(result.limitations());
                });
        return List.copyOf(out);
    }

    public boolean requiredClaimsSatisfied() {
        return hasRequiredClaims()
                && requiredClaimGroups().values().stream()
                .map(VerificationReport::controllingResults)
                .allMatch(results -> results.stream().anyMatch(ClaimResult::satisfied));
    }

    public boolean hasRequiredFailure() {
        return requiredClaimGroups().values().stream()
                .map(VerificationReport::controllingResults)
                .filter(results -> results.stream().noneMatch(ClaimResult::satisfied))
                .flatMap(List::stream)
                .anyMatch(result -> result.verdict() == VerificationVerdict.FAILED);
    }

    public boolean hasRequiredUnavailable() {
        return requiredClaimGroups().values().stream()
                .map(VerificationReport::controllingResults)
                .filter(results -> results.stream().noneMatch(ClaimResult::satisfied))
                .flatMap(List::stream)
                .anyMatch(result -> result.verdict() == VerificationVerdict.UNAVAILABLE);
    }

    public boolean hasRequiredUnsupported() {
        return requiredClaimGroups().values().stream()
                .map(VerificationReport::controllingResults)
                .filter(results -> results.stream().noneMatch(ClaimResult::satisfied))
                .flatMap(List::stream)
                .anyMatch(result -> result.verdict() == VerificationVerdict.UNSUPPORTED);
    }

    private Map<String, List<ClaimResult>> requiredClaimGroups() {
        LinkedHashMap<String, List<ClaimResult>> out = new LinkedHashMap<>();
        for (ClaimResult result : claimResults) {
            if (result == null || !result.required()) continue;
            out.computeIfAbsent(claimKey(result), ignored -> new ArrayList<>()).add(result);
        }
        return out;
    }

    private static String claimKey(ClaimResult result) {
        VerificationClaim claim = result.claim();
        if (claim == null) return "";
        if (!claim.id().isBlank()) return claim.id();
        TargetBinding binding = claim.binding();
        if (binding != null) {
            return binding.eventType() + ":" + binding.triggerSelector() + "->" + binding.outputSelector();
        }
        return claim.description();
    }

    private static List<ClaimResult> controllingResults(List<ClaimResult> results) {
        if (results == null || results.isEmpty()) return List.of();
        List<ClaimResult> browserResults = results.stream()
                .filter(result -> result.proofKind() == ProofKind.BROWSER_BEHAVIOR)
                .toList();
        return browserResults.isEmpty() ? results : browserResults;
    }

    private static void append(
            List<ClaimResult> claims,
            List<VerifierResult> verifiers,
            List<String> facts,
            List<String> problems,
            List<String> limitations,
            VerificationReport report
    ) {
        if (report == null) return;
        claims.addAll(report.claimResults());
        verifiers.addAll(report.verifierResults());
        facts.addAll(report.facts());
        problems.addAll(report.problems());
        limitations.addAll(report.limitations());
    }
}
