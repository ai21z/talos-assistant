package dev.talos.core.index;

import dev.talos.core.ingest.ChunkMetadata;
import dev.talos.core.spi.CorpusStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link LuceneStore} KNN (vector) retrieval.
 *
 * <p>Uses small 3-dimensional vectors to validate KNN search, scoring,
 * ordering, metadata propagation, and edge cases — all without requiring
 * an external embedding model.
 */
@DisplayName("LuceneStore — KNN retrieval")
class LuceneStoreKnnTest {

    private static final int DIM = 3;

    // ═══════════════════════════════════════════════════════════════════════
    //  Basic KNN retrieval
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Basic KNN retrieval")
    class BasicRetrieval {

        @Test
        @DisplayName("nearest vector ranks first")
        void nearestVectorRanksFirst(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("close#0", "close to query", new float[]{1.0f, 0.0f, 0.0f});
                store.add("far#0", "far from query", new float[]{0.0f, 1.0f, 0.0f});
                store.add("mid#0", "mid distance", new float[]{0.7f, 0.3f, 0.0f});
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 3);

                assertFalse(hits.isEmpty(), "KNN should return results");
                assertEquals("close#0", hits.getFirst().path, "Exact match should rank first");
            }
        }

        @Test
        @DisplayName("k limits result count")
        void kLimitsResultCount(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("a#0", "alpha", new float[]{1.0f, 0.0f, 0.0f});
                store.add("b#0", "beta", new float[]{0.0f, 1.0f, 0.0f});
                store.add("c#0", "gamma", new float[]{0.0f, 0.0f, 1.0f});
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 2);

                assertEquals(2, hits.size(), "Should return at most k results");
            }
        }

        @Test
        @DisplayName("scores are non-negative")
        void scoresAreNonNegative(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("a#0", "text", new float[]{0.5f, 0.5f, 0.0f});
                store.add("b#0", "text", new float[]{0.0f, 0.5f, 0.5f});
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 5);

                for (var h : hits) {
                    assertTrue(h.score >= 0f, "Score should be non-negative: " + h.score);
                }
            }
        }

        @Test
        @DisplayName("ordering reflects vector similarity")
        void orderingReflectsVectorSimilarity(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                // Query vector will be [1, 0, 0]
                // Distances: exact=0, mid≈0.3, far≈1.0
                store.add("exact#0", "exact", new float[]{1.0f, 0.0f, 0.0f});
                store.add("mid#0", "mid", new float[]{0.8f, 0.2f, 0.0f});
                store.add("far#0", "far", new float[]{0.0f, 0.0f, 1.0f});
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 3);

                assertEquals(3, hits.size());
                assertEquals("exact#0", hits.get(0).path, "Closest vector first");
                assertEquals("mid#0", hits.get(1).path, "Middle distance second");
                assertEquals("far#0", hits.get(2).path, "Farthest vector last");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  SPI interface (CorpusStore.knn)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("SPI knn() method")
    class SpiKnn {

        @Test
        @DisplayName("SPI knn returns CorpusStore.Hit with path and score")
        void spiKnnReturnsHits(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("doc#0", "document", new float[]{1.0f, 0.0f, 0.0f});
                store.commit();

                List<CorpusStore.Hit> hits = store.knn(new float[]{1.0f, 0.0f, 0.0f}, 5);

                assertFalse(hits.isEmpty());
                assertEquals("doc#0", hits.getFirst().path());
                assertTrue(hits.getFirst().score() > 0f);
            }
        }

        @Test
        @DisplayName("SPI knn returns metadata when stored")
        void spiKnnReturnsMetadata(@TempDir Path dir) {
            var meta = new ChunkMetadata("java", 10, 30, "## Methods");
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("Foo.java#0", "method implementations", new float[]{1.0f, 0.0f, 0.0f},
                        "hash1", 0, meta);
                store.commit();

                List<CorpusStore.Hit> hits = store.knn(new float[]{1.0f, 0.0f, 0.0f}, 5);

                assertFalse(hits.isEmpty());
                ChunkMetadata retrieved = hits.getFirst().metadata();
                assertNotNull(retrieved);
                assertEquals("java", retrieved.language());
                assertEquals(10, retrieved.lineStart());
                assertEquals(30, retrieved.lineEnd());
                assertEquals("## Methods", retrieved.headingContext());
            }
        }

        @Test
        @DisplayName("SPI knn without metadata returns ChunkMetadata.empty()")
        void spiKnnWithoutMetadata(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("plain#0", "plain text", new float[]{1.0f, 0.0f, 0.0f});
                store.commit();

                List<CorpusStore.Hit> hits = store.knn(new float[]{1.0f, 0.0f, 0.0f}, 5);

                assertFalse(hits.isEmpty());
                ChunkMetadata retrieved = hits.getFirst().metadata();
                assertNotNull(retrieved);
                assertFalse(retrieved.hasContent(), "No metadata stored → empty");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null query vector returns empty list")
        void nullQueryReturnsEmpty(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("a#0", "text", new float[]{1.0f, 0.0f, 0.0f});
                store.commit();

                var hits = store.knn(null, 5);
                assertTrue(hits.isEmpty(), "Null query vector should return empty");
            }
        }

        @Test
        @DisplayName("empty index returns empty list")
        void emptyIndexReturnsEmpty(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 5);
                assertTrue(hits.isEmpty(), "Empty index should return no results");
            }
        }

        @Test
        @DisplayName("wrong-dimension vector is silently skipped during add")
        void wrongDimensionVectorSkipped(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                // DIM=3 but we provide a 2-element vector → should be skipped
                store.add("bad#0", "wrong dim", new float[]{1.0f, 0.0f});
                store.add("good#0", "correct dim", new float[]{1.0f, 0.0f, 0.0f});
                store.commit();

                // KNN should only find the good doc
                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 5);
                assertEquals(1, hits.size(), "Only correctly-dimensioned docs should appear");
                assertEquals("good#0", hits.getFirst().path);
            }
        }

        @Test
        @DisplayName("doc with null vector does not appear in KNN results")
        void nullVectorDocNotInKnn(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("novector#0", "no vector content", null);
                store.add("withvec#0", "has vector", new float[]{0.5f, 0.5f, 0.0f});
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 5);
                assertEquals(1, hits.size());
                assertEquals("withvec#0", hits.getFirst().path, "Only vectorized doc should appear");
            }
        }

        @Test
        @DisplayName("doc update replaces vector in KNN results")
        void docUpdateReplacesVector(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                // Initial: vector points to [1,0,0]
                store.add("doc#0", "original", new float[]{1.0f, 0.0f, 0.0f});
                store.commit();

                // Update: same path, vector now points to [0,0,1]
                store.add("doc#0", "updated", new float[]{0.0f, 0.0f, 1.0f});
                store.commit();

                // Query toward [0,0,1] should find the updated vector
                var hits = store.searchKNN(new float[]{0.0f, 0.0f, 1.0f}, 1);
                assertEquals(1, hits.size());
                assertEquals("doc#0", hits.getFirst().path);
                // Verify text was also updated
                assertEquals("updated", store.getTextByPath("doc#0"));
            }
        }

        @Test
        @DisplayName("k=1 returns exactly one result")
        void kOneReturnsSingleResult(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("a#0", "alpha", new float[]{1.0f, 0.0f, 0.0f});
                store.add("b#0", "beta", new float[]{0.0f, 1.0f, 0.0f});
                store.commit();

                var hits = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 1);
                assertEquals(1, hits.size());
                assertEquals("a#0", hits.getFirst().path);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Combined BM25 + KNN (sanity check for dual retrieval)
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Combined BM25 + KNN")
    class Combined {

        @Test
        @DisplayName("same store supports both BM25 and KNN queries")
        void bothSearchMethodsWork(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                store.add("java#0", "Java class design patterns", new float[]{1.0f, 0.0f, 0.0f});
                store.add("python#0", "Python async await tutorial", new float[]{0.0f, 1.0f, 0.0f});
                store.add("rust#0", "Rust ownership and borrowing", new float[]{0.0f, 0.0f, 1.0f});
                store.commit();

                // BM25 finds by text
                var bm25Hits = store.searchBM25("Java design patterns", 3);
                assertFalse(bm25Hits.isEmpty());
                assertEquals("java#0", bm25Hits.getFirst().path);

                // KNN finds by vector (vector for "rust" topic)
                var knnHits = store.searchKNN(new float[]{0.0f, 0.0f, 1.0f}, 3);
                assertFalse(knnHits.isEmpty());
                assertEquals("rust#0", knnHits.getFirst().path);
            }
        }

        @Test
        @DisplayName("BM25 and KNN can return different top results for same store")
        void differentRankings(@TempDir Path dir) {
            try (var store = new LuceneStore(dir, DIM)) {
                // Text says "lucene" but vector is far from [1,0,0]
                store.add("textMatch#0", "lucene search engine internals",
                        new float[]{0.0f, 0.0f, 1.0f});
                // Text says "unrelated" but vector is close to [1,0,0]
                store.add("vecMatch#0", "unrelated content",
                        new float[]{1.0f, 0.0f, 0.0f});
                store.commit();

                var bm25 = store.searchBM25("lucene search", 2);
                var knn = store.searchKNN(new float[]{1.0f, 0.0f, 0.0f}, 2);

                assertEquals("textMatch#0", bm25.getFirst().path, "BM25 ranks by text");
                assertEquals("vecMatch#0", knn.getFirst().path, "KNN ranks by vector");
            }
        }
    }
}


