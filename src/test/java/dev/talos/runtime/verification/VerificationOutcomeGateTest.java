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
    void authoritativeVerifiedRequiredClaimProjectsPassedRequiredVerification() {
        VerificationReport report = VerificationReport.ofClaim(claimResult(
                VerificationVerdict.VERIFIED,
                EvidenceAuthority.AUTHORITATIVE));

        Optional<TaskVerificationResult> override =
                VerificationOutcomeGate.compatibilityOverride(report, List.of("Static coherence passed."));

        assertTrue(override.isPresent());
        assertEquals(TaskVerificationStatus.PASSED, override.get().status());
        assertTrue(override.get().summary().contains("Required verification passed"),
                override.get().summary());
        assertTrue(override.get().requestBound());
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
        assertTrue(override.isPresent());
        assertEquals(TaskVerificationStatus.PASSED, override.get().status());
        assertTrue(override.get().summary().contains("Required verification passed"),
                override.get().summary());
        assertTrue(override.get().requestBound());
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

    @Test
    void aRequiredClaimIsRequestBoundOnlyForChangesItsProofRead() {
        TargetBinding binding = new TargetBinding("#teaser-button", "#teaser-status", "click");
        VerificationClaim claim = new VerificationClaim(
                "static-web-interaction:#teaser-button->#teaser-status",
                "Static interaction #teaser-button -> #teaser-status.",
                ProofKind.STATIC_INTERACTION_GUARD,
                binding,
                true,
                List.of("index.html", "js\\scripts.js"));
        VerificationReport report = VerificationReport.ofClaim(new ClaimResult(
                claim,
                new VerificationObligation(claim, Set.of(ProofKind.STATIC_INTERACTION_GUARD),
                        EvidenceAuthority.AUTHORITATIVE, binding),
                VerificationVerdict.VERIFIED,
                ProofKind.STATIC_INTERACTION_GUARD,
                EvidenceAuthority.AUTHORITATIVE,
                EvidenceCoverage.SCOPED,
                List.of(),
                List.of(),
                List.of()));

        TaskVerificationResult covered = VerificationOutcomeGate.compatibilityOverride(
                report, List.of(), List.of("./index.html", "js/scripts.js")).orElseThrow();
        TaskVerificationResult uncovered = VerificationOutcomeGate.compatibilityOverride(
                report, List.of(), List.of("index.html", "styles.css")).orElseThrow();
        TaskVerificationResult unnamed = VerificationOutcomeGate.compatibilityOverride(
                VerificationReport.ofClaim(claimResult(VerificationVerdict.VERIFIED, EvidenceAuthority.AUTHORITATIVE)),
                List.of(), List.of("index.html")).orElseThrow();

        assertTrue(covered.requestBound(), covered.summary());
        assertEquals(TaskVerificationStatus.PASSED, uncovered.status());
        assertFalse(uncovered.requestBound(), uncovered.summary());
        assertTrue(uncovered.summary().endsWith("Changes outside the required claims were checked only."),
                uncovered.summary());
        assertFalse(unnamed.requestBound(), unnamed.summary());
        // Paths fold case where the file system does, as the static verifier's own path checks do.
        TaskVerificationResult caseVariant = VerificationOutcomeGate.compatibilityOverride(
                report, List.of(), List.of("Index.HTML")).orElseThrow();
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        assertEquals(windows, caseVariant.requestBound(), caseVariant.summary());
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
