package dev.talos.core.rag;

import dev.talos.core.context.ContextPacker;
import dev.talos.core.context.ContextResult;
import dev.talos.spi.types.ChunkMetadata;
import dev.talos.core.retrieval.RetrievalTrace;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link RagService.Prepared} — verifies trace exposure,
 * backwards-compatible constructors, and snippet accessors.
 */
class PreparedTraceTest {

    @Test
    void prepared_withTrace_exposesTrace() {
        var trace = new RetrievalTrace();
        trace.record("bm25", 1_000_000L, 0, 3, null);
        trace.record("knn", 500_000L, 3, 3, "skipped: no query vector");

        var snippets = List.of(
                new ContextResult.Snippet("a.java#0", "content a"),
                new ContextResult.Snippet("b.java#0", "content b")
        );
        var citations = List.of("a.java", "b.java");

        var prepared = new RagService.Prepared(snippets, citations, trace);

        assertNotNull(prepared.trace());
        assertEquals(2, prepared.trace().entries().size());
        assertEquals("bm25", prepared.trace().entries().get(0).stageName());
        assertTrue(prepared.trace().entries().get(1).wasSkipped());
    }

    @Test
    void prepared_withoutTrace_returnsNull() {
        var prepared = new RagService.Prepared(List.of(), List.of());

        assertNull(prepared.trace(), "Two-arg constructor should leave trace null");
    }

    @Test
    void prepared_traceSummary_includesEmbeddingFailure() {
        var trace = new RetrievalTrace();
        trace.record("bm25", 1_000_000L, 0, 5, null);
        trace.record("knn", 100_000L, 5, 5, "skipped: embedding failed — NaN");

        var prepared = new RagService.Prepared(List.of(), List.of(), trace);

        String summary = prepared.trace().summary();
        assertTrue(summary.contains("embedding failed"), "Summary should contain embedding failure");
        assertTrue(summary.contains("NaN"), "Summary should contain NaN reason");
    }

    @Test
    void prepared_snippetMaps_consistent_with_snippets() {
        var snippets = List.of(
                new ContextResult.Snippet("x.java#0", "code x"),
                new ContextResult.Snippet("y.java#0", "code y")
        );

        var prepared = new RagService.Prepared(snippets, List.of("x.java", "y.java"));

        List<Map<String, String>> maps = prepared.snippetMaps();
        assertEquals(2, maps.size());
        assertEquals("x.java#0", maps.get(0).get("path"));
        assertEquals("code x", maps.get(0).get("text"));
    }

    @Test
    void prepared_citations_with_metadata_are_rich() {
        // Simulate what RagService.prepare() should now produce:
        // snippets carry metadata, citations built via ContextPacker.buildCitations()
        var snippets = List.of(
                new ContextResult.Snippet("src/Foo.java#0", "code foo",
                        new ChunkMetadata("java", 10, 25, "## Architecture")),
                new ContextResult.Snippet("src/Bar.java#0", "code bar",
                        new ChunkMetadata("java", 1, 50, null))
        );
        List<String> richCitations = ContextPacker.buildCitations(snippets);

        var prepared = new RagService.Prepared(snippets, richCitations);

        assertEquals(2, prepared.citations().size());
        assertEquals("src/Foo.java:10-25 \u00A7 Architecture", prepared.citations().get(0));
        assertEquals("src/Bar.java:1-50", prepared.citations().get(1));
    }

    @Test
    void prepared_citations_without_metadata_are_bare_paths() {
        // When snippets have no metadata, citations should be bare paths
        var snippets = List.of(
                new ContextResult.Snippet("src/X.java#0", "content"),
                new ContextResult.Snippet("src/Y.java#1", "content2")
        );
        List<String> bareCitations = ContextPacker.buildCitations(snippets);

        var prepared = new RagService.Prepared(snippets, bareCitations);

        assertEquals(2, prepared.citations().size());
        assertEquals("src/X.java", prepared.citations().get(0));
        assertEquals("src/Y.java", prepared.citations().get(1));
    }
}

