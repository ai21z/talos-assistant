package dev.talos.runtime.verification;

import java.util.List;

/**
 * A verification claim. {@code evaluatedFiles} names the workspace files the claim's proof read, so a request-bound
 * pass can be limited to changes the proof covered (MICA invariant I3, decision D5). An empty list covers no file.
 */
public record VerificationClaim(
        String id,
        String description,
        ProofKind proofKind,
        TargetBinding binding,
        boolean required,
        List<String> evaluatedFiles
) {
    public VerificationClaim {
        id = id == null ? "" : id.strip();
        description = description == null ? "" : description.strip();
        proofKind = proofKind == null ? ProofKind.READBACK : proofKind;
        evaluatedFiles = evaluatedFiles == null ? List.of() : evaluatedFiles.stream()
                .filter(file -> file != null && !file.isBlank())
                .map(String::strip)
                .distinct()
                .toList();
    }

    public VerificationClaim(String id, String description, ProofKind proofKind, TargetBinding binding,
                             boolean required) {
        this(id, description, proofKind, binding, required, List.of());
    }
}
