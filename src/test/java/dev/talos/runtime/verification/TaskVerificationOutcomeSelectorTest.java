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
        assertEquals("Exact edit replacement verification passed.", result.summary());
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
        return new TaskExpectationStaticVerifier.Result(
                verifiedAny,
                replacementRequired,
                appendLineRequired,
                bulletCountRequired,
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
