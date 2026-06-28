package dev.talos.core.context;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link TokenBudget} - token estimation and budget allocation.
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

    // ───── P0: history-aware budget coordination ─────

    @Test
    void availableForSnippets_deductsHistoryTokens() {
        // 1000 tokens total, 30% response reserve = 300, overhead = 50
        var budget = new TokenBudget(1000, 0.30, 50);
        // system = 80 chars -> 20 tokens, query = 40 chars -> 10 tokens
        int withoutHistory = budget.availableForSnippets("x".repeat(80), "y".repeat(40), 0);
        int withHistory    = budget.availableForSnippets("x".repeat(80), "y".repeat(40), 200);
        // Without history: 1000 - 20 - 10 - 300 - 50 = 620
        assertEquals(620, withoutHistory);
        // With history:    1000 - 20 - 10 - 200 - 300 - 50 = 420
        assertEquals(420, withHistory);
        assertEquals(200, withoutHistory - withHistory, "Difference should equal historyTokens");
    }

    @Test
    void availableForSnippets_twoArgDelegatesToThreeArgWithZeroHistory() {
        var budget = new TokenBudget(1000, 0.30, 50);
        String sys = "x".repeat(80);
        String q = "y".repeat(40);
        assertEquals(
                budget.availableForSnippets(sys, q, 0),
                budget.availableForSnippets(sys, q),
                "Two-arg form should equal three-arg with historyTokens=0");
    }

    @Test
    void availableForSnippets_negativeHistoryIsTreatedAsZero() {
        var budget = new TokenBudget(1000, 0.30, 50);
        String sys = "x".repeat(80);
        String q = "y".repeat(40);
        assertEquals(
                budget.availableForSnippets(sys, q, 0),
                budget.availableForSnippets(sys, q, -100),
                "Negative historyTokens should be clamped to 0");
    }

    @Test
    void availableForSnippets_historyOverflowReturnsZero() {
        var budget = new TokenBudget(1000, 0.30, 50);
        // Giant history that exceeds the full budget
        int available = budget.availableForSnippets("x".repeat(80), "y".repeat(40), 9999);
        assertEquals(0, available, "Should clamp to 0 when history overflows budget");
    }

    @Test
    void availableForSnippets_fullBudgetLayout_sumsCorrectly() {
        // Verify system + query + history + snippets + overhead + response <= contextMaxTokens
        int ctxMax = 8192;
        var budget = new TokenBudget(ctxMax, 0.30, 100);
        String sys = "x".repeat(800);  // 200 tokens
        String q = "y".repeat(160);    // 40 tokens
        int historyTokens = 500;

        int snippetTokens = budget.availableForSnippets(sys, q, historyTokens);
        int responseReserve = (int) (ctxMax * 0.30);

        int total = budget.estimateTokens(sys) + budget.estimateTokens(q)
                  + historyTokens + snippetTokens + 100 + responseReserve;
        assertEquals(ctxMax, total, "All components should exactly fill the context window");
    }
}

