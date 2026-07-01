package dev.talos.runtime.verification;

import java.util.Set;

public record VerificationObligation(
        VerificationClaim claim,
        Set<ProofKind> acceptableProofKinds,
        EvidenceAuthority requiredAuthority,
        TargetBinding binding
) {
    public VerificationObligation {
        acceptableProofKinds = acceptableProofKinds == null
                ? Set.of()
                : Set.copyOf(acceptableProofKinds);
        requiredAuthority = requiredAuthority == null ? EvidenceAuthority.AUTHORITATIVE : requiredAuthority;
    }
}
