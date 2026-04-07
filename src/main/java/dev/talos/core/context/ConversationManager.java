package dev.talos.core.context;

import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.llm.LlmClient;
import dev.talos.spi.types.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Token-aware conversation history manager with automatic compaction.
 *
 * <p>Wraps {@link SessionMemory} with a {@link TokenBudget} to provide
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
 * <p>Thread-safe: delegates to SessionMemory which synchronizes internally.
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
     * Fraction of context window allocated to history.
     * Used both for buildHistory budget and as the trigger threshold
     * for compaction (when stored history exceeds this budget).
     */
    static final double HISTORY_BUDGET_FRACTION = 0.25;

    private final SessionMemory memory;
    private final TokenBudget budget;

    /** Compact sketch of older turns (null until first compaction). */
    private volatile String sketch;

    public ConversationManager(SessionMemory memory, TokenBudget budget) {
        this.memory = Objects.requireNonNull(memory, "memory must not be null");
        this.budget = Objects.requireNonNull(budget, "budget must not be null");
    }

    public ConversationManager(SessionMemory memory) {
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
     * <p>Turns are kept as user/assistant pairs — never split.
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

    /** Build history using 25% of context window as default budget. */
    public List<ChatMessage> buildHistory() {
        int historyBudget = (int) (budget.contextMaxTokens() * HISTORY_BUDGET_FRACTION);
        return buildHistory(historyBudget);
    }

    /**
     * Check whether compaction is needed and perform it if so.
     *
     * <p>Compaction triggers when:
     * <ol>
     *   <li>There are at least {@value #COMPACTION_THRESHOLD_PAIRS} turn pairs, AND</li>
     *   <li>The total stored history exceeds the history budget (25% of context window)</li>
     * </ol>
     *
     * <p>When triggered, turns that don't fit in the budget are summarized
     * into a sketch, and the old turns are pruned from SessionMemory.
     *
     * @param llm the LLM client to use for summarization (must not be null)
     * @return true if compaction was performed
     */
    public boolean maybeCompact(LlmClient llm) {
        if (llm == null) return false;

        int pairs = turnCount();
        if (pairs < COMPACTION_THRESHOLD_PAIRS) {
            return false;
        }

        int historyBudget = (int) (budget.contextMaxTokens() * HISTORY_BUDGET_FRACTION);
        int totalTokens = estimateHistoryTokens();

        if (totalTokens <= historyBudget) {
            return false; // everything fits, no need to compact
        }

        LOG.info("Compaction triggered: {} pairs, {} tokens > {} budget",
                pairs, totalTokens, historyBudget);

        // Identify which turns don't fit (the "old" ones)
        List<ChatMessage> allTurns = memory.getTurns();
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
            return false; // nothing to compact
        }
        for (int i = 0; i < splitIndex; i++) {
            oldTurns.add(allTurns.get(i));
        }

        if (oldTurns.isEmpty()) {
            return false;
        }

        // Perform compaction
        String newSketch = ConversationCompactor.compact(sketch, oldTurns, llm);
        synchronized (this) {
            sketch = newSketch;
        }

        // Prune old turns from memory
        memory.pruneOldest(oldTurns.size());

        LOG.info("Compaction complete: pruned {} turns, sketch={} chars, remaining {} turns",
                oldTurns.size(), (newSketch != null ? newSketch.length() : 0),
                memory.getTurns().size());

        return true;
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

    /** Check if any conversation history exists. */
    public boolean hasHistory() {
        return memory.hasContent() || (sketch != null && !sketch.isBlank());
    }

    /** Clear all conversation history and sketch. */
    public void clear() {
        memory.clear();
        synchronized (this) {
            sketch = null;
        }
    }

    /** Access the underlying memory (for backward compatibility). */
    public SessionMemory memory() {
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

    /** Set the sketch directly (for testing or restoration). */
    public synchronized void setSketch(String sketch) {
        this.sketch = sketch;
    }
}

