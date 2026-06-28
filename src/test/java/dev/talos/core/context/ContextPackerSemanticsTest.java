package dev.talos.core.context;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the correctness semantics pass:
 * - wasTrimmed is true when text is truncated (not just when snippets are dropped)
 * - wasTrimmed is true when snippets are dropped
 * - wasTrimmed is false when everything fits
 * - packed citations reflect only what survived packing
 */
class ContextPackerSemanticsTest {

    private static final String SYS = "You are a test assistant.";
    private static final String QUERY = "What is X?";

    // ───── wasTrimmed: text truncation without snippet drops ─────

    @Test
    void wasTrimmed_trueWhenTextTruncatedButSnippetCountUnchanged() {
        // Budget so tight that the single snippet's text must be truncated,
        // but the snippet itself is still included (not dropped).
        // 400 tokens total, 30% response = 120, overhead = 100, system ≈ 6, query ≈ 3
        // available ≈ 171 tokens → 684 chars
        var budget = new TokenBudget(400, 0.30, 100);
        var packer = new ContextPacker(budget);

        // Single snippet with 1000 chars - must be truncated to fit 684 chars
        var regular = List.of(snip("A.java#0", "x".repeat(1000)));

        ContextResult result = packer.pack(SYS, QUERY, List.of(), regular);

        assertEquals(1, result.originalCount(), "one snippet in");
        assertEquals(1, result.finalCount(), "one snippet out (not dropped)");
        assertTrue(result.wasTrimmed(), "wasTrimmed must be true: text was truncated");
        assertTrue(result.snippets().get(0).text().length() < 1000,
                "text should have been shortened");
    }

    @Test
    void wasTrimmed_trueWhenSnippetsDropped() {
        // Tiny budget: char budget ~ 288 chars. First snippet fills it, second is dropped.
        var budget = new TokenBudget(300, 0.30, 100);
        var packer = new ContextPacker(budget);

        var regular = List.of(
                snip("A.java#0", "a".repeat(500)),
                snip("B.java#0", "b".repeat(500))
        );

        ContextResult result = packer.pack(SYS, QUERY, List.of(), regular);

        assertTrue(result.finalCount() < result.originalCount(),
                "at least one snippet should have been dropped, finalCount="
                        + result.finalCount() + " originalCount=" + result.originalCount());
        assertTrue(result.wasTrimmed());
    }

    @Test
    void wasTrimmed_falseWhenEverythingFits() {
        var budget = new TokenBudget(100_000);
        var packer = new ContextPacker(budget);

        var regular = List.of(
                snip("A.java#0", "small content"),
                snip("B.java#0", "also small")
        );

        ContextResult result = packer.pack(SYS, QUERY, List.of(), regular);

        assertEquals(2, result.originalCount());
        assertEquals(2, result.finalCount());
        assertFalse(result.wasTrimmed());
    }

    // ───── packed citations vs pre-packed citations ─────

    @Test
    void packedCitations_excludeDroppedSnippets() {
        // Budget: 300 tokens → char budget ≈ 408.
        // Keep.java (500 chars) fills the budget (truncated to 408).
        // Drop.java gets take=0 and is excluded entirely.
        var budget = new TokenBudget(300, 0.30, 100);
        var packer = new ContextPacker(budget);

        var regular = List.of(
                snip("Keep.java#0", "k".repeat(500)),
                snip("Drop.java#0", "d".repeat(500))
        );

        ContextResult result = packer.pack(SYS, QUERY, List.of(), regular);

        // Only Keep.java should appear in citations
        assertTrue(result.citations().contains("Keep.java"),
                "kept snippet's base file should be cited");
        assertFalse(result.citations().contains("Drop.java"),
                "dropped snippet's base file should NOT be cited");
    }

    @Test
    void packedCitations_includeAllWhenNothingDropped() {
        var budget = new TokenBudget(100_000);
        var packer = new ContextPacker(budget);

        var regular = List.of(
                snip("Foo.java#0", "foo"),
                snip("Bar.java#0", "bar")
        );

        ContextResult result = packer.pack(SYS, QUERY, List.of(), regular);

        assertEquals(List.of("Foo.java", "Bar.java"), result.citations());
    }

    // ───── helper ─────

    private static ContextResult.Snippet snip(String path, String text) {
        return new ContextResult.Snippet(path, text);
    }
}

