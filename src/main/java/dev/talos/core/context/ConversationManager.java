package dev.talos.core.context;

import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

/**
 * Token-aware conversation history manager with automatic compaction.
 *
 * <p>Wraps {@link ConversationMemory} with a {@link TokenBudget} to provide
 * budget-aware history retrieval. {@link #buildHistory(int)} returns as
 * many recent turns as fit within the available token budget.
 *
 * <p>When conversation history grows beyond what fits in the budget,
 * older turns are compacted into a short sketch via
 * {@link ConversationCompactor}. The sketch is prepended to the
 * history as a system-role message, preserving context about the user's
 * goal and key decisions without consuming the full token budget.
 *
 * <p>Compaction is triggered automatically by {@link #maybeCompact(LlmClient)}
 * which should be called after each turn (typically from
 * {@link dev.talos.runtime.MemoryUpdateListener}).
 *
 * <p>Thread-safe: delegates synchronization to the provided memory implementation.
 * The sketch field is guarded by {@code synchronized} on this instance.
 */
public final class ConversationManager {

    private static final Logger LOG = LoggerFactory.getLogger(ConversationManager.class);

    /**
     * Minimum number of turn pairs before compaction is considered.
     * Below this threshold, all turns fit comfortably and compaction
     * would waste an LLM call.
     */
    static final int COMPACTION_THRESHOLD_PAIRS = 6;

    /**
     * Higher compaction threshold for assist/unified mode.
     * Editing tasks produce many short turns; compacting too early
     * destroys the file-state context the model needs to stay coherent.
     */
    static final int ASSIST_COMPACTION_THRESHOLD_PAIRS = 10;

    /**
     * Fraction of context window allocated to history in RAG mode.
     * Used both for buildHistory budget and as the trigger threshold
     * for compaction (when stored history exceeds this budget).
     */
    static final double HISTORY_BUDGET_FRACTION = 0.25;

    /**
     * Fraction of context window allocated to history in assist/ask mode.
     * Assist mode has no RAG snippets competing for context space, so
     * history gets a much larger share - critical for multi-turn creative
     * tasks where the user iterates on the assistant's prior output.
     */
    static final double ASSIST_HISTORY_BUDGET_FRACTION = 0.55;

    /**
     * Stop attempting compaction after repeated failures in the same session.
     * Failed compaction preserves verbatim turns, so repeatedly retrying would
     * just burn model calls without improving context safety.
     */
    static final int MAX_CONSECUTIVE_COMPACTION_FAILURES = 3;

    private final ConversationMemory memory;
    private final TokenBudget budget;

    /** Compact sketch of older turns (null until first compaction). */
    private volatile String sketch;
    private int consecutiveCompactionFailures;
    private volatile ConversationCompactionStatus lastCompactionStatus =
            ConversationCompactionStatus.neverAttempted();
    /** One-shot auto-compaction signal, consumed by {@link #pollCompactionEvent()}. */
    private volatile CompactionEvent pendingEvent;

    public ConversationManager(ConversationMemory memory, TokenBudget budget) {
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    public ConversationManager(ConversationMemory memory) {
        this(memory, new TokenBudget());
    }

    /** Record a completed user/assistant exchange. */
    public void addTurn(String userInput, String assistantResponse) {
        if (userInput != null && assistantResponse != null && !assistantResponse.isBlank()) {
            memory.update(userInput, assistantResponse);
        }
    }

    /**
     * Build history that fits within the given token budget.
     * If a compacted sketch exists, it is prepended as the first message
     * (assistant-role summary of older context), and the remaining budget
     * is filled with the most recent verbatim turns.
     *
     * <p>Turns are kept as user/assistant pairs - never split.
     *
     * @param availableTokens maximum tokens to spend on history
     * @return list of ChatMessage in chronological order
     */
    public List<ChatMessage> buildHistory(int availableTokens) {
        List<ChatMessage> allTurns = memory.getTurns();
        if (allTurns.isEmpty() || availableTokens <= 0) {
            // Even with no turns, include sketch if available
            String sk = sketch;
            if (sk != null && !sk.isBlank() && availableTokens > 0) {
                int sketchTokens = budget.estimateTokens(sk);
                if (sketchTokens <= availableTokens) {
                    return List.of(ChatMessage.assistant("[Conversation context] " + sk));
                }
            }
            return List.of();
        }

        List<ChatMessage> selected = new ArrayList<>();
        int tokensUsed = 0;

        // Reserve space for sketch if present
        String sk = sketch;
        int sketchTokens = 0;
        if (sk != null && !sk.isBlank()) {
            sketchTokens = budget.estimateTokens("[Conversation context] " + sk);
            tokensUsed += sketchTokens;
        }

        // Walk backward through pairs, accumulate most recent that fit
        for (int i = allTurns.size() - 1; i >= 1; i -= 2) {
            ChatMessage assistant = allTurns.get(i);
            ChatMessage user = allTurns.get(i - 1);

            int pairTokens = budget.estimateTokens(user.content())
                           + budget.estimateTokens(assistant.content());

            if (tokensUsed + pairTokens > availableTokens) {
                break;
            }

            selected.addFirst(assistant);
            selected.addFirst(user);
            tokensUsed += pairTokens;
        }

        // Prepend sketch as first message if present
        if (sk != null && !sk.isBlank() && sketchTokens <= availableTokens) {
            selected.addFirst(ChatMessage.assistant("[Conversation context] " + sk));
        }

        return List.copyOf(selected);
    }

    /** Build history using 25% of context window as default budget (for RAG mode). */
    public List<ChatMessage> buildHistory() {
        int historyBudget = (int) (budget.contextMaxTokens() * HISTORY_BUDGET_FRACTION);
        return buildHistory(historyBudget);
    }

    /**
     * Build history using 55% of context window (for assist/ask mode).
     *
     * <p>In assist mode there are no RAG snippets competing for context space,
     * so history gets a much larger share. This is critical for multi-turn
     * creative tasks where the user iterates on the assistant's prior output
     * (e.g., "make the ASCII cat bigger", "add more detail to the poem").
     *
     * @return list of ChatMessage in chronological order
     */
    public List<ChatMessage> buildHistoryForAssist() {
        int historyBudget = (int) (budget.contextMaxTokens() * ASSIST_HISTORY_BUDGET_FRACTION);
        return buildHistory(historyBudget);
    }

    /**
     * Check whether compaction is needed and perform it if so.
     * Uses the RAG-mode budget (25% of context window).
     *
     * <p>For unified/assist mode, use {@link #maybeCompactForAssist(LlmClient)}
     * which uses a larger budget and higher pair threshold.
     *
     * @param llm the LLM client to use for summarization (must not be null)
     * @return true if compaction was performed
     */
    public boolean maybeCompact(LlmClient llm) {
        if (llm == null) return false;
        return maybeCompactWith(
                (existingSketch, oldTurns) -> ConversationCompactor.tryCompact(existingSketch, oldTurns, llm),
                COMPACTION_THRESHOLD_PAIRS,
                HISTORY_BUDGET_FRACTION);
    }

    /**
     * Check whether compaction is needed for assist/unified mode.
     * Uses the larger assist budget (55% of context window) and a higher
     * pair threshold (10 pairs instead of 6) because multi-turn editing
     * sessions produce many short turns and need more context retained.
     *
     * <p>This fixes a critical bug where unified mode used 55% for
     * building history ({@link #buildHistoryForAssist()}) but only 25%
     * for the compaction trigger, causing premature compaction that
     * destroyed file-state context during repair loops.
     *
     * @param llm the LLM client to use for summarization (must not be null)
     * @return true if compaction was performed
     */
    public boolean maybeCompactForAssist(LlmClient llm) {
        if (llm == null) return false;
        return maybeCompactWith(
                (existingSketch, oldTurns) -> ConversationCompactor.tryCompact(existingSketch, oldTurns, llm),
                ASSIST_COMPACTION_THRESHOLD_PAIRS,
                ASSIST_HISTORY_BUDGET_FRACTION);
    }

    /**
     * Internal compaction implementation with configurable thresholds.
     *
     * <p>Compaction triggers when:
     * <ol>
     *   <li>There are at least {@code pairThreshold} turn pairs, AND</li>
     *   <li>The total stored history exceeds the history budget</li>
     * </ol>
     *
     * @param compactor      the compaction function used to summarize older turns
     * @param pairThreshold  minimum turn pairs before compaction is considered
     * @param budgetFraction fraction of context window used as the history budget
     * @return true if compaction was performed
     */
    boolean maybeCompactWith(
            BiFunction<String, List<ChatMessage>, ConversationCompactor.CompactionResult> compactor,
            int pairThreshold,
            double budgetFraction) {
        CompactionOutcome outcome = compactInternal(compactor, pairThreshold, budgetFraction, false);
        if (outcome.performed()) {
            // One-shot signal for the render-side notice (T798/T805). Set
            // ONLY by the auto path - /compact reports its own outcome.
            pendingEvent = new CompactionEvent(
                    outcome.summarizedPairs(),
                    outcome.keptPairs(),
                    outcome.beforeTokens(),
                    outcome.afterTokens());
        }
        return outcome.performed();
    }

    /**
     * Manual compaction for {@code /compact} (T798): skips the pair-threshold
     * and over-budget gates (explicit user intent) and bypasses an OPEN
     * failure breaker - the user consented to spend the LLM call - but a
     * forced failure still increments the breaker counter. The recent tail
     * that fits the mode's history budget stays verbatim, exactly like the
     * auto path.
     */
    public CompactionOutcome compactNow(LlmClient llm, boolean assistMode) {
        if (llm == null) {
            return CompactionOutcome.noOp("no-llm", estimateHistoryTokens(), turnCount());
        }
        return compactNowWith(
                (existingSketch, oldTurns) ->
                        ConversationCompactor.tryCompact(existingSketch, oldTurns, llm),
                assistMode ? ASSIST_HISTORY_BUDGET_FRACTION : HISTORY_BUDGET_FRACTION);
    }

    CompactionOutcome compactNowWith(
            BiFunction<String, List<ChatMessage>, ConversationCompactor.CompactionResult> compactor,
            double budgetFraction) {
        return compactInternal(compactor, 0, budgetFraction, true);
    }

    private CompactionOutcome compactInternal(
            BiFunction<String, List<ChatMessage>, ConversationCompactor.CompactionResult> compactor,
            int pairThreshold,
            double budgetFraction,
            boolean forced) {
        int beforeTokens = estimateHistoryTokens();
        if (compactor == null) {
            return CompactionOutcome.noOp("no-compactor", beforeTokens, turnCount());
        }
        List<ChatMessage> allTurns = memory.getTurns();
        if (allTurns.isEmpty()) {
            return CompactionOutcome.noOp("empty", beforeTokens, 0);
        }
        if (!completeUserAssistantPairs(allTurns)) {
            LOG.warn("Compaction skipped: stored conversation history is not complete user/assistant pairs");
            return CompactionOutcome.noOp("odd-shape", beforeTokens, allTurns.size() / 2);
        }
        int pairs = allTurns.size() / 2;
        if (!forced && pairs < pairThreshold) {
            return CompactionOutcome.noOp("below-threshold", beforeTokens, pairs);
        }

        int historyBudget = (int) (budget.contextMaxTokens() * budgetFraction);

        if (!forced && beforeTokens <= historyBudget) {
            return CompactionOutcome.noOp("within-budget", beforeTokens, pairs);
        }

        if (!forced) {
            synchronized (this) {
                if (consecutiveCompactionFailures >= MAX_CONSECUTIVE_COMPACTION_FAILURES) {
                    LOG.warn("Compaction skipped: {} consecutive failures reached session breaker",
                            consecutiveCompactionFailures);
                    lastCompactionStatus = ConversationCompactionStatus.skipped(
                            "failure-breaker-open",
                            consecutiveCompactionFailures,
                            allTurns.size());
                    return CompactionOutcome.noOp("failure-breaker-open", beforeTokens, pairs);
                }
            }
        }

        LOG.info("Compaction triggered: {} pairs, {} tokens > {} budget (fraction={})",
                pairs, beforeTokens, historyBudget, budgetFraction);

        // Identify which turns don't fit (the "old" ones)
        List<ChatMessage> oldTurns = new ArrayList<>();
        int tokensFromEnd = 0;

        // Walk backward to find the split point
        int splitIndex = allTurns.size();
        for (int i = allTurns.size() - 1; i >= 1; i -= 2) {
            ChatMessage assistant = allTurns.get(i);
            ChatMessage user = allTurns.get(i - 1);
            int pairTokens = budget.estimateTokens(user.content())
                           + budget.estimateTokens(assistant.content());

            if (tokensFromEnd + pairTokens > historyBudget) {
                splitIndex = i - 1;
                break;
            }
            tokensFromEnd += pairTokens;
            splitIndex = i - 1;
        }

        // Collect old turns (everything before splitIndex)
        if (splitIndex <= 0) {
            return CompactionOutcome.noOp("nothing-to-compact", beforeTokens, pairs);
        }
        for (int i = 0; i < splitIndex; i++) {
            oldTurns.add(allTurns.get(i));
        }

        if (oldTurns.isEmpty()) {
            return CompactionOutcome.noOp("nothing-to-compact", beforeTokens, pairs);
        }
        int preservedTailTurns = Math.max(0, allTurns.size() - oldTurns.size());

        // Perform compaction. Pruning is allowed only after an explicit success.
        ConversationCompactor.CompactionResult result;
        String priorSketch = sketch;
        try {
            result = compactor.apply(priorSketch, List.copyOf(oldTurns));
        } catch (Exception e) {
            result = ConversationCompactor.CompactionResult.failed(
                    priorSketch, "exception:" + e.getClass().getSimpleName());
        }

        if (result == null || !result.succeeded()) {
            int failureCount;
            if (result == null || result.countsTowardFailureBreaker()) {
                synchronized (this) {
                    consecutiveCompactionFailures++;
                    failureCount = consecutiveCompactionFailures;
                }
            } else {
                synchronized (this) {
                    failureCount = consecutiveCompactionFailures;
                }
            }
            lastCompactionStatus = ConversationCompactionStatus.fromResult(
                    result,
                    failureCount,
                    oldTurns.size(),
                    preservedTailTurns);
            LOG.warn("Compaction failed: reason={}, category={}, preserved {} old turns and prior sketch",
                    result != null ? result.reason() : "null-result",
                    result != null ? result.category() : "NULL_RESULT",
                    oldTurns.size());
            return CompactionOutcome.failed(lastCompactionStatus, beforeTokens, pairs);
        }

        String newSketch = result.sketch();
        synchronized (this) {
            sketch = newSketch;
            consecutiveCompactionFailures = 0;
            lastCompactionStatus = ConversationCompactionStatus.fromResult(
                    result,
                    0,
                    oldTurns.size(),
                    preservedTailTurns);
        }

        // Prune old turns from memory
        memory.pruneOldest(oldTurns.size());

        LOG.info("Compaction complete: pruned {} turns, sketch={} chars, remaining {} turns",
                oldTurns.size(), (newSketch != null ? newSketch.length() : 0),
                memory.getTurns().size());

        return new CompactionOutcome(
                true,
                "",
                oldTurns.size() / 2,
                preservedTailTurns / 2,
                beforeTokens,
                estimateHistoryTokens(),
                lastCompactionStatus);
    }

    /** Outcome of one compaction attempt (auto or forced), for command rendering. */
    public record CompactionOutcome(
            boolean performed,
            String noOpReason,
            int summarizedPairs,
            int keptPairs,
            int beforeTokens,
            int afterTokens,
            ConversationCompactionStatus status
    ) {
        static CompactionOutcome noOp(String reason, int beforeTokens, int pairs) {
            return new CompactionOutcome(false, reason, 0, pairs, beforeTokens, beforeTokens, null);
        }

        static CompactionOutcome failed(
                ConversationCompactionStatus status, int beforeTokens, int pairs) {
            return new CompactionOutcome(false, "", 0, pairs, beforeTokens, beforeTokens, status);
        }

        /** True when an attempt ran and failed (distinct from a gate no-op). */
        public boolean attemptedAndFailed() {
            return !performed && noOpReason.isEmpty();
        }
    }

    /** One-shot signal that an automatic compaction just happened (T798/T805). */
    public record CompactionEvent(
            int summarizedPairs, int keptPairs, int beforeTokens, int afterTokens) {}

    /** Returns and clears the pending auto-compaction event, if any. */
    public CompactionEvent pollCompactionEvent() {
        CompactionEvent event = pendingEvent;
        pendingEvent = null;
        return event;
    }

    /** Context-window usage snapshot for the /context meter (T798). */
    public ContextMeter meter(boolean assistMode) {
        double fraction = assistMode ? ASSIST_HISTORY_BUDGET_FRACTION : HISTORY_BUDGET_FRACTION;
        int threshold = assistMode ? ASSIST_COMPACTION_THRESHOLD_PAIRS : COMPACTION_THRESHOLD_PAIRS;
        String currentSketch = sketch();
        return new ContextMeter(
                estimateHistoryTokens(),
                (int) (budget.contextMaxTokens() * fraction),
                budget.contextMaxTokens(),
                budget.responseReserveFraction(),
                budget.overheadTokens(),
                turnCount(),
                currentSketch != null && !currentSketch.isBlank(),
                currentSketch == null ? 0 : currentSketch.length(),
                threshold,
                lastCompactionStatus);
    }

    /** Estimate total token count of all stored history. */
    public int estimateHistoryTokens() {
        return estimateTokens(memory.getTurns(), budget);
    }

    /**
     * Estimate token cost of a pre-built history message list.
     * Use this after {@link #buildHistory()} to measure how many tokens
     * the selected history consumes, so the caller can subtract them
     * from the snippet budget.
     *
     * @param history the history messages (from {@link #buildHistory()})
     * @param budget  the token budget to use for estimation
     * @return estimated token count for the history messages
     */
    public static int estimateTokens(List<ChatMessage> history, TokenBudget budget) {
        if (history == null || history.isEmpty() || budget == null) return 0;
        int total = 0;
        for (ChatMessage msg : history) {
            total += budget.estimateTokens(msg.content());
        }
        return total;
    }

    /** Number of stored user/assistant exchanges (pairs). */
    public int turnCount() {
        return memory.getTurns().size() / 2;
    }

    private static boolean completeUserAssistantPairs(List<ChatMessage> turns) {
        if (turns == null) return true;
        // SessionMemory appends pairs; if another memory implementation violates
        // that shape, fail closed rather than guessing a safe compaction boundary.
        if (turns.size() % 2 != 0) return false;
        for (int i = 0; i < turns.size(); i += 2) {
            ChatMessage user = turns.get(i);
            ChatMessage assistant = turns.get(i + 1);
            if (user == null || assistant == null) return false;
            if (!"user".equals(user.role()) || !"assistant".equals(assistant.role())) return false;
        }
        return true;
    }

    /** Check if any conversation history exists. */
    public boolean hasHistory() {
        return memory.hasContent() || (sketch != null && !sketch.isBlank());
    }

    /** Clear all conversation history and sketch. */
    public void clear() {
        memory.clear();
        synchronized (this) {
            sketch = null;
            consecutiveCompactionFailures = 0;
            lastCompactionStatus = ConversationCompactionStatus.neverAttempted();
            pendingEvent = null;
        }
    }

    /** Access the underlying memory (for backward compatibility). */
    public ConversationMemory memory() {
        return memory;
    }

    /** Access the token budget. */
    public TokenBudget budget() {
        return budget;
    }

    /** Get the current sketch (may be null). */
    public synchronized String sketch() {
        return sketch;
    }

    /** Latest compaction attempt status for trace and prompt-debug audit metadata. */
    public ConversationCompactionStatus lastCompactionStatus() {
        return lastCompactionStatus;
    }

    /** Set the sketch directly (for testing or restoration). */
    public synchronized void setSketch(String sketch) {
        this.sketch = sketch;
    }
}

