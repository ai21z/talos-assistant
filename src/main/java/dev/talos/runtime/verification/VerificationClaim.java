package dev.talos.runtime.verification;

public record VerificationClaim(
        String id,
        String description,
        ProofKind proofKind,
        TargetBinding binding,
        boolean required
) {
    public VerificationClaim {
        id = id == null ? "" : id.strip();
        description = description == null ? "" : description.strip();
        proofKind = proofKind == null ? ProofKind.READBACK : proofKind;
    }
}
