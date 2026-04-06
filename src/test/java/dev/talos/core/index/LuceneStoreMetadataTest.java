package dev.talos.core.index;

import dev.talos.core.ingest.ChunkMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that {@link ChunkMetadata} fields are persisted to and retrievable from
 * the Lucene index via {@link LuceneStore}.
 */
class LuceneStoreMetadataTest {

    @TempDir Path tempDir;

    @Test
    void metadataFieldsStoredAndRetrievable() throws Exception {
        var meta = new ChunkMetadata("java", 10, 25, "## Architecture");

        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/Foo.java#0", "public class Foo {}", null, "abc123", 0, meta);
            store.commit();

            // Verify the document was stored
            String text = store.getTextByPath("src/Foo.java#0");
            assertEquals("public class Foo {}", text);

            // Verify metadata fields via a raw Lucene reader
            var sm = store.getSearcherManager();
            var searcher = sm.acquire();
            try {
                var tq = new org.apache.lucene.search.TermQuery(
                        new org.apache.lucene.index.Term(LuceneStore.F_PATH, "src/Foo.java#0"));
                var td = searcher.search(tq, 1);
                assertEquals(1, td.scoreDocs.length);

                var doc = searcher.storedFields().document(td.scoreDocs[0].doc);
                assertEquals("java", doc.get(LuceneStore.F_LANG));
                assertEquals("## Architecture", doc.get(LuceneStore.F_HEADING));

                var lineStartField = doc.getField(LuceneStore.F_LINE_START);
                assertNotNull(lineStartField, "lineStart field should be stored");
                assertEquals(10, lineStartField.numericValue().intValue());

                var lineEndField = doc.getField(LuceneStore.F_LINE_END);
                assertNotNull(lineEndField, "lineEnd field should be stored");
                assertEquals(25, lineEndField.numericValue().intValue());
            } finally {
                sm.release(searcher);
            }
        }
    }

    @Test
    void nullMetadata_storesWithoutMetadataFields() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("plain.txt#0", "hello", null, null, 0, null);
            store.commit();

            var sm = store.getSearcherManager();
            var searcher = sm.acquire();
            try {
                var tq = new org.apache.lucene.search.TermQuery(
                        new org.apache.lucene.index.Term(LuceneStore.F_PATH, "plain.txt#0"));
                var td = searcher.search(tq, 1);
                var doc = searcher.storedFields().document(td.scoreDocs[0].doc);

                assertNull(doc.get(LuceneStore.F_LANG));
                assertNull(doc.get(LuceneStore.F_HEADING));
                assertNull(doc.getField(LuceneStore.F_LINE_START));
                assertNull(doc.getField(LuceneStore.F_LINE_END));
            } finally {
                sm.release(searcher);
            }
        }
    }

    @Test
    void backwardsCompatibleAdd_stillWorks() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            // Old-style add without metadata
            store.add("file.txt#0", "content", null, "hash", 0);
            store.commit();
            assertEquals("content", store.getTextByPath("file.txt#0"));
        }
    }
}

