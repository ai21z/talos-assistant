package dev.talos.core.context;

import dev.talos.cli.repl.SessionMemory;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration-style tests for the P0 budget coordination fix.
 *
 * <p>Verifies that the full flow — build history → measure tokens →
 * pack snippets with history deduction → assemble messages — keeps
 * the total estimated tokens within the configured context window.
 *
 * <p>Before this fix, history tokens were allocated independently
 * (25% of context) and not deducted from the snippet budget, causing
 * the assembled context to exceed the model's context window.
 */
@DisplayName("P0 — Budget Coordination: history + snippets within context window")
class BudgetCoordinationTest {

    /**
     * Simulates the full RagMode flow:
     * 1. Build history from ConversationManager
     * 2. Measure its token cost
     * 3. Pack snippets with history deduction
     * 4. Assert total (system + query + history + snippets) ≤ contextMaxTokens
     */
    @Test
    void fullFlow_totalTokensStayWithinBudget() {
        int contextMax = 1024;
        var budget = new TokenBudget(contextMax, 0.30, 100);

        // Simulate conversation history
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory, budget);
        cm.addTurn("What is dependency injection?", "DI is a design pattern where dependencies are provided externally rather than created internally.");
        cm.addTurn("Give me an example in Java.", "Here is a simple constructor injection example using Spring framework annotations.");

        // Step 1: Build history
        List<ChatMessage> history = cm.buildHistory();
        assertFalse(history.isEmpty(), "Should have conversation history");

        // Step 2: Measure history tokens
        int historyTokens = ConversationManager.estimateTokens(history, budget);
        assertTrue(historyTokens > 0);

        // Step 3: Pack snippets with history deduction
        String system = "You are Talos, a local-first knowledge assistant. " +
                "Answer clearly and concisely using the provided context.";
        String query = "Now explain how it works with Spring Boot auto-configuration?";

        var snippets = List.of(
                new ContextResult.Snippet("SpringBoot.java#0", "x".repeat(800)),
                new ContextResult.Snippet("AutoConfig.java#0", "y".repeat(800)),
                new ContextResult.Snippet("DI-Guide.md#0", "z".repeat(800))
        );

        var packer = new ContextPacker(budget);
        ContextResult packed = packer.pack(system, query, historyTokens, List.of(), snippets, false);

        // Step 4: Verify total does not exceed budget
        // Use raw char/4 for snippet tokens (what the packer's char budget enforces),
        // NOT estimateSnippetTokens which adds per-snippet structural overhead.
        int systemTokens = budget.estimateTokens(system);
        int queryTokens = budget.estimateTokens(query);
        int snippetCharTotal = packed.snippets().stream()
                .mapToInt(s -> s.text().length())
                .sum();
        int snippetTokens = snippetCharTotal / 4;
        int responseReserve = (int) (contextMax * budget.responseReserveFraction());

        int totalBeforeResponse = systemTokens + queryTokens + historyTokens + snippetTokens + budget.overheadTokens();
        int totalWithResponse = totalBeforeResponse + responseReserve;

        assertTrue(totalWithResponse <= contextMax,
                "Total with response (" + totalWithResponse + ") should not exceed contextMax (" + contextMax + ")"
                        + " [system=" + systemTokens + ", query=" + queryTokens
                        + ", history=" + historyTokens + ", snippets=" + snippetTokens
                        + ", overhead=" + budget.overheadTokens() + ", response=" + responseReserve + "]");

        // History should have reduced the snippet budget compared to no-history
        ContextResult noHistoryPack = packer.pack(system, query, 0, List.of(), snippets, false);
        int noHistoryChars = noHistoryPack.snippets().stream().mapToInt(s -> s.text().length()).sum();
        int withHistoryChars = packed.snippets().stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(withHistoryChars <= noHistoryChars,
                "History should reduce snippet space: noHistory=" + noHistoryChars
                        + ", withHistory=" + withHistoryChars);
    }

    /**
     * Verifies that without history, more snippet space is available.
     */
    @Test
    void noHistory_getsFullSnippetBudget() {
        int contextMax = 2048;
        var budget = new TokenBudget(contextMax, 0.30, 100);
        String system = "You are a helpful assistant.";
        String query = "How does X work?";

        var snippets = List.of(
                new ContextResult.Snippet("A.java#0", "a".repeat(600)),
                new ContextResult.Snippet("B.java#0", "b".repeat(600))
        );

        var packer = new ContextPacker(budget);
        ContextResult noHistoryResult = packer.pack(system, query, 0, List.of(), snippets, false);
        ContextResult withHistoryResult = packer.pack(system, query, 300, List.of(), snippets, false);

        int charsNoHistory = noHistoryResult.snippets().stream().mapToInt(s -> s.text().length()).sum();
        int charsWithHistory = withHistoryResult.snippets().stream().mapToInt(s -> s.text().length()).sum();

        assertTrue(charsNoHistory >= charsWithHistory,
                "No-history should pack at least as many chars: noHistory=" + charsNoHistory
                        + ", withHistory=" + charsWithHistory);
    }

    /**
     * Edge case: history consumes almost the entire budget,
     * leaving very little for snippets.
     */
    @Test
    void hugeHistory_leavesMinimalSnippetSpace() {
        int contextMax = 1024;
        var budget = new TokenBudget(contextMax, 0.30, 50);
        String system = "system";
        String query = "query";

        // History that consumes most of the non-reserved space
        // contextMax=1024, response=307, overhead=50, system≈1, query≈1
        // Available for snippets+history = 1024 - 1 - 1 - 307 - 50 = 665
        int historyTokens = 600; // leaves only 65 tokens for snippets → 260 chars

        var snippets = List.of(
                new ContextResult.Snippet("Big.java#0", "x".repeat(2000))
        );

        var packer = new ContextPacker(budget);
        ContextResult result = packer.pack(system, query, historyTokens, List.of(), snippets, false);

        int snippetChars = result.snippets().stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(snippetChars <= 260,
                "With 600 history tokens, snippets should be heavily trimmed: got " + snippetChars + " chars");
        assertTrue(result.wasTrimmed(), "Should be trimmed");
    }

    /**
     * Verifies the old (pre-fix) scenario would have overflowed.
     * Demonstrates the bug: if history tokens are NOT deducted,
     * total exceeds context window.
     */
    @Test
    void preFixScenario_wouldOverflowWithoutCoordination() {
        int contextMax = 2048;
        var budget = new TokenBudget(contextMax, 0.30, 100);
        String system = "x".repeat(400); // 100 tokens
        String query = "y".repeat(80);   // 20 tokens

        // Simulate ConversationManager's 25% allocation for history
        int historyTokens = (int) (contextMax * 0.25); // 512 tokens

        // WITHOUT history deduction (the old bug)
        int snippetsOldBug = budget.availableForSnippets(system, query, 0);
        // WITH history deduction (the fix)
        int snippetsFix = budget.availableForSnippets(system, query, historyTokens);

        // Old bug: system(100) + query(20) + history(512) + snippets(snippetsOldBug) + overhead(100) + response(614)
        int totalOldBug = 100 + 20 + historyTokens + snippetsOldBug + 100 + (int)(contextMax * 0.30);
        // Fix: system(100) + query(20) + history(512) + snippets(snippetsFix) + overhead(100) + response(614)
        int totalFix = 100 + 20 + historyTokens + snippetsFix + 100 + (int)(contextMax * 0.30);

        assertTrue(totalOldBug > contextMax,
                "Pre-fix total (" + totalOldBug + ") should exceed budget — this was the bug");
        assertTrue(totalFix <= contextMax,
                "Fixed total (" + totalFix + ") should stay within budget");
    }
}




