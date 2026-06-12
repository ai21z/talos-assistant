package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationCompactionStatus;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * T804: /compact — the manual trigger over the T798 compactNow
 * machinery. The command's LLM-touching path is covered at the core
 * level by ConversationManagerCompactNowTest (the compactor's
 * chatPlain path has no scripted test transport, so command-level LLM
 * tests would silently depend on the machine's real config); here we
 * pin the guards — which never reach the model — and the pure
 * outcome-to-Result mapping.
 */
class CompactCommandTest {

    private static Context ctxWith(ConversationManager cm) {
        return Context.builder(new Config())
                .memory(new SessionMemory())
                .conversationManager(cm)
                .build();
    }

    @Test
    void missingContextIsAnError() {
        assertInstanceOf(Result.Error.class, new CompactCommand(true).execute("", null));
    }

    @Test
    void emptyConversationIsAnHonestNoOpThatNeverTouchesTheModel() {
        ConversationManager cm = new ConversationManager(new SessionMemory(), new TokenBudget(200));

        Result r = new CompactCommand(true).execute("", ctxWith(cm));

        assertInstanceOf(Result.Info.class, r);
        assertEquals("Nothing to compact - the conversation is empty.", ((Result.Info) r).text);
    }

    @Test
    void everythingFittingIsReportedWithoutConsultingTheModel() {
        // The forced path's fits-the-budget gate fires before the
        // compactor lambda, so the LLM is never invoked.
        SessionMemory mem = new SessionMemory();
        ConversationManager cm = new ConversationManager(mem, new TokenBudget(1_000_000));
        cm.addTurn("small question", "small answer");

        Result r = new CompactCommand(true).execute("", ctxWith(cm));

        assertInstanceOf(Result.Info.class, r);
        assertEquals("Nothing to compact: recent history already fits the budget.",
                ((Result.Info) r).text);
    }

    /**
     * The outcome-to-Result mapping: a real failed attempt is an Error
     * promising preservation; a performed compaction informs with
     * counts. (That a failed compaction actually preserves history is
     * proven at the core level by ConversationManagerCompactNowTest.)
     */
    @Test
    void performedOutcomeInformsAndFailedOutcomeErrors() {
        var failedStatus = new ConversationCompactionStatus(
                true, "FAILED", "LLM_FAILURE", "down", 1, 0, 8, "NOT_CHECKED");
        var failure = new ConversationManager.CompactionOutcome(
                false, "", 0, 8, 500, 500, failedStatus);
        Result failed = CompactCommand.toResult(failure);
        assertInstanceOf(Result.Error.class, failed);
        assertTrue(((Result.Error) failed).message.startsWith("Compaction failed: status=FAILED"));
        assertTrue(((Result.Error) failed).message
                .endsWith("History preserved verbatim - nothing was lost."));

        var success = new ConversationManager.CompactionOutcome(
                true, "", 6, 2, 900, 300, ConversationCompactionStatus.neverAttempted());
        Result performed = CompactCommand.toResult(success);
        assertInstanceOf(Result.Info.class, performed);
        assertTrue(((Result.Info) performed).text.startsWith("Compacted: "));
        assertTrue(((Result.Info) performed).text.contains("kept verbatim"));
    }

    @Test
    void formatOutcomeShapesArePinned() {
        var performed = new ConversationManager.CompactionOutcome(
                true, "", 6, 4, 5_000, 2_000,
                ConversationCompactionStatus.neverAttempted());
        assertEquals(
                "Compacted: 6 older exchanges summarized - 4 kept verbatim "
                        + "(~5,000 -> ~2,000 tokens, est.).",
                CompactCommand.formatOutcome(performed));

        var single = new ConversationManager.CompactionOutcome(
                true, "", 1, 4, 900, 400,
                ConversationCompactionStatus.neverAttempted());
        assertTrue(CompactCommand.formatOutcome(single).startsWith(
                "Compacted: 1 older exchange summarized"));

        var empty = new ConversationManager.CompactionOutcome(
                false, "empty", 0, 0, 0, 0, ConversationCompactionStatus.neverAttempted());
        assertEquals("Nothing to compact - the conversation is empty.",
                CompactCommand.formatOutcome(empty));

        var fits = new ConversationManager.CompactionOutcome(
                false, "nothing-to-compact", 0, 2, 100, 100,
                ConversationCompactionStatus.neverAttempted());
        assertEquals("Nothing to compact: recent history already fits the budget.",
                CompactCommand.formatOutcome(fits));

        var noLlm = new ConversationManager.CompactionOutcome(
                false, "no-llm", 0, 2, 100, 100,
                ConversationCompactionStatus.neverAttempted());
        assertEquals("No model is available to write the summary.",
                CompactCommand.formatOutcome(noLlm));
    }
}
