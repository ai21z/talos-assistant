package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.ToolCallLoop.ReadOnlySynthesisStatus;
import dev.talos.runtime.failure.FailureDecision;
import dev.talos.runtime.outcome.ReadOnlyToolLimitOutcome;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidence;
import dev.talos.runtime.toolcall.ReadOnlyFinalizationEvidenceTestSupport;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ReadOnlySynthesisMergeTest {
    @TempDir Path workspace;

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

    @Test
    void selectedEnvelopeIsNotUnionedAndExactRestorationKeepsTheOriginalView() throws Exception {
        LocalTurnTraceCapture.begin("merge-selection", "tests", 1, "2026-09-13T00:00:00Z",
                "workspace", "ask", "fixture", "fixture", "Inspect sources");
        try {
            var contract = new TaskContract(TaskType.DIAGNOSE_ONLY, false, false, false,
                    Set.of("script.js"), Set.of(), "Read script.js and explain the selector mismatch. Do not edit.");
            var originalEvidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract,
                    "Earlier answer", Map.of("script.js", "earlier source\n"));
            var original = InspectCompletenessRetry.withRestoredAnswer(
                    loop("Earlier answer", ReadOnlySynthesisStatus.NOT_ATTEMPTED, false),
                    "Earlier answer", originalEvidence);
            var checkpoint = LocalTurnTraceCapture.checkpointExactAnswerCandidate("Earlier answer");
            var retryEvidence = ReadOnlyFinalizationEvidenceTestSupport.evidence(workspace, contract,
                    "Selected retry answer", Map.of("script.js", "retry source\n"));
            var retry = InspectCompletenessRetry.withRestoredAnswer(
                    loop("Selected retry answer", ReadOnlySynthesisStatus.ACCEPTED, false),
                    "Selected retry answer", retryEvidence);
            var merged = InspectCompletenessRetry.mergeReadOnlyRetryEvidence(original, retry);
            assertSame(retryEvidence, merged.readOnlyFinalizationEvidence());
            assertEquals("retry source\n", merged.readOnlyFinalizationEvidence().sources().getFirst().text());
            assertTrue(merged.readOnlyFinalizationEvidence().matches("Selected retry answer", workspace, contract));

            assertTrue(LocalTurnTraceCapture.restoreExactAnswerCandidate(checkpoint, "Earlier answer",
                    LocalTurnTraceCapture.AnswerCandidateRestorationReason.SPECULATIVE_RETRY_REJECTED));
            var restored = InspectCompletenessRetry.withRestoredAnswer(merged, "Earlier answer", originalEvidence);
            assertSame(originalEvidence, restored.readOnlyFinalizationEvidence());
            assertEquals("earlier source\n", restored.readOnlyFinalizationEvidence().sources().getFirst().text());
            assertTrue(restored.readOnlyFinalizationEvidence().matches("Earlier answer", workspace, contract));
            assertSame(ReadOnlyFinalizationEvidence.empty(), InspectCompletenessRetry.mergeReadOnlyRetryEvidence(
                    original, loop("Rejected", ReadOnlySynthesisStatus.REJECTED, false))
                    .readOnlyFinalizationEvidence());
        } finally {
            TurnTaskContractCapture.clear();
            LocalTurnTraceCapture.clear();
        }
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
