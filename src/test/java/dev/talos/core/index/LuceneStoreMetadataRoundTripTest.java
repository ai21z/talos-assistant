package dev.talos.core.index;
import dev.talos.spi.types.ChunkMetadata;
import dev.talos.spi.CorpusStore;
import dev.talos.core.Config;
import dev.talos.core.context.ContextResult;
import dev.talos.core.privacy.ContextSourcePolicy;
import dev.talos.spi.types.SourceIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Tests metadata round-trip through LuceneStore:
 * - Store with metadata, retrieve via bm25/knn, verify Hit carries metadata
 * - getMetadataByPath returns stored metadata
 * - Backwards compatible: missing metadata returns ChunkMetadata.empty()
 */
class LuceneStoreMetadataRoundTripTest {
    @Test
    void explicitSourcePathSurvivesRoundTripAndInconsistentProvenanceIsDenied(@TempDir Path dir) {
        var meta = new ChunkMetadata(null, -1, -1, null, SourceIdentity.unclassified("notes#draft.docx"));
        try (var store = new LuceneStore(dir.resolve("index"), 0)) {
            store.add("public.md#0", "retained private prose", null, "hash", 0, meta);
            store.commit();
            var hit = store.bm25("retained private prose", 1).getFirst();
            assertEquals(meta.sourceIdentity(), hit.metadata().sourceIdentity());
            var admitted = ContextSourcePolicy.admit(new Config(null), dir,
                    new ContextResult.Snippet(hit.path(), store.getTextByPath(hit.path()), hit.metadata()));
            assertFalse(admitted.contentRestrictions().modelAllowed());
            assertFalse(admitted.contentRestrictions().artifactAllowed());
            assertFalse(admitted.contentRestrictions().historyAllowed());
        }
    }

    @Test
    void legacyStoredIdentityReconstructsOnlyFinalNumericSuffix(@TempDir Path dir) throws Exception {
        // An actual pre-sourcePath index fixture, containing classification but no source path field.
        try (var directory = org.apache.lucene.store.FSDirectory.open(dir);
             var analyzer = new org.apache.lucene.analysis.standard.StandardAnalyzer();
             var writer = new org.apache.lucene.index.IndexWriter(directory,
                     new org.apache.lucene.index.IndexWriterConfig(analyzer))) {
            var document = new org.apache.lucene.document.Document();
            document.add(new org.apache.lucene.document.StringField(LuceneStore.F_PATH,
                    "notes#draft.docx#0", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.StringField(LuceneStore.F_SOURCE_TYPE,
                    "UNKNOWN", org.apache.lucene.document.Field.Store.YES));
            document.add(new org.apache.lucene.document.TextField(LuceneStore.F_TEXT,
                    "legacy extracted prose", org.apache.lucene.document.Field.Store.YES));
            writer.addDocument(document);
        }
        try (var store = new LuceneStore(dir, 0)) {
            var hit = store.bm25("legacy extracted prose", 1).getFirst();
            assertEquals("notes#draft.docx", hit.metadata().sourceIdentity().path());
            Config cfg = new Config(null);
            cfg.data.put("privacy", Map.of("mode", "private", "rag", Map.of("enabled_in_private_mode", true),
                    "document_extraction", Map.of("allow_send_to_model", false,
                            "allow_rag_indexing", true, "persist_raw_artifacts", false)));
            cfg.data.put("document_extraction", Map.of("enabled", true, "word", Map.of("enabled", true)));
            var admitted = ContextSourcePolicy.admit(cfg, dir,
                    new ContextResult.Snippet(hit.path(), store.getTextByPath(hit.path()), hit.metadata()));
            assertFalse(admitted.contentRestrictions().modelAllowed());
            assertFalse(admitted.contentRestrictions().artifactAllowed());
            assertFalse(admitted.contentRestrictions().historyAllowed());
            assertTrue(admitted.contentRestrictions().indexAllowed());
        }
    }

    @Test
    void bm25_returnsMetadataOnHit(@TempDir Path dir) {
        var meta = new ChunkMetadata("java", 10, 25, "## Architecture");
        try (var store = new LuceneStore(dir, 0)) {
            store.add("src/Foo.java#0", "architecture of the system", null, "abc123", 0, meta);
            store.commit();
            List<CorpusStore.Hit> hits = store.bm25("architecture", 5);
            assertFalse(hits.isEmpty());
            CorpusStore.Hit hit = hits.get(0);
            assertEquals("src/Foo.java#0", hit.path());
            assertNotNull(hit.metadata());
            assertEquals("java", hit.metadata().language());
            assertEquals(10, hit.metadata().lineStart());
            assertEquals(25, hit.metadata().lineEnd());
            assertEquals("## Architecture", hit.metadata().headingContext());
        }
    }
    @Test
    void getMetadataByPath_returnsStoredMetadata(@TempDir Path dir) {
        var meta = new ChunkMetadata("py", 1, 50, "# Setup");
        try (var store = new LuceneStore(dir, 0)) {
            store.add("main.py#0", "setup code", null, "hash1", 0, meta);
            store.commit();
            ChunkMetadata retrieved = store.getMetadataByPath("main.py#0");
            assertEquals("py", retrieved.language());
            assertEquals(1, retrieved.lineStart());
            assertEquals(50, retrieved.lineEnd());
            assertEquals("# Setup", retrieved.headingContext());
        }
    }
    @Test
    void getMetadataByPath_unknownPath_returnsEmpty(@TempDir Path dir) {
        try (var store = new LuceneStore(dir, 0)) {
            store.commit();
            ChunkMetadata meta = store.getMetadataByPath("nonexistent.java#0");
            assertNotNull(meta);
            assertFalse(meta.hasContent());
        }
    }
    @Test
    void bm25_noMetadataStored_returnsEmptyMetadata(@TempDir Path dir) {
        try (var store = new LuceneStore(dir, 0)) {
            // Add without metadata (backwards-compatible path)
            store.add("old.txt#0", "old content", null, "oldhash", 0);
            store.commit();
            List<CorpusStore.Hit> hits = store.bm25("old content", 5);
            assertFalse(hits.isEmpty());
            assertNotNull(hits.get(0).metadata());
            assertFalse(hits.get(0).metadata().hasContent());
        }
    }
    @Test
    void hit_backwardsCompatConstructor_nullMetadata() {
        var hit = new CorpusStore.Hit("path", 1.0f);
        assertNull(hit.metadata());
    }
    @Test
    void hit_withMetadata_constructor() {
        var meta = new ChunkMetadata("java", 10, 20, null);
        var hit = new CorpusStore.Hit("path", 1.0f, meta);
        assertEquals(meta, hit.metadata());
    }
    @Test
    void bm25_partialMetadata_returnsWhatWasStored(@TempDir Path dir) {
        // Only language, no line numbers, no heading
        var meta = new ChunkMetadata("md", -1, -1, null);
        try (var store = new LuceneStore(dir, 0)) {
            store.add("README.md#0", "readme content", null, "h", 0, meta);
            store.commit();
            List<CorpusStore.Hit> hits = store.bm25("readme", 5);
            assertFalse(hits.isEmpty());
            ChunkMetadata retrieved = hits.get(0).metadata();
            assertEquals("md", retrieved.language());
            assertEquals(-1, retrieved.lineStart());
            assertEquals(-1, retrieved.lineEnd());
            assertNull(retrieved.headingContext());
        }
    }
    @Test
    void bm25_lineEndOnly_recognizedAsHavingContent(@TempDir Path dir) {
        // Edge case: only lineEnd is set (malformed/partial metadata).
        // extractMetadata must not treat this as empty - lineEnd > 0
        // signals that some metadata was stored.
        var meta = new ChunkMetadata(null, -1, 42, null);
        try (var store = new LuceneStore(dir, 0)) {
            store.add("edge.txt#0", "edge case content", null, "e", 0, meta);
            store.commit();
            List<CorpusStore.Hit> hits = store.bm25("edge case", 5);
            assertFalse(hits.isEmpty());
            ChunkMetadata retrieved = hits.get(0).metadata();
            assertTrue(retrieved.hasContent(), "lineEnd-only metadata must be recognized as having content");
            assertNull(retrieved.language());
            assertEquals(-1, retrieved.lineStart());
            assertEquals(42, retrieved.lineEnd());
            assertNull(retrieved.headingContext());
        }
    }
}

