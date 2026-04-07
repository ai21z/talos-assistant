package dev.talos.core.context;

import dev.talos.cli.repl.SessionMemory;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ConversationManager}: budget-aware conversation
 * history management.
 */
class ConversationManagerTest {

    @Test
    void constructorRejectsNulls() {
        assertThrows(NullPointerException.class,
                () -> new ConversationManager(null, new TokenBudget()));
        assertThrows(NullPointerException.class,
                () -> new ConversationManager(new SessionMemory(), null));
    }

    @Test
    void addTurnDelegatesToMemory() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory);

        cm.addTurn("hello", "world");

        assertTrue(memory.hasContent());
        List<ChatMessage> turns = memory.getTurns();
        assertEquals(2, turns.size());
        assertEquals("user", turns.get(0).role());
        assertEquals("hello", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
        assertEquals("world", turns.get(1).content());
    }

    @Test
    void addTurnIgnoresNullAndBlank() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory);

        cm.addTurn(null, "response");
        cm.addTurn("input", null);
        cm.addTurn("input", "   ");

        assertFalse(memory.hasContent());
        assertEquals(0, cm.turnCount());
    }

    @Test
    void buildHistoryReturnsEmptyWhenNoTurns() {
        var cm = new ConversationManager(new SessionMemory());
        List<ChatMessage> history = cm.buildHistory(1000);
        assertTrue(history.isEmpty());
    }

    @Test
    void buildHistoryReturnsAllTurnsWithinBudget() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory, new TokenBudget(8192));

        cm.addTurn("short q1", "short a1");
        cm.addTurn("short q2", "short a2");

        // Budget is large enough for all turns
        List<ChatMessage> history = cm.buildHistory(10_000);
        assertEquals(4, history.size());
        assertEquals("short q1", history.get(0).content());
        assertEquals("short a1", history.get(1).content());
        assertEquals("short q2", history.get(2).content());
        assertEquals("short a2", history.get(3).content());
    }

    @Test
    void buildHistoryTruncatesOldestWhenOverBudget() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory, new TokenBudget(8192));

        // Add many turns with known sizes
        cm.addTurn("q1-" + "x".repeat(100), "a1-" + "x".repeat(100));
        cm.addTurn("q2-" + "x".repeat(100), "a2-" + "x".repeat(100));
        cm.addTurn("q3-" + "x".repeat(100), "a3-" + "x".repeat(100));

        // Budget for ~1 pair only (each pair is ~200 chars = ~50 tokens)
        List<ChatMessage> history = cm.buildHistory(55);
        assertEquals(2, history.size(), "Only the most recent pair should fit");
        assertTrue(history.get(0).content().startsWith("q3-"),
                "Most recent pair should be kept: " + history.get(0).content());
    }

    @Test
    void buildHistoryPreservesChronologicalOrder() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory, new TokenBudget(8192));

        cm.addTurn("first", "reply-1");
        cm.addTurn("second", "reply-2");
        cm.addTurn("third", "reply-3");

        // Budget enough for 2 pairs
        List<ChatMessage> history = cm.buildHistory(200);
        // Should include the 2 most recent pairs in chronological order
        assertTrue(history.size() >= 2);
        // Check ordering: earlier pair before later pair
        int secondIdx = -1, thirdIdx = -1;
        for (int i = 0; i < history.size(); i++) {
            if ("second".equals(history.get(i).content())) secondIdx = i;
            if ("third".equals(history.get(i).content())) thirdIdx = i;
        }
        if (secondIdx >= 0 && thirdIdx >= 0) {
            assertTrue(secondIdx < thirdIdx,
                    "Second turn should come before third turn in chronological order");
        }
    }

    @Test
    void buildHistoryZeroBudgetReturnsEmpty() {
        var memory = new SessionMemory();
        memory.update("q", "a");
        var cm = new ConversationManager(memory, new TokenBudget());

        assertEquals(List.of(), cm.buildHistory(0));
        assertEquals(List.of(), cm.buildHistory(-1));
    }

    @Test
    void buildHistoryDefaultUsesContextFraction() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory, new TokenBudget(8192));

        cm.addTurn("q1", "a1");

        // Default buildHistory() uses 25% of 8192 = 2048 tokens
        // A short pair easily fits
        List<ChatMessage> history = cm.buildHistory();
        assertEquals(2, history.size());
    }

    @Test
    void estimateHistoryTokens() {
        var memory = new SessionMemory();
        var budget = new TokenBudget();
        var cm = new ConversationManager(memory, budget);

        assertEquals(0, cm.estimateHistoryTokens());

        cm.addTurn("hello world", "goodbye world"); // ~11+13 chars = ~6 tokens
        assertTrue(cm.estimateHistoryTokens() > 0);
    }

    @Test
    void turnCount() {
        var cm = new ConversationManager(new SessionMemory());
        assertEquals(0, cm.turnCount());

        cm.addTurn("q1", "a1");
        assertEquals(1, cm.turnCount());

        cm.addTurn("q2", "a2");
        assertEquals(2, cm.turnCount());
    }

    @Test
    void hasHistory() {
        var cm = new ConversationManager(new SessionMemory());
        assertFalse(cm.hasHistory());

        cm.addTurn("q", "a");
        assertTrue(cm.hasHistory());
    }

    @Test
    void clearResetsEverything() {
        var cm = new ConversationManager(new SessionMemory());
        cm.addTurn("q", "a");
        assertTrue(cm.hasHistory());

        cm.clear();
        assertFalse(cm.hasHistory());
        assertEquals(0, cm.turnCount());
        assertTrue(cm.buildHistory(10_000).isEmpty());
    }

    @Test
    void accessors() {
        var memory = new SessionMemory();
        var budget = new TokenBudget(4096);
        var cm = new ConversationManager(memory, budget);

        assertSame(memory, cm.memory());
        assertSame(budget, cm.budget());
    }

    // ───── P0: static estimateTokens for budget coordination ─────

    @Test
    void staticEstimateTokens_matchesBudgetEstimation() {
        var budget = new TokenBudget();
        var history = List.of(
                ChatMessage.user("hello world"),       // 11 chars -> 2 tokens
                ChatMessage.assistant("goodbye world") // 13 chars -> 3 tokens
        );
        int estimated = ConversationManager.estimateTokens(history, budget);
        assertEquals(2 + 3, estimated);
    }

    @Test
    void staticEstimateTokens_nullAndEmptyReturnZero() {
        var budget = new TokenBudget();
        assertEquals(0, ConversationManager.estimateTokens(null, budget));
        assertEquals(0, ConversationManager.estimateTokens(List.of(), budget));
        assertEquals(0, ConversationManager.estimateTokens(List.of(ChatMessage.user("hi")), null));
    }

    @Test
    void buildHistoryTokenCount_matchesStaticEstimate() {
        var memory = new SessionMemory();
        var budget = new TokenBudget(8192);
        var cm = new ConversationManager(memory, budget);

        cm.addTurn("question one", "answer one");
        cm.addTurn("question two", "answer two");

        List<ChatMessage> history = cm.buildHistory();
        int estimated = ConversationManager.estimateTokens(history, budget);

        assertTrue(estimated > 0, "Non-empty history should have positive token estimate");
        // The static method should give the same result as estimating each message individually
        int manual = 0;
        for (ChatMessage msg : history) {
            manual += budget.estimateTokens(msg.content());
        }
        assertEquals(manual, estimated);
    }
}

