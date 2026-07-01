package dev.talos.runtime.verification;

import java.util.List;

public record VerifierResult(
        VerificationClaim claim,
        ProofKind proofKind,
        EvidenceAuthority authority,
        EvidenceCoverage coverage,
        VerificationVerdict verdict,
        List<String> facts,
        List<String> problems,
        List<String> limitations
) {
    public VerifierResult {
        proofKind = proofKind == null ? ProofKind.READBACK : proofKind;
        authority = authority == null ? EvidenceAuthority.SUPPLEMENTAL : authority;
        coverage = coverage == null ? EvidenceCoverage.BEST_EFFORT : coverage;
        verdict = verdict == null ? VerificationVerdict.NOT_RUN : verdict;
        facts = facts == null ? List.of() : List.copyOf(facts);
        problems = problems == null ? List.of() : List.copyOf(problems);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }
}
