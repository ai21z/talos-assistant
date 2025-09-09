package dev.loqj.core.search;

import dev.loqj.core.spi.CorpusStore;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class SnippetBuilderTest {

    /** Minimal in-memory CorpusStore for tests, matching current SPI. */
    static class StubStore implements CorpusStore {
        private final Map<String,String> textByPath = new LinkedHashMap<>();

        void put(String path, String text) { textByPath.put(path, text); }

        // ----- SPI -----
        @Override public void add(String path, String text, float[] vec) {
            // ignore vec; just store text
            textByPath.put(path, text);
        }

        @Override public void add(String path, String text, float[] vec, String fileHash, Integer chunkId) {
            // ignore vec/hash/id; just store text
            textByPath.put(path, text);
        }

        @Override public void commit() { /* no-op for test */ }

        @Override public List<Hit> bm25(String queryText, int k) { return List.of(); }

        @Override public List<Hit> knn(float[] qvec, int k) { return List.of(); }

        @Override public String getTextByPath(String path) { return textByPath.get(path); }

        @Override public void close() { /* no-op */ }
    }

    @Test
    void pack_dedupesAndKeepsInsertionOrder() {
        StubStore store = new StubStore();
        store.put("A#0", "alpha");
        store.put("B#0", "bravo");
        store.put("C#0", "charlie");

        // Production SnippetBuilder.pack expects List<CorpusStore.Hit>
        List<CorpusStore.Hit> hits = List.of(
                new CorpusStore.Hit("A#0", 1.0f),
                new CorpusStore.Hit("B#0", 0.9f),
                new CorpusStore.Hit("A#0", 0.5f), // duplicate path → should be ignored
                new CorpusStore.Hit("C#0", 0.8f)
        );

        var snippets = SnippetBuilder.pack(store, hits, 1000);

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

        // Expect pinned first + one regular (budget ~1600 chars)
        assertEquals(2, merged.size());
        assertEquals("X#0", merged.get(0).path());
        assertEquals("Y#0", merged.get(1).path());
    }
}
