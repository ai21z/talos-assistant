package dev.loqj.core.context;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies the invariant: every citation in the packed {@link ContextResult}
 * corresponds to a snippet the model will actually see.
 */
class PackedCitationFidelityTest {

    private static final String SYS = "You are a helpful assistant.";
    private static final String Q   = "What does this do?";

    @Test
    void packed_citations_match_packed_snippet_base_paths() {
        var packer = new ContextPacker(new TokenBudget(100_000));
        var regular = List.of(
                snip("src/Foo.java#0", "Foo content"),
                snip("src/Bar.java#0", "Bar content"),
                snip("src/Baz.java#1", "Baz content")
        );

        ContextResult result = packer.pack(SYS, Q, List.of(), regular);

        Set<String> citedPaths = new HashSet<>(result.citations());
        Set<String> snippetBases = result.snippets().stream()
                .map(s -> stripChunkId(s.path()))
                .collect(Collectors.toSet());

        assertEquals(snippetBases, citedPaths,
                "Citations should exactly match base paths of packed snippets");
    }

    @Test
    void tight_budget_drops_snippets_and_citations_stay_aligned() {
        // TokenBudget clamps min contextMaxTokens to 256.
        // With 0.30 response reserve (76 tokens) + 50 overhead + ~11 system/query tokens
        // → available ≈ 119 tokens → 476 chars for snippets.
        // Three 300-char snippets (900 total) cannot all fit;
        // the third will be dropped entirely.
        var budget = new TokenBudget(256, 0.30, 50);
        var packer = new ContextPacker(budget);

        var regular = List.of(
                snip("src/Keep.java#0", "x".repeat(300)),
                snip("src/Maybe.java#0", "y".repeat(300)),
                snip("src/Drop.java#0", "z".repeat(300))
        );

        ContextResult result = packer.pack(SYS, Q, List.of(), regular);

        assertTrue(result.wasTrimmed(), "Expected budget trimming");
        assertTrue(result.finalCount() < 3,
                "Expected fewer than 3 packed snippets, got " + result.finalCount());

        // Every citation corresponds to a packed snippet
        Set<String> snippetBases = result.snippets().stream()
                .map(s -> stripChunkId(s.path()))
                .collect(Collectors.toSet());
        for (String citation : result.citations()) {
            assertTrue(snippetBases.contains(citation),
                    "Citation '" + citation + "' has no corresponding packed snippet");
        }
        // Every packed snippet has a citation
        for (String base : snippetBases) {
            assertTrue(result.citations().contains(base),
                    "Packed snippet base '" + base + "' missing from citations");
        }
    }

    @Test
    void pinned_plus_regular_citations_only_reflect_packed() {
        // Same 256-token minimum; pinned is first priority
        var budget = new TokenBudget(256, 0.30, 50);
        var packer = new ContextPacker(budget);

        var pinned = List.of(snip("pin/A.java#0", "pinned A " + "a".repeat(200)));
        var regular = List.of(
                snip("reg/B.java#0", "b".repeat(200)),
                snip("reg/C.java#0", "c".repeat(500))
        );

        ContextResult result = packer.pack(SYS, Q, pinned, regular);

        assertFalse(result.snippets().isEmpty());

        Set<String> citedPaths = new HashSet<>(result.citations());
        Set<String> snippetBases = result.snippets().stream()
                .map(s -> stripChunkId(s.path()))
                .collect(Collectors.toSet());

        assertEquals(snippetBases, citedPaths,
                "Packed citations should match packed snippet base paths exactly");
        assertTrue(citedPaths.contains("pin/A.java"),
                "Pinned snippet should always survive and be cited");
    }

    @Test
    void multiple_chunks_same_file_produce_single_citation() {
        var packer = new ContextPacker(new TokenBudget(100_000));
        var regular = List.of(
                snip("src/Foo.java#0", "chunk 0"),
                snip("src/Foo.java#1", "chunk 1"),
                snip("src/Foo.java#2", "chunk 2"),
                snip("src/Bar.java#0", "bar chunk")
        );

        ContextResult result = packer.pack(SYS, Q, List.of(), regular);

        assertEquals(4, result.finalCount());
        assertEquals(2, result.citations().size(), "Two base files -> two citations");
        assertTrue(result.citations().contains("src/Foo.java"));
        assertTrue(result.citations().contains("src/Bar.java"));
    }

    @Test
    void empty_input_produces_empty_citations() {
        var packer = new ContextPacker(new TokenBudget(100_000));
        ContextResult result = packer.pack(SYS, Q, List.of(), List.of());

        assertTrue(result.snippets().isEmpty());
        assertTrue(result.citations().isEmpty());
        assertFalse(result.wasTrimmed());
    }

    @Test
    void dedup_across_pinned_and_regular_keeps_pinned_version() {
        var packer = new ContextPacker(new TokenBudget(100_000));
        var pinned = List.of(snip("src/X.java#0", "pinned version of X"));
        var regular = List.of(
                snip("src/X.java#0", "regular version of X"),
                snip("src/Y.java#0", "Y content")
        );

        ContextResult result = packer.pack(SYS, Q, pinned, regular);

        assertEquals(2, result.finalCount());
        assertEquals("pinned version of X", result.snippets().get(0).text());
        assertTrue(result.citations().contains("src/X.java"));
        assertTrue(result.citations().contains("src/Y.java"));
    }

    // ──── helpers ────

    private static ContextResult.Snippet snip(String path, String text) {
        return new ContextResult.Snippet(path, text);
    }

    private static String stripChunkId(String path) {
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }
}
