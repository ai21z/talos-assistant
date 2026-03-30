package dev.loqj.core.context;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ContextPacker} — unified context assembly.
 */
class ContextPackerTest {

    // Large budget so packing is not budget-constrained unless we want it to be
    private static final TokenBudget BIG_BUDGET = new TokenBudget(100_000);
    private static final String SYS = "You are a helpful assistant.";
    private static final String QUERY = "What does Foo do?";

    @Test
    void pack_pinnedFirst_thenRegular() {
        var packer = new ContextPacker(BIG_BUDGET);
        var pinned = List.of(snip("A.java#0", "pinned content"));
        var regular = List.of(snip("B.java#0", "regular content"));

        ContextResult result = packer.pack(SYS, QUERY, pinned, regular);

        assertEquals(2, result.finalCount());
        assertEquals("A.java#0", result.snippets().get(0).path());
        assertEquals("B.java#0", result.snippets().get(1).path());
        assertFalse(result.wasTrimmed());
    }

    @Test
    void pack_deduplicatesByPath() {
        var packer = new ContextPacker(BIG_BUDGET);
        var pinned = List.of(snip("X.java#0", "v1"));
        var regular = List.of(snip("X.java#0", "v2"), snip("Y.java#0", "other"));

        ContextResult result = packer.pack(SYS, QUERY, pinned, regular);

        assertEquals(2, result.finalCount());
        // Pinned version wins
        assertEquals("v1", result.snippets().get(0).text());
        assertEquals("Y.java#0", result.snippets().get(1).path());
        assertTrue(result.wasTrimmed()); // 3 original -> 2 final
    }

    @Test
    void pack_respectsCharacterBudget() {
        // Very tight budget: 500 tokens total, 30% response = 150, overhead = 100
        // system ≈ 7 tokens, query ≈ 4 tokens → available ≈ 239 tokens → 956 chars
        var budget = new TokenBudget(500, 0.30, 100);
        var packer = new ContextPacker(budget);

        var pinned = List.of(snip("A.java#0", "x".repeat(500)));
        var regular = List.of(
                snip("B.java#0", "y".repeat(500)),
                snip("C.java#0", "z".repeat(500))
        );

        ContextResult result = packer.pack(SYS, QUERY, pinned, regular);

        // Should fit pinned + part of first regular but not all three
        assertTrue(result.finalCount() < 3);
        assertTrue(result.wasTrimmed());
        // Total chars should not exceed budget
        int totalChars = result.snippets().stream().mapToInt(s -> s.text().length()).sum();
        int charBudget = budget.tokensToChars(budget.availableForSnippets(SYS, QUERY));
        assertTrue(totalChars <= charBudget, "totalChars=" + totalChars + " > charBudget=" + charBudget);
    }

    @Test
    void pack_reservationEnsuresBothBaseFilesPresent() {
        var packer = new ContextPacker(BIG_BUDGET);
        // Two base files, each with multiple chunks
        var pinned = List.of(
                snip("README.md#0", "x".repeat(100)),
                snip("README.md#1", "x".repeat(100)),
                snip("docs/landing.md#0", "y".repeat(100))
        );
        List<ContextResult.Snippet> regular = List.of();

        ContextResult result = packer.pack(SYS, QUERY, pinned, regular, true);

        // Both base files should have at least one snippet
        Set<String> bases = result.snippets().stream()
                .map(s -> s.path().contains("#") ? s.path().substring(0, s.path().indexOf('#')) : s.path())
                .collect(Collectors.toSet());
        assertTrue(bases.contains("README.md"), "README.md should be present");
        assertTrue(bases.contains("docs/landing.md"), "docs/landing.md should be present");
    }

    @Test
    void pack_reservationOnlyWithExactlyTwoBaseFiles() {
        var packer = new ContextPacker(BIG_BUDGET);
        // Only one base file — reservation has no special effect
        var pinned = List.of(snip("A.java#0", "content"));

        ContextResult result = packer.pack(SYS, QUERY, pinned, List.of(), true);

        assertEquals(1, result.finalCount());
    }

    @Test
    void pack_emptyInputs() {
        var packer = new ContextPacker(BIG_BUDGET);

        ContextResult result = packer.pack(SYS, QUERY, List.of(), List.of());

        assertTrue(result.isEmpty());
        assertEquals(0, result.originalCount());
        assertEquals(0, result.finalCount());
        assertFalse(result.wasTrimmed());
    }

    @Test
    void pack_nullInputsHandledGracefully() {
        var packer = new ContextPacker(BIG_BUDGET);

        ContextResult result = packer.pack(SYS, QUERY, null, null);

        assertTrue(result.isEmpty());
    }

    @Test
    void pack_citationsAreDeduplicatedBaseFiles() {
        var packer = new ContextPacker(BIG_BUDGET);
        var pinned = List.of(
                snip("Foo.java#0", "chunk1"),
                snip("Foo.java#1", "chunk2")
        );
        var regular = List.of(snip("Bar.java#0", "bar"));

        ContextResult result = packer.pack(SYS, QUERY, pinned, regular);

        // Citations should be base files only, no duplicates
        assertEquals(List.of("Foo.java", "Bar.java"), result.citations());
    }

    @Test
    void pack_toSnippetMaps_producesCorrectFormat() {
        var packer = new ContextPacker(BIG_BUDGET);
        var pinned = List.of(snip("A.java#0", "content A"));

        ContextResult result = packer.pack(SYS, QUERY, pinned, List.of());

        var maps = result.toSnippetMaps();
        assertEquals(1, maps.size());
        assertEquals("A.java#0", maps.get(0).get("path"));
        assertEquals("content A", maps.get(0).get("text"));
    }

    @Test
    void pack_provenanceMetadata_isAccurate() {
        var budget = new TokenBudget(1000);
        var packer = new ContextPacker(budget);
        var regular = List.of(
                snip("A.java#0", "a".repeat(100)),
                snip("B.java#0", "b".repeat(100))
        );

        ContextResult result = packer.pack(SYS, QUERY, List.of(), regular);

        assertEquals(2, result.originalCount());
        assertEquals(2, result.finalCount());
        assertEquals(1000, result.budgetTokens());
        assertTrue(result.estimatedTokens() > 0);
        assertTrue(result.utilization() > 0.0);
        assertTrue(result.utilization() < 1.0);
    }

    // ───── helper ─────

    private static ContextResult.Snippet snip(String path, String text) {
        return new ContextResult.Snippet(path, text);
    }
}

