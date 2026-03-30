package dev.loqj.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenBudget} — token estimation and budget allocation.
 */
class TokenBudgetTest {

    @Test
    void estimateTokens_usesCharsDivFour() {
        var budget = new TokenBudget();
        assertEquals(0, budget.estimateTokens(null));
        assertEquals(0, budget.estimateTokens(""));
        assertEquals(25, budget.estimateTokens("x".repeat(100))); // 100/4 = 25
        assertEquals(1, budget.estimateTokens("test"));            // 4/4 = 1
    }

    @Test
    void estimateSnippetTokens_includesOverhead() {
        var budget = new TokenBudget();
        // path="a.java" (6 chars -> 1 token), text="hello world!" (12 chars -> 3 tokens), +20 overhead
        int tokens = budget.estimateSnippetTokens("a.java", "hello world!");
        assertEquals(1 + 3 + 20, tokens);
    }

    @Test
    void availableForSnippets_subtractsAllReservations() {
        // 1000 tokens total, 30% response reserve = 300, overhead = 50
        var budget = new TokenBudget(1000, 0.30, 50);
        // system = 80 chars -> 20 tokens, query = 40 chars -> 10 tokens
        int available = budget.availableForSnippets("x".repeat(80), "y".repeat(40));
        // 1000 - 20 - 10 - 300 - 50 = 620
        assertEquals(620, available);
    }

    @Test
    void availableForSnippets_returnsZeroWhenOverBudget() {
        // Tiny budget of 256, large system prompt
        var budget = new TokenBudget(256, 0.30, 100);
        // system = 1000 chars -> 250 tokens (already > 256 - reserve)
        int available = budget.availableForSnippets("x".repeat(1000), "query");
        assertEquals(0, available);
    }

    @Test
    void tokensToChars_inversesEstimate() {
        var budget = new TokenBudget();
        assertEquals(400, budget.tokensToChars(100));
    }

    @Test
    void contextMaxTokens_clampsToMinimum() {
        var budget = new TokenBudget(10);
        assertEquals(256, budget.contextMaxTokens()); // minimum clamp
    }

    @Test
    void responseReserveFraction_clamps() {
        var low = new TokenBudget(1000, -0.5, 0);
        assertEquals(0.0, low.responseReserveFraction());

        var high = new TokenBudget(1000, 1.5, 0);
        assertEquals(0.9, high.responseReserveFraction());
    }

    @Test
    void defaults_areReasonable() {
        var budget = new TokenBudget();
        assertEquals(TokenBudget.DEFAULT_CONTEXT_MAX_TOKENS, budget.contextMaxTokens());
        assertEquals(TokenBudget.DEFAULT_RESPONSE_RESERVE, budget.responseReserveFraction());
        assertEquals(TokenBudget.DEFAULT_OVERHEAD_TOKENS, budget.overheadTokens());
    }
}

