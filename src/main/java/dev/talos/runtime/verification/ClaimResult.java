package dev.talos.runtime.verification;

import java.util.List;

public record ClaimResult(
        VerificationClaim claim,
        VerificationObligation obligation,
        VerificationVerdict verdict,
        ProofKind proofKind,
        EvidenceAuthority authority,
        EvidenceCoverage coverage,
        List<String> facts,
        List<String> problems,
        List<String> limitations
) {
    public ClaimResult {
        verdict = verdict == null ? VerificationVerdict.NOT_RUN : verdict;
        proofKind = proofKind == null ? ProofKind.READBACK : proofKind;
        authority = authority == null ? EvidenceAuthority.SUPPLEMENTAL : authority;
        coverage = coverage == null ? EvidenceCoverage.BEST_EFFORT : coverage;
        facts = facts == null ? List.of() : List.copyOf(facts);
        problems = problems == null ? List.of() : List.copyOf(problems);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public boolean required() {
        return claim != null && claim.required();
    }

    public boolean satisfied() {
        return verdict == VerificationVerdict.VERIFIED && authority == EvidenceAuthority.AUTHORITATIVE;
    }
}
