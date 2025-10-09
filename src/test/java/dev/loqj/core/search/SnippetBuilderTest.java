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

    @Test
    void packWithPinned_reservationEnsuresBothFilesIncluded() {
        // Two pinned files with tight budget - reservation should guarantee ≥1 snippet per file
        var pinned = List.of(
                new SnippetBuilder.Snippet("README.md#0", "README content: " + "x".repeat(500)),
                new SnippetBuilder.Snippet("docs/landing.md#0", "Landing page: " + "y".repeat(500))
        );
        var regular = List.of(
                new SnippetBuilder.Snippet("other.md#0", "Other file")
        );

        // Small budget that would normally only fit one pinned snippet
        var packed = SnippetBuilder.packWithPinned(pinned, regular, 600, true);

        // Should include both base files even with tight budget
        assertEquals(2, packed.size(), "Should reserve space for both pinned files");
        assertEquals("README.md#0", packed.get(0).path());
        assertEquals("docs/landing.md#0", packed.get(1).path());
    }

    @Test
    void packWithPinned_reservationOnlyWithExactlyTwoFiles() {
        // Reservation should only activate with exactly 2 distinct base files
        var pinnedOne = List.of(
                new SnippetBuilder.Snippet("README.md#0", "x".repeat(600))
        );
        var pinnedThree = List.of(
                new SnippetBuilder.Snippet("file1.md#0", "a".repeat(300)),
                new SnippetBuilder.Snippet("file2.md#0", "b".repeat(300)),
                new SnippetBuilder.Snippet("file3.md#0", "c".repeat(300))
        );

        // With 1 file, reservation flag should be ignored
        var packedOne = SnippetBuilder.packWithPinned(pinnedOne, List.of(), 600, true);
        assertEquals(1, packedOne.size());

        // With 3 files, reservation flag should be ignored (budget exhausted normally)
        var packedThree = SnippetBuilder.packWithPinned(pinnedThree, List.of(), 600, true);
        assertEquals(2, packedThree.size(), "Should fit only 2 snippets with budget");
    }

    @Test
    void packWithPinned_reservationWithMultipleChunksPerFile() {
        // Multiple chunks from same base file - reservation should count base files
        var pinned = List.of(
                new SnippetBuilder.Snippet("README.md#0", "x".repeat(300)),
                new SnippetBuilder.Snippet("README.md#1", "x".repeat(300)),
                new SnippetBuilder.Snippet("docs/landing.md#0", "y".repeat(300)),
                new SnippetBuilder.Snippet("docs/landing.md#1", "y".repeat(300))
        );

        // Tight budget - should ensure at least one chunk from each of the 2 base files
        var packed = SnippetBuilder.packWithPinned(pinned, List.of(), 400, true);

        // Should have reserved one chunk per base file (2 distinct bases)
        assertTrue(packed.size() >= 2, "Should have at least 2 chunks");

        // Extract base paths
        java.util.Set<String> bases = new java.util.HashSet<>();
        for (var s : packed) {
            String base = s.path().indexOf('#') >= 0
                ? s.path().substring(0, s.path().indexOf('#'))
                : s.path();
            bases.add(base);
        }
        assertEquals(2, bases.size(), "Should include both base files");
        assertTrue(bases.contains("README.md"));
        assertTrue(bases.contains("docs/landing.md"));
    }

    @Test
    void packWithPinned_noReservationWhenFlagIsFalse() {
        // Without reservation flag, tight budget may exclude one file
        var pinned = List.of(
                new SnippetBuilder.Snippet("README.md#0", "x".repeat(500)),
                new SnippetBuilder.Snippet("docs/landing.md#0", "y".repeat(500))
        );

        // Small budget with reservation disabled
        var packed = SnippetBuilder.packWithPinned(pinned, List.of(), 600, false);

        // May only fit first snippet (no guarantee of both files)
        assertTrue(packed.size() >= 1, "Should have at least 1 snippet");
        assertEquals("README.md#0", packed.get(0).path(), "First pinned should be included");
    }
}
