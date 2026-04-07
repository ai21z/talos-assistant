package dev.talos.core.context;

import dev.talos.cli.repl.SessionMemory;
import dev.talos.spi.types.ChatMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Token-aware conversation history manager.
 *
 * <p>Wraps {@link SessionMemory} with a {@link TokenBudget} to provide
 * budget-aware history retrieval. {@link #buildHistory(int)} returns as
 * many recent turns as fit within the available token budget.
 *
 * <p>Thread-safe: delegates to SessionMemory which synchronizes internally.
 */
public final class ConversationManager {

    private final SessionMemory memory;
    private final TokenBudget budget;

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
     * Returns most recent turns first priority, in chronological order.
     * Turns are kept as user/assistant pairs — never split.
     *
     * @param availableTokens maximum tokens to spend on history
     * @return list of ChatMessage in chronological order
     */
    public List<ChatMessage> buildHistory(int availableTokens) {
        List<ChatMessage> allTurns = memory.getTurns();
        if (allTurns.isEmpty() || availableTokens <= 0) {
            return List.of();
        }

        List<ChatMessage> selected = new ArrayList<>();
        int tokensUsed = 0;

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

        return List.copyOf(selected);
    }

    /** Build history using 25% of context window as default budget. */
    public List<ChatMessage> buildHistory() {
        int historyBudget = (int) (budget.contextMaxTokens() * 0.25);
        return buildHistory(historyBudget);
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
        return memory.hasContent();
    }

    /** Clear all conversation history. */
    public void clear() {
        memory.clear();
    }

    /** Access the underlying memory (for backward compatibility). */
    public SessionMemory memory() {
        return memory;
    }

    /** Access the token budget. */
    public TokenBudget budget() {
        return budget;
    }
}

