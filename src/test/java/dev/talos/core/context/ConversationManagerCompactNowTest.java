package dev.talos.core.context;

import dev.talos.runtime.SessionMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T798: manual compaction ({@code compactNow}) and the one-shot
 * auto-compaction event. The T797 pins prove the auto path is
 * behavior-preserved across the shared-body refactor.
 */
class ConversationManagerCompactNowTest {

    private static ConversationManager smallBudgetManager(SessionMemory mem) {
        return new ConversationManager(mem, new TokenBudget(200));
    }

    private static void addTurns(ConversationManager cm, int pairs) {
        for (int i = 0; i < pairs; i++) {
            cm.addTurn("What about feature number " + i + "?",
                    "Feature " + i + " is a complex topic that requires detailed explanation. "
                            + "Here are the key points you should know about this feature.");
        }
    }

    @Test
    void forcedCompactionRunsBelowTheAutoThresholdAndReportsCounts() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = smallBudgetManager(mem);
        addTurns(cm, 4); // below both auto thresholds (6 / 10)

        ConversationManager.CompactionOutcome outcome = cm.compactNowWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S"),
                ConversationManager.HISTORY_BUDGET_FRACTION);

        assertTrue(outcome.performed(), outcome.noOpReason());
        assertTrue(outcome.summarizedPairs() > 0);
        assertEquals(4, outcome.summarizedPairs() + outcome.keptPairs());
        assertTrue(outcome.afterTokens() < outcome.beforeTokens());
        assertEquals("S", cm.sketch());
        assertNull(cm.pollCompactionEvent(),
                "the forced path must not set the auto-compaction event");
    }

    @Test
    void forcedCompactionWithEverythingFittingIsAnHonestNoOp() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = new ConversationManager(mem, new TokenBudget(1_000_000));
        addTurns(cm, 4);

        ConversationManager.CompactionOutcome outcome = cm.compactNowWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S"),
                ConversationManager.HISTORY_BUDGET_FRACTION);

        assertFalse(outcome.performed());
        assertEquals("nothing-to-compact", outcome.noOpReason());
        assertNull(cm.sketch(), "a no-op must not touch the sketch");
    }

    @Test
    void emptyHistoryAndMissingLlmAreNoOps() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = smallBudgetManager(mem);

        assertEquals("empty", cm.compactNowWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S"),
                ConversationManager.HISTORY_BUDGET_FRACTION).noOpReason());
        assertEquals("no-llm", cm.compactNow(null, true).noOpReason());
    }

    @Test
    void forcedCompactionBypassesAnOpenBreakerButFailuresStillCount() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = smallBudgetManager(mem);
        addTurns(cm, 8);

        // Open the breaker via three auto failures.
        for (int i = 0; i < ConversationManager.MAX_CONSECUTIVE_COMPACTION_FAILURES; i++) {
            cm.maybeCompactWith(
                    (sketch, oldTurns) -> ConversationCompactor.CompactionResult.failed(sketch, "down"),
                    ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                    ConversationManager.HISTORY_BUDGET_FRACTION);
        }

        // Forced path still REACHES the compactor (explicit user intent)...
        ConversationManager.CompactionOutcome forcedFailure = cm.compactNowWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.failed(sketch, "still-down"),
                ConversationManager.HISTORY_BUDGET_FRACTION);
        assertTrue(forcedFailure.attemptedAndFailed(),
                "the open breaker must not block an explicit /compact");
        assertEquals("FAILED", forcedFailure.status().status());

        // ...and a forced success resets the breaker like any success.
        ConversationManager.CompactionOutcome forcedSuccess = cm.compactNowWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S"),
                ConversationManager.HISTORY_BUDGET_FRACTION);
        assertTrue(forcedSuccess.performed(), forcedSuccess.noOpReason());
        assertEquals(0, forcedSuccess.status().consecutiveFailureCount());
    }

    @Test
    void autoCompactionSetsTheEventExactlyOnceAndClearClearsIt() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = smallBudgetManager(mem);
        addTurns(cm, 8);

        boolean compacted = cm.maybeCompactWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S"),
                ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                ConversationManager.HISTORY_BUDGET_FRACTION);

        assertTrue(compacted);
        ConversationManager.CompactionEvent event = cm.pollCompactionEvent();
        assertNotNull(event, "auto compaction must signal the render-side notice");
        assertTrue(event.summarizedPairs() > 0);
        assertNull(cm.pollCompactionEvent(), "the event is one-shot");

        addTurns(cm, 8);
        cm.maybeCompactWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.succeeded("S2"),
                ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                ConversationManager.HISTORY_BUDGET_FRACTION);
        cm.clear();
        assertNull(cm.pollCompactionEvent(), "clear() discards a pending event");
    }

    @Test
    void failedAutoCompactionSetsNoEvent() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = smallBudgetManager(mem);
        addTurns(cm, 8);

        cm.maybeCompactWith(
                (sketch, oldTurns) -> ConversationCompactor.CompactionResult.failed(sketch, "down"),
                ConversationManager.COMPACTION_THRESHOLD_PAIRS,
                ConversationManager.HISTORY_BUDGET_FRACTION);

        assertNull(cm.pollCompactionEvent());
    }

    @Test
    void meterReportsBudgetsThresholdsAndSketchState() {
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = new ConversationManager(mem, new TokenBudget(1000));
        cm.addTurn("a question long enough to estimate tokens",
                "an answer long enough to estimate tokens");
        cm.setSketch("a sketch of older turns");

        ContextMeter assist = cm.meter(true);
        ContextMeter rag = cm.meter(false);

        assertEquals(1000, assist.contextMaxTokens());
        assertEquals(550, assist.historyBudgetTokens(), "assist budget is 55%");
        assertEquals(250, rag.historyBudgetTokens(), "rag budget is 25%");
        assertEquals(10, assist.compactionThresholdPairs());
        assertEquals(6, rag.compactionThresholdPairs());
        assertEquals(1, assist.turnPairs());
        assertTrue(assist.hasSketch());
        assertEquals("a sketch of older turns".length(), assist.sketchChars());
        assertEquals("NEVER_ATTEMPTED", assist.lastCompaction().status());
        assertTrue(assist.historyTokensEstimate() > 0);
    }
}
