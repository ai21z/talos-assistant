package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

    @Test
    void browserBehaviorCanSatisfySameRequiredClaimEvenWhenStaticGuardIsUnverified() {
        VerificationReport report = new VerificationReport(
                List.of(
                        claimResult(
                                VerificationVerdict.UNVERIFIED,
                                EvidenceAuthority.AUTHORITATIVE,
                                ProofKind.STATIC_INTERACTION_GUARD),
                        claimResult(
                                VerificationVerdict.VERIFIED,
                                EvidenceAuthority.AUTHORITATIVE,
                                ProofKind.BROWSER_BEHAVIOR)),
                List.of(new VerifierResult(
                        null,
                        ProofKind.LLM_ADVISORY,
                        EvidenceAuthority.ADVISORY,
                        EvidenceCoverage.BEST_EFFORT,
                        VerificationVerdict.VERIFIED,
                        List.of("advisory"),
                        List.of(),
                        List.of())),
                List.of(),
                List.of(),
                List.of("Static guard could not prove behavior, but browser assertion passed."));

        Optional<TaskVerificationResult> override =
                VerificationOutcomeGate.compatibilityOverride(report, List.of("Static coherence passed."));

        assertTrue(report.requiredClaimsSatisfied());
        assertEquals(1, report.requiredClaimCount());
        assertEquals(0, report.unsatisfiedRequiredClaimCount());
        assertTrue(override.isEmpty());
    }

    @Test
    void browserBehaviorUnavailableControlsSameClaimEvenWhenStaticGuardPassed() {
        VerificationReport report = new VerificationReport(
                List.of(
                        claimResult(
                                VerificationVerdict.VERIFIED,
                                EvidenceAuthority.AUTHORITATIVE,
                                ProofKind.STATIC_INTERACTION_GUARD),
                        claimResult(
                                VerificationVerdict.UNAVAILABLE,
                                EvidenceAuthority.AUTHORITATIVE,
                                ProofKind.BROWSER_BEHAVIOR)),
                List.of(),
                List.of(),
                List.of(),
                List.of("browser runner unavailable"));

        Optional<TaskVerificationResult> override =
                VerificationOutcomeGate.compatibilityOverride(report, List.of("Static coherence passed."));

        assertFalse(report.requiredClaimsSatisfied());
        assertEquals(1, report.unsatisfiedRequiredClaimCount());
        assertTrue(override.isPresent());
        assertEquals(TaskVerificationStatus.UNAVAILABLE, override.get().status());
    }

    private static ClaimResult claimResult(VerificationVerdict verdict, EvidenceAuthority authority) {
        return claimResult(verdict, authority, ProofKind.STATIC_INTERACTION_GUARD);
    }

    private static ClaimResult claimResult(
            VerificationVerdict verdict,
            EvidenceAuthority authority,
            ProofKind proofKind
    ) {
        TargetBinding binding = new TargetBinding("#teaser-button", "#teaser-status", "click");
        VerificationClaim claim = new VerificationClaim(
                "static-web-interaction:#teaser-button->#teaser-status",
                "Static interaction #teaser-button -> #teaser-status.",
                proofKind,
                binding,
                true);
        VerificationObligation obligation = new VerificationObligation(
                claim,
                Set.of(ProofKind.STATIC_INTERACTION_GUARD, ProofKind.BROWSER_BEHAVIOR),
                EvidenceAuthority.AUTHORITATIVE,
                binding);
        return new ClaimResult(
                claim,
                obligation,
                verdict,
                proofKind,
                authority,
                EvidenceCoverage.SCOPED,
                List.of(),
                verdict == VerificationVerdict.FAILED ? List.of("wrong target") : List.of(),
                List.of());
    }
}
