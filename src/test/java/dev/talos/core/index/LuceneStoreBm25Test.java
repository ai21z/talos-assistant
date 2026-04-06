package dev.talos.core.index;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class LuceneStoreBm25Test {

    @Test
    void bm25FindsBestDoc() throws Exception {
        Path dir = Files.createTempDirectory("lucene-bm25");
        // vectorDim can be any positive number; we won't add vectors in this test
        try (LuceneStore store = new LuceneStore(dir, 3)) {
            store.add("docA#0", "JavaFX first run wizard and UI scaffold", null);
            store.add("docB#0", "Lucene BM25 example and query parser tutorial", null);
            store.add("docC#0", "Completely unrelated text", null);
            store.commit();

            var hits = store.searchBM25("lucene bm25 query", 5);
            assertFalse(hits.isEmpty());
            // docB should rank above others for this query
            assertEquals("docB#0", hits.getFirst().path);
            assertTrue(hits.getFirst().score >= 0f);
        }
    }

    @Test
    void getTextByPathReturnsStoredText() throws Exception {
        Path dir = Files.createTempDirectory("lucene-gettext");
        try (LuceneStore store = new LuceneStore(dir, 3)) {
            store.add("f#1", "Hello stored field", null);
            store.commit();
            assertEquals("Hello stored field", store.getTextByPath("f#1"));
            assertNull(store.getTextByPath("missing#0"));
        }
    }
}
