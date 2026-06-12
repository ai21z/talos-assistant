package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.context.ConversationManager;
import dev.talos.runtime.Result;

import java.util.List;
import java.util.Locale;

/**
 * /compact — force a compaction now (T804).
 *
 * <p>Thin command over {@link ConversationManager#compactNow}: the
 * forced path skips the pair-threshold and over-budget gates and runs
 * even when the failure breaker is open (explicit user intent — a
 * forced failure still counts toward the breaker, a forced success
 * resets it). Outcomes are reported honestly: performed with counts,
 * nothing-to-compact, or failed with the full status — and a failed
 * compaction never loses history.
 */
public final class CompactCommand implements Command {

    private final boolean assistModeCompaction;

    /**
     * @param assistModeCompaction the single bootstrap compaction-mode
     *                             flag shared with MemoryUpdateListener
     *                             and /context — the budget /compact
     *                             enforces is the budget the meter shows
     */
    public CompactCommand(boolean assistModeCompaction) {
        this.assistModeCompaction = assistModeCompaction;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec(
                "compact",
                List.of(),
                "/compact",
                "Summarize older exchanges to free context space.",
                CommandGroup.SESSION);
    }

    @Override
    public Result execute(String args, Context ctx) {
        if (ctx == null || ctx.conversationManager() == null) {
            return new Result.Error("No conversation context is available in this process.", 200);
        }
        if (ctx.llm() == null) {
            return new Result.Error("No model is available to write the summary.", 200);
        }
        ConversationManager cm = ctx.conversationManager();
        if (cm.turnCount() == 0) {
            // Honest fast path: never touch (or warm up) the model for an
            // empty conversation.
            return new Result.Info("Nothing to compact - the conversation is empty.");
        }
        return toResult(cm.compactNow(ctx.llm(), assistModeCompaction));
    }

    /** A real failed attempt is an Error; everything else informs. */
    static Result toResult(ConversationManager.CompactionOutcome outcome) {
        return outcome.attemptedAndFailed()
                ? new Result.Error(formatOutcome(outcome), 200)
                : new Result.Info(formatOutcome(outcome));
    }

    /** Pure outcome formatting — unit-testable without a model. */
    static String formatOutcome(ConversationManager.CompactionOutcome outcome) {
        if (outcome.performed()) {
            return "Compacted: " + outcome.summarizedPairs()
                    + (outcome.summarizedPairs() == 1 ? " older exchange" : " older exchanges")
                    + " summarized - " + outcome.keptPairs() + " kept verbatim (~"
                    + num(outcome.beforeTokens()) + " -> ~" + num(outcome.afterTokens())
                    + " tokens, est.).";
        }
        if (outcome.attemptedAndFailed()) {
            return "Compaction failed: status=" + outcome.status().status()
                    + " category=" + outcome.status().category()
                    + " reason=" + outcome.status().reason()
                    + ". History preserved verbatim - nothing was lost.";
        }
        return switch (outcome.noOpReason()) {
            case "empty" -> "Nothing to compact - the conversation is empty.";
            case "no-llm" -> "No model is available to write the summary.";
            default -> "Nothing to compact: recent history already fits the budget.";
        };
    }

    private static String num(int value) {
        return String.format(Locale.US, "%,d", value);
    }
}
