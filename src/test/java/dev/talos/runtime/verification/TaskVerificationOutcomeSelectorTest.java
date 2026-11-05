package dev.talos.runtime.verification;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TaskVerificationOutcomeSelectorTest {

    @Test
    void replacementExpectationFailureKeepsExistingSummaryPrecedence() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("readback fact"),
                List.of("notes.md: replacement text was not observed."),
                1,
                false,
                expectationResult(true, true, false, false),
                exactEditResult(false, false, false),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("Replacement verification failed.", result.summary());
        assertEquals(List.of("readback fact"), result.facts());
        assertEquals(List.of("notes.md: replacement text was not observed."), result.problems());
    }

    @Test
    void sourceDerivedFailureWinsOnlyWhenStaticWebCoherenceIsNotRequired() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("source fact"),
                List.of("summary.md: source-derived target is empty after apply."),
                1,
                false,
                expectationResult(false, false, false, false),
                exactEditResult(false, false, false),
                sourceDerivedResult(true));

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("Source-derived artifact verification failed.", result.summary());
    }

    @Test
    void exactEditPassWinsForNonWebWhenEverySuccessfulMutationHasExactEditEvidence() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("notes.md: exact edit replacement observed in post-apply file."),
                List.of(),
                1,
                false,
                expectationResult(false, false, false, false),
                exactEditResult(true, true, false),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertEquals("Exact edit replacement checks passed.", result.summary());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, result.proofScope());
    }

    @Test
    void aRequestExpectationPassIsRequestBoundAndCoherenceAloneIsNot() {
        TaskVerificationResult replacement = TaskVerificationOutcomeSelector.select(
                List.of("notes.md: replacement text observed and old text absent."), List.of(), 1, false,
                expectationResult(true, true, false, false), exactEditResult(false, false, false),
                sourceDerivedResult(false));
        TaskVerificationResult coherence = TaskVerificationOutcomeSelector.select(
                List.of("HTML/CSS/JS selector coherence passed."), List.of(), 1, true,
                expectationResult(false, false, false, false), exactEditResult(true, true, false),
                sourceDerivedResult(false));
        // A replacement expectation on a web file is the request-derived expectation production pairs with
        // coherence. Exact content turns web coherence off.
        TaskVerificationResult coherenceWithExpectation = TaskVerificationOutcomeSelector.select(
                List.of("index.html: replacement text observed and old text absent."), List.of(), 1, true,
                expectationResult(true, true, false, false), exactEditResult(false, false, false),
                sourceDerivedResult(false));
        TaskVerificationResult partialExpectation = TaskVerificationOutcomeSelector.select(
                List.of("index.html: exact content observed."), List.of(), 2, true,
                expectationResult(true, false, false, false, false), exactEditResult(false, false, false),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationResult.ProofScope.REQUEST_BOUND, replacement.proofScope());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, coherence.proofScope());
        assertEquals(TaskVerificationResult.ProofScope.REQUEST_BOUND, coherenceWithExpectation.proofScope());
        assertEquals("Static web coherence checks passed for 1 mutated target(s).", coherenceWithExpectation.summary());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, partialExpectation.proofScope());
    }

    @Test
    void onlyAPassCarriesAProofScopeAndAnUnstatedScopeIsNotRequestBound() {
        assertEquals(TaskVerificationResult.ProofScope.CHECKS,
                new TaskVerificationResult(TaskVerificationStatus.PASSED, "pass", List.of(), List.of()).proofScope());
        assertEquals(TaskVerificationResult.ProofScope.NONE, new TaskVerificationResult(
                TaskVerificationStatus.FAILED, "fail", List.of(), List.of("x"),
                TaskVerificationResult.ProofScope.REQUEST_BOUND).proofScope());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, new TaskVerificationResult(
                TaskVerificationStatus.PASSED, "pass", List.of(), List.of(), null).proofScope());
    }

    @Test
    void expectationPassDoesNotCoverUnverifiedSuccessfulMutationTarget() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("notes.md: replacement text observed and old text absent."),
                List.of(),
                2,
                false,
                expectationResult(true, true, false, false, false),
                exactEditResult(true, false, false),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.summary().contains("Target/readback checks passed"), result.summary());
    }

    @Test
    void sourceDerivedPositiveCoverageDoesNotProjectToPassedForGenericSummary() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("summary.md: source-derived artifact includes evidence from notes.md."),
                List.of(),
                1,
                false,
                expectationResult(false, false, false, false),
                exactEditResult(false, false, false),
                sourceDerivedResult(true));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.summary().contains("Source-derived coverage checks passed"), result.summary());
        assertTrue(result.summary().contains("summary semantics were not fully verified"), result.summary());
    }

    @Test
    void webCoherencePassPreservesMutatedTargetCountSummary() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("HTML/CSS/JS selector coherence passed."),
                List.of(),
                3,
                true,
                expectationResult(false, false, false, false),
                exactEditResult(true, true, false),
                sourceDerivedResult(true));

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertEquals("Static web coherence checks passed for 3 mutated target(s).", result.summary());
    }

    @Test
    void readbackOnlyFallbackPreservesExistingSummary() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("README.md: readable after mutation."),
                List.of(),
                2,
                false,
                expectationResult(false, false, false, false),
                exactEditResult(false, false, false),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, result.status());
        assertTrue(result.summary().contains("Target/readback checks passed for 2 mutated target(s)"));
        assertTrue(result.summary().contains("no task-specific static verifier was applicable"));
    }

    @Test
    void aReplacedExactEditGivesAReadbackSummaryThatDoesNotClaimNoVerifierApplied() {
        // P19, D13 option A: the exact edit verifier applied and left an edit unasserted, so the readback summary does
        // not claim that no task-specific verifier was applicable. An edit that is only uncovered keeps the clause.
        List<String> facts = List.of(
                "notes.md: exact edit replacement observed before a later change of the same file altered it.",
                "notes.md: exact edit replacement observed in post-apply file.");
        TaskVerificationResult replaced = TaskVerificationOutcomeSelector.select(
                facts, List.of(), 1, false,
                expectationResult(false, false, false, false),
                new ExactEditReplacementVerifier.Result(true, false, false, facts, List.of(), List.of(
                        ExactEditReplacementVerifier.Standing.REPLACED_LATER,
                        ExactEditReplacementVerifier.Standing.KEPT)),
                sourceDerivedResult(false));
        TaskVerificationResult unobserved = TaskVerificationOutcomeSelector.select(
                List.of(), List.of(), 1, false,
                expectationResult(false, false, false, false),
                new ExactEditReplacementVerifier.Result(true, false, false, List.of(), List.of(), List.of(
                        ExactEditReplacementVerifier.Standing.UNOBSERVED)),
                sourceDerivedResult(false));
        TaskVerificationResult uncovered = TaskVerificationOutcomeSelector.select(
                List.of(), List.of(), 2, false,
                expectationResult(false, false, false, false),
                new ExactEditReplacementVerifier.Result(true, false, false, List.of(), List.of(), List.of(
                        ExactEditReplacementVerifier.Standing.KEPT)),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.READBACK_ONLY, replaced.status());
        assertEquals("Target/readback checks passed for 1 mutated target(s).", replaced.summary());
        assertEquals(facts, replaced.facts());
        assertTrue(replaced.exactEditNotAsserted());
        assertEquals("Target/readback checks passed for 1 mutated target(s).", unobserved.summary());
        assertTrue(unobserved.exactEditNotAsserted());
        assertEquals(TaskVerificationStatus.READBACK_ONLY, uncovered.status());
        assertEquals(false, uncovered.exactEditNotAsserted());
        assertEquals("Target/readback checks passed for 2 mutated target(s); no task-specific static verifier was "
                + "applicable.", uncovered.summary());
    }

    @Test
    void aReplacedExactEditOnAWebTurnLeavesTheCoherenceChecksPass() {
        // Review finding F1: a replaced edit no longer fails the turn, so the verifier that owns the turn decides it.
        // On a web turn that is the static web coherence checks pass, which is not request-bound.
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("HTML/CSS/JS selector coherence passed."), List.of(), 1, true,
                expectationResult(false, false, false, false),
                new ExactEditReplacementVerifier.Result(true, false, false, List.of(), List.of(), List.of(
                        ExactEditReplacementVerifier.Standing.REPLACED_LATER,
                        ExactEditReplacementVerifier.Standing.KEPT)),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.PASSED, result.status());
        assertEquals(TaskVerificationResult.ProofScope.CHECKS, result.proofScope());
        assertEquals("Static web coherence checks passed for 1 mutated target(s).", result.summary());
    }

    @Test
    void genericFailureFallbackPreservesFirstThreeProblemSummary() {
        TaskVerificationResult result = TaskVerificationOutcomeSelector.select(
                List.of("readback fact"),
                List.of("first problem", "second problem", "third problem", "fourth problem"),
                1,
                false,
                expectationResult(false, false, false, false),
                exactEditResult(false, false, false),
                sourceDerivedResult(false));

        assertEquals(TaskVerificationStatus.FAILED, result.status());
        assertEquals("first problem; second problem; third problem", result.summary());
    }

    private static TaskExpectationStaticVerifier.Result expectationResult(
            boolean verifiedAny,
            boolean replacementRequired,
            boolean appendLineRequired,
            boolean bulletCountRequired
    ) {
        return expectationResult(verifiedAny, replacementRequired, appendLineRequired, bulletCountRequired, true);
    }

    private static TaskExpectationStaticVerifier.Result expectationResult(
            boolean verifiedAny,
            boolean replacementRequired,
            boolean appendLineRequired,
            boolean bulletCountRequired,
            boolean coversAllSuccessfulMutations
    ) {
        return new TaskExpectationStaticVerifier.Result(
                verifiedAny,
                replacementRequired,
                appendLineRequired,
                false,
                false,
                bulletCountRequired,
                coversAllSuccessfulMutations,
                List.of(),
                List.of());
    }

    private static ExactEditReplacementVerifier.Result exactEditResult(
            boolean verifiedAny,
            boolean coversAllSuccessfulMutations,
            boolean hasProblem
    ) {
        return new ExactEditReplacementVerifier.Result(
                verifiedAny,
                coversAllSuccessfulMutations,
                hasProblem,
                List.of(),
                List.of());
    }

    private static SourceDerivedArtifactVerifier.Result sourceDerivedResult(boolean required) {
        return new SourceDerivedArtifactVerifier.Result(required, List.of(), List.of());
    }
}
