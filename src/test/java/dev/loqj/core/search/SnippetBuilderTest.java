package dev.loqj.core.search;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SnippetBuilderTest {

    @Test
    void packWithPinned_dedupesAndKeepsInsertionOrder() {
        // Regular includes a duplicate "A#0" that should be ignored on packing
        List<SnippetBuilder.Snippet> regular = List.of(
                new SnippetBuilder.Snippet("A#0", "alpha"),
                new SnippetBuilder.Snippet("B#0", "bravo"),
                new SnippetBuilder.Snippet("A#0", "alpha"),  // duplicate path → should be ignored
                new SnippetBuilder.Snippet("C#0", "charlie")
        );

        var snippets = SnippetBuilder.packWithPinned(Collections.emptyList(), regular, 1000);

        assertEquals(3, snippets.size(), "Should keep A,B,C exactly once");
        assertEquals("A#0", snippets.get(0).path());
        assertEquals("B#0", snippets.get(1).path());
        assertEquals("C#0", snippets.get(2).path());
        assertEquals("alpha",   snippets.get(0).text());
        assertEquals("bravo",   snippets.get(1).text());
        assertEquals("charlie", snippets.get(2).text());
    }

    @Test
    void packWithPinned_respectsPinnedAndBudget() {
        var pinned  = List.of(new SnippetBuilder.Snippet("X#0", "x".repeat(900)));
        var regular = List.of(
                new SnippetBuilder.Snippet("Y#0", "y".repeat(900)),
                new SnippetBuilder.Snippet("Z#0", "z".repeat(900))
        );

        var merged = SnippetBuilder.packWithPinned(pinned, regular, 1800);

        // Expect pinned first + one regular (budget ≈ 1800; allows slight overflow up to 200, but here it's exact)
        assertEquals(2, merged.size());
        assertEquals("X#0", merged.get(0).path());
        assertEquals("Y#0", merged.get(1).path());
    }
}
