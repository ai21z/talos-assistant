package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerificationOutcomeGateTest {

    @Test
    void authoritativeVerifiedRequiredClaimAllowsExistingPassProjectionToStand() {
        VerificationReport report = VerificationReport.ofClaim(claimResult(
                VerificationVerdict.VERIFIED,
                EvidenceAuthority.AUTHORITATIVE));

        Optional<TaskVerificationResult> override =
                VerificationOutcomeGate.compatibilityOverride(report, List.of("Static coherence passed."));

        assertTrue(override.isEmpty());
    }

    @Test
    void advisoryEvidenceCannotSatisfyRequiredClaim() {
        VerificationReport report = VerificationReport.ofClaim(claimResult(
                VerificationVerdict.VERIFIED,
                EvidenceAuthority.ADVISORY));

        Optional<TaskVerificationResult> override =
                VerificationOutcomeGate.compatibilityOverride(report, List.of("Static coherence passed."));

        assertTrue(override.isPresent());
        assertEquals(TaskVerificationStatus.READBACK_ONLY, override.get().status());
    }

    @Test
    void failedRequiredClaimProjectsFailedCompatibilityStatus() {
        VerificationReport report = VerificationReport.ofClaim(claimResult(
                VerificationVerdict.FAILED,
                EvidenceAuthority.AUTHORITATIVE));

        Optional<TaskVerificationResult> override =
                VerificationOutcomeGate.compatibilityOverride(report, List.of("Static coherence passed."));

        assertTrue(override.isPresent());
        assertEquals(TaskVerificationStatus.FAILED, override.get().status());
    }

    private static ClaimResult claimResult(VerificationVerdict verdict, EvidenceAuthority authority) {
        TargetBinding binding = new TargetBinding("#teaser-button", "#teaser-status", "click");
        VerificationClaim claim = new VerificationClaim(
                "static-web-interaction:#teaser-button->#teaser-status",
                "Static interaction #teaser-button -> #teaser-status.",
                ProofKind.STATIC_INTERACTION_GUARD,
                binding,
                true);
        VerificationObligation obligation = new VerificationObligation(
                claim,
                Set.of(ProofKind.STATIC_INTERACTION_GUARD),
                EvidenceAuthority.AUTHORITATIVE,
                binding);
        return new ClaimResult(
                claim,
                obligation,
                verdict,
                ProofKind.STATIC_INTERACTION_GUARD,
                authority,
                EvidenceCoverage.SCOPED,
                List.of(),
                verdict == VerificationVerdict.FAILED ? List.of("wrong target") : List.of(),
                List.of());
    }
}
