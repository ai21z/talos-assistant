package dev.loqj.core.search;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for snippet packing with per-file reservation for two-file comparisons.
 */
public class SnippetPackingReservationTest {

    @Test
    public void testReservationWithTwoFiles() {
        // Create two pinned files with chunks
        List<SnippetBuilder.Snippet> pinned = List.of(
            new SnippetBuilder.Snippet("README.md#0", "x".repeat(100)),
            new SnippetBuilder.Snippet("docs/landing.md#0", "y".repeat(100))
        );

        // Create regular snippets
        List<SnippetBuilder.Snippet> regular = List.of(
            new SnippetBuilder.Snippet("other.txt#0", "z".repeat(50))
        );

        // Pack with small budget and reservation enabled
        List<SnippetBuilder.Snippet> packed = SnippetBuilder.packWithPinned(pinned, regular, 300, true);

        // Should have at least one snippet from each pinned file
        long readmeCount = packed.stream().filter(s -> s.path().startsWith("README.md")).count();
        long landingCount = packed.stream().filter(s -> s.path().startsWith("docs/landing.md")).count();

        assertTrue(readmeCount >= 1, "Should reserve at least one snippet for README.md");
        assertTrue(landingCount >= 1, "Should reserve at least one snippet for docs/landing.md");
    }

    @Test
    public void testNoReservationWithOneFile() {
        // Only one pinned file
        List<SnippetBuilder.Snippet> pinned = List.of(
            new SnippetBuilder.Snippet("README.md#0", "x".repeat(100))
        );

        List<SnippetBuilder.Snippet> regular = List.of(
            new SnippetBuilder.Snippet("other.txt#0", "y".repeat(100))
        );

        // Reservation should not apply with only one file
        List<SnippetBuilder.Snippet> packed = SnippetBuilder.packWithPinned(pinned, regular, 150, true);

        // Should prioritize pinned but not apply special reservation logic
        assertTrue(packed.size() >= 1, "Should include at least pinned file");
        assertTrue(packed.get(0).path().startsWith("README.md"), "Should prioritize pinned");

        // Verify total stays within budget
        int totalChars = packed.stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(totalChars <= 150, "Should respect budget");
    }

    @Test
    public void testReservationWithMultipleChunksFromSameFile() {
        // Two chunks from same file should count as one base file
        List<SnippetBuilder.Snippet> pinned = List.of(
            new SnippetBuilder.Snippet("README.md#0", "x".repeat(100)),
            new SnippetBuilder.Snippet("README.md#1", "y".repeat(100)),
            new SnippetBuilder.Snippet("docs/landing.md#0", "z".repeat(100))
        );

        List<SnippetBuilder.Snippet> regular = List.of();

        // Should identify exactly 2 base files
        List<SnippetBuilder.Snippet> packed = SnippetBuilder.packWithPinned(pinned, regular, 250, true);

        long readmeCount = packed.stream().filter(s -> s.path().startsWith("README.md")).count();
        long landingCount = packed.stream().filter(s -> s.path().startsWith("docs/landing.md")).count();

        assertTrue(readmeCount >= 1, "Should reserve at least one README chunk");
        assertTrue(landingCount >= 1, "Should reserve at least one landing chunk");
    }

    @Test
    public void testDeduplicationByPath() {
        List<SnippetBuilder.Snippet> pinned = List.of(
            new SnippetBuilder.Snippet("README.md#0", "content1")
        );

        // Same path in regular list
        List<SnippetBuilder.Snippet> regular = List.of(
            new SnippetBuilder.Snippet("README.md#0", "content2"),
            new SnippetBuilder.Snippet("other.txt#0", "content3")
        );

        List<SnippetBuilder.Snippet> packed = SnippetBuilder.packWithPinned(pinned, regular, 1000, false);

        // Should have unique paths only (first occurrence wins)
        assertEquals(2, packed.size(), "Should deduplicate by path");
        assertEquals("content1", packed.get(0).text(), "Pinned version should win");
        assertEquals("content3", packed.get(1).text(), "Other file should be included");
    }

    @Test
    public void testBudgetEnforcement() {
        List<SnippetBuilder.Snippet> pinned = List.of(
            new SnippetBuilder.Snippet("file1.txt#0", "a".repeat(100))
        );

        List<SnippetBuilder.Snippet> regular = List.of(
            new SnippetBuilder.Snippet("file2.txt#0", "b".repeat(100))
        );

        // Tight budget
        List<SnippetBuilder.Snippet> packed = SnippetBuilder.packWithPinned(pinned, regular, 120, false);

        int totalChars = packed.stream().mapToInt(s -> s.text().length()).sum();
        assertTrue(totalChars <= 120, "Should respect budget");
    }
}
