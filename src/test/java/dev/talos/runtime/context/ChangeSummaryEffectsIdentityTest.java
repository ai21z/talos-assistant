package dev.talos.runtime.context;

import dev.talos.runtime.JsonSessionStore;
import dev.talos.runtime.TurnPolicyTrace;
import dev.talos.runtime.TurnRecord;
import dev.talos.runtime.TurnAudit;
import dev.talos.runtime.TurnResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.tools.ToolMutationEffects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ChangeSummaryEffectsIdentityTest {
    @Test void uncertaintySurvivesSpacesAndBoundedLongDisplayLabels() {
        for (String path : List.of("dir/two  spaces.txt", " leading.txt",
                "dir/" + "a".repeat(310) + "/tail.txt")) {
            ChangeSummaryContext context = history(record(1, uncertain(path)));
            assertEquals(1, context.changedFiles().size());
            assertEquals("UNKNOWN", context.changedFiles().getFirst().toolOutcome());
            assertTrue(context.changedFiles().getFirst().path().length() <= 300);
            assertTrue(context.renderForChangeSummaryQuestion().contains("possible effects"));
            assertFalse(context.renderForChangeSummaryQuestion().contains("No files were changed"));
        }
    }

    @Test void collidingAppliedAndUncertainLabelsAreSeparateFactsWithinOneCall() {
        String uncertain = "dir/two  spaces.txt";
        String applied = "dir/two spaces.txt";
        ToolMutationEffects effects = new ToolMutationEffects(List.of(applied), List.of(uncertain));
        ChangeSummaryContext context = history(record(1, call(false, effects)));
        assertEquals(2, context.changedFiles().size());
        assertEquals(List.of("APPLIED_UNVERIFIED", "UNKNOWN"),
                context.changedFiles().stream().map(ChangeSummaryContext.FileChange::toolOutcome).toList());
        assertEquals(1, context.changedFiles().stream().map(ChangeSummaryContext.FileChange::path).distinct().count());
        assertEquals(2, context.changedFiles().stream().map(ChangeSummaryContext.FileChange::pathIdentity).distinct().count());
        assertTrue(context.renderForChangeSummaryQuestion().contains("same shortened or redacted display label"));
    }

    @Test void exactPathInBothEffectSetsRemainsUnknown() {
        ChangeSummaryContext context = history(record(1, call(true,
                new ToolMutationEffects(List.of("note.txt"), List.of("note.txt")))));
        assertEquals(1, context.changedFiles().size());
        assertEquals("UNKNOWN", context.changedFiles().getFirst().toolOutcome());
    }

    @Test void differentLaterPathsCannotEraseAnUncertainTruncatedOrSpacedLabel() {
        for (List<String> pair : List.of(
                List.of("dir/two  spaces.txt", "dir/two spaces.txt"),
                List.of(" leading.txt", "leading.txt"),
                List.of("dir/" + "a".repeat(310) + "/first.txt", "dir/" + "a".repeat(310) + "/second.txt"))) {
            ChangeSummaryContext acrossTurns = history(record(1, uncertain(pair.getFirst())),
                    record(2, applied(pair.getLast())));
            assertEquals(List.of("UNKNOWN", "SUCCEEDED"), acrossTurns.changedFiles().stream()
                    .map(ChangeSummaryContext.FileChange::toolOutcome).toList());
            ChangeSummaryContext sameTurn = history(record(1,
                    uncertain(pair.getFirst()), applied(pair.getLast())));
            assertEquals(List.of("UNKNOWN", "SUCCEEDED"), sameTurn.changedFiles().stream()
                    .map(ChangeSummaryContext.FileChange::toolOutcome).toList());
        }
    }

    @Test void genuineLaterRecoveryUpdatesOnlyTheSameExactPath() {
        String first = "dir/two  spaces.txt";
        String second = "dir/two spaces.txt";
        ChangeSummaryContext context = history(record(1, uncertain(first)),
                record(2, uncertain(second)), record(3, applied(first)));
        assertEquals(List.of("UNKNOWN", "SUCCEEDED"), context.changedFiles().stream()
                .map(ChangeSummaryContext.FileChange::toolOutcome).toList());
        assertEquals(List.of(2, 3), context.changedFiles().stream()
                .map(ChangeSummaryContext.FileChange::turnNumber).toList());
    }

    @Test void onlyUncertainEffectsLeaveExpectedTargetsUnresolved() {
        TurnRecord original = record(1, uncertain("dir/two  spaces.txt"));
        TurnPolicyTrace policy = new TurnPolicyTrace("FILE_EDIT", true, true,
                List.of("dir/two  spaces.txt"), List.of(), "EDIT", "VERIFY",
                List.of(), List.of(), List.of());
        TurnRecord withExpectedTarget = new TurnRecord(original.turnNumber(), original.timestamp(),
                original.durationMs(), original.userInput(), original.assistantText(), original.toolCalls(),
                1, 1, 0, "", "error", policy, original.traceId());
        assertEquals(List.of("dir/two spaces.txt"), history(withExpectedTarget).unresolvedTargets());
    }

    @Test void durableRestorationPreservesCollidingEffectsAndRedaction(@TempDir Path sessions) {
        JsonSessionStore store = new JsonSessionStore(sessions);
        List<TurnRecord> records = List.of(record(1, uncertain("dir/two  spaces.txt")),
                record(2, applied("dir/two spaces.txt")),
                record(3, uncertain("password=synthetic-secret-value")));
        try (var writer = store.acquireSessionWriter("effect-labels")) {
            for (TurnRecord record : records) writer.appendTurn("effect-labels", record);
        }
        ChangeSummaryContext context = ChangeSummaryContext.fromTurnRecords(store.loadTurns("effect-labels"));
        assertEquals(List.of("UNKNOWN", "SUCCEEDED", "UNKNOWN"), context.changedFiles().stream()
                .map(ChangeSummaryContext.FileChange::toolOutcome).toList());
        assertFalse(context.renderForChangeSummaryQuestion().contains("synthetic-secret-value"));
        assertTrue(context.renderForChangeSummaryQuestion().contains("possible effects"));
    }

    @Test void passedCollidingLabelCannotClearEarlierUnknownVerificationFailure() {
        ChangeSummaryContext earlier = updateWithVerification(ChangeSummaryContext.none(),
                record(1, uncertain("dir/two  spaces.txt")), "FAILED");
        assertEquals(1, earlier.unresolvedVerificationFailures().size());
        ChangeSummaryContext later = updateWithVerification(earlier,
                record(2, applied("dir/two spaces.txt")), "PASSED");
        assertEquals(1, later.unresolvedVerificationFailures().size());
        assertEquals("UNKNOWN", later.changedFiles().getFirst().toolOutcome());
        assertTrue(later.renderForChangeSummaryQuestion().contains("not verified complete"));
    }

    @Test void durableRedactionCollisionCannotSettleAnEarlierUnknown(@TempDir Path sessions) throws Exception {
        JsonSessionStore store = new JsonSessionStore(sessions);
        String first = "password=synthetic-one";
        String second = "password=synthetic-two";
        try (var writer = store.acquireSessionWriter("redacted-labels")) {
            writer.appendTurn("redacted-labels", record(1, uncertain(first)));
            writer.appendTurn("redacted-labels", record(2, applied(second)));
        }
        String persisted = java.nio.file.Files.readString(sessions.resolve("redacted-labels.turns.jsonl"));
        assertFalse(persisted.contains("synthetic-one"));
        assertFalse(persisted.contains("synthetic-two"));
        List<TurnRecord> restored = store.loadTurns("redacted-labels");
        assertEquals(2, restored.size());
        assertEquals(restored.getFirst().toolCalls().getFirst().mutationEffects().uncertainPaths().getFirst(),
                restored.getLast().toolCalls().getFirst().mutationEffects().appliedPaths().getFirst());
        assertTrue(restored.getLast().toolCalls().getFirst().success());
        ChangeSummaryContext context = ChangeSummaryContext.fromTurnRecords(restored);
        assertEquals(1, context.changedFiles().size());
        assertEquals("UNKNOWN", context.changedFiles().getFirst().toolOutcome());
        assertEquals(1, context.changedFiles().getFirst().turnNumber());
        assertTrue(context.renderForChangeSummaryQuestion().contains("possible effects"));
    }

    @Test void redactedLabelsNeverClearAnEarlierFailureEvenAfterPassedVerification() {
        for (String path : List.of("password=[redacted]", "<protected-path>",
                "[redacted-canary]", "[redacted-private-document-canary]")) {
            ChangeSummaryContext earlier = updateWithVerification(ChangeSummaryContext.none(),
                    record(1, uncertain(path)), "FAILED");
            ChangeSummaryContext later = updateWithVerification(earlier,
                    record(2, applied(path)), "PASSED");
            assertEquals("UNKNOWN", later.changedFiles().getFirst().toolOutcome(), path);
            assertEquals(1, later.unresolvedVerificationFailures().size(), path);
        }
    }

    @Test void exactRecoveryCanClearEarlierUnambiguousVerificationFailure() {
        ChangeSummaryContext earlier = updateWithVerification(ChangeSummaryContext.none(),
                record(1, uncertain("note.txt")), "FAILED");
        ChangeSummaryContext later = updateWithVerification(earlier,
                record(2, applied("note.txt")), "PASSED");
        assertTrue(later.unresolvedVerificationFailures().isEmpty());
        assertEquals("SUCCEEDED", later.changedFiles().getFirst().toolOutcome());
    }

    private static ChangeSummaryContext updateWithVerification(ChangeSummaryContext previous,
                                                               TurnRecord record, String status) {
        LocalTurnTrace trace = LocalTurnTrace.builder(record.traceId(), "effect-labels", record.turnNumber(),
                record.timestamp().toString()).verification(status, status, List.of()).build();
        TurnAudit audit = new TurnAudit(record.toolCalls(), 1, 1, 0, record.policyTrace(), trace);
        return ChangeSummaryContext.updateAfterTurn(previous,
                new TurnResult(null, null, record.turnNumber(), Duration.ZERO, audit));
    }

    private static ChangeSummaryContext history(TurnRecord... records) {
        return ChangeSummaryContext.fromTurnRecords(List.of(records));
    }

    private static TurnRecord record(int number, TurnRecord.ToolCallSummary... calls) {
        return new TurnRecord(number, Instant.parse("2026-09-05T00:00:00Z").plusSeconds(number),
                1, "mutation", "result", List.of(calls), 1, 1, 0, "", "error",
                TurnPolicyTrace.empty(), "trace-effects-" + number);
    }

    private static TurnRecord.ToolCallSummary uncertain(String path) {
        return call(false, ToolMutationEffects.uncertain(path));
    }

    private static TurnRecord.ToolCallSummary applied(String path) {
        return call(true, ToolMutationEffects.applied(path));
    }

    private static TurnRecord.ToolCallSummary call(boolean success, ToolMutationEffects effects) {
        return new TurnRecord.ToolCallSummary("talos.apply_workspace_batch", "", List.of(),
                success, success ? "" : "uncertain", effects);
    }
}
