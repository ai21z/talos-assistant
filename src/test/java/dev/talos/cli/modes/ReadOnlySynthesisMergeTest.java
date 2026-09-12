package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallLoop.ReadOnlySynthesisStatus;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.ReadOnlyToolLimitOutcome;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlySynthesisMergeTest {
    @Test
    void inspectionMergeKeepsOnlyTheSelectedCandidatesAcceptanceAndAllAttemptHistory() {
        for (var originalStatus : ReadOnlySynthesisStatus.values()) {
            for (var retryStatus : ReadOnlySynthesisStatus.values()) {
                var original = loop("Earlier answer", originalStatus, false);
                var retry = loop("Selected retry answer", retryStatus, true);
                assertSelectedMerge(InspectCompletenessRetry.mergeReadOnlyRetryEvidence(original, retry),
                        originalStatus, retryStatus);
            }
        }
    }

    @Test
    void mutationMergeKeepsOnlyTheSelectedCandidatesAcceptanceAndAllAttemptHistory() {
        for (var originalStatus : ReadOnlySynthesisStatus.values()) {
            for (var retryStatus : ReadOnlySynthesisStatus.values()) {
                var original = loop("Earlier answer", originalStatus, false);
                var retry = loop("Selected retry answer", retryStatus, true);
                assertSelectedMerge(MissingMutationRetry.mergeEvidence(original, retry),
                        originalStatus, retryStatus);
            }
        }
    }

    @Test
    void acceptedRetryCapExemptionRemainsBoundToItsExactSelectedText() {
        var merged = InspectCompletenessRetry.mergeReadOnlyRetryEvidence(
                loop("Earlier answer", ReadOnlySynthesisStatus.NOT_ATTEMPTED, true),
                loop("Selected retry answer", ReadOnlySynthesisStatus.ACCEPTED, false));
        assertTrue(merged.hitIterLimit());
        assertFalse(ReadOnlyToolLimitOutcome.assess(null, merged, false, "Selected retry answer")
                .withoutRuntimeAnswer());
        assertTrue(ReadOnlyToolLimitOutcome.assess(null, merged, false, "Different later answer")
                .withoutRuntimeAnswer());
    }

    private static void assertSelectedMerge(ToolCallLoop.LoopResult merged,
                                            ReadOnlySynthesisStatus originalStatus,
                                            ReadOnlySynthesisStatus retryStatus) {
        var expected = retryStatus != ReadOnlySynthesisStatus.NOT_ATTEMPTED ? retryStatus
                : originalStatus != ReadOnlySynthesisStatus.NOT_ATTEMPTED
                    ? ReadOnlySynthesisStatus.REJECTED : ReadOnlySynthesisStatus.NOT_ATTEMPTED;
        assertEquals(expected, merged.readOnlySynthesisStatus(), originalStatus + " -> " + retryStatus);
        assertEquals("Selected retry answer", merged.finalAnswer());
        assertTrue(merged.hitIterLimit());
        assertTrue(merged.finalAnswerOutputLimitReached());
        assertEquals(4, merged.iterations());
        assertEquals(2, merged.toolsInvoked());
    }

    private static ToolCallLoop.LoopResult loop(String answer, ReadOnlySynthesisStatus status,
                                               boolean outputLimited) {
        return new ToolCallLoop.LoopResult(answer, 2, 1, List.of("talos.read_file"), List.of(),
                0, 0, true, 0, List.of("notes.md"), 0, 0, 0, 0,
                FailureDecision.continueLoop(), List.of(), Map.of(), outputLimited, status);
    }
}
