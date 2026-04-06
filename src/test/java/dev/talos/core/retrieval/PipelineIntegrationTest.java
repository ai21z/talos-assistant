package dev.talos.core.retrieval;

import dev.talos.core.index.LuceneStore;
import dev.talos.core.rerank.NoOpReranker;
import dev.talos.core.retrieval.stages.*;
import dev.talos.core.spi.CorpusStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for the full composed retrieval pipeline
 * (BM25 → KNN → RRF Fusion → Rerank → Dedup) running against a
 * real {@link LuceneStore} with indexed content.
 * <p>
 * These tests verify cross-stage interactions that unit tests on
 * individual stages cannot catch: correct dedup after fusion,
 * topK enforcement across the whole chain, score ordering through
 * the pipeline, and path consistency.
 */
class PipelineIntegrationTest {

    @TempDir Path tempDir;

    // ──── BM25-only (no vectors) ────

    @Test
    void bm25_only_pipeline_returns_deduplicated_topK() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            indexFixture(store, /* vectors= */ false);

            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene indexing search", null, 3);
            RetrievalResult result = pipeline.execute(request);

            List<RetrievalCandidate> candidates = result.candidates();

            // Result count ≤ topK
            assertTrue(candidates.size() <= 3,
                    "Expected ≤ 3, got " + candidates.size());

            // No duplicate paths
            Set<String> paths = candidates.stream()
                    .map(RetrievalCandidate::path)
                    .collect(Collectors.toSet());
            assertEquals(candidates.size(), paths.size(), "Duplicate paths in results");

            // Scores are in descending order
            assertDescendingScores(candidates);

            // All candidates should have a recognized source tag
            // DedupStage preserves the source from prior stages (typically "rrf" after fusion)
            assertTrue(candidates.stream().allMatch(c ->
                            "rrf".equals(c.source()) || "bm25".equals(c.source())
                                    || "knn".equals(c.source()) || "rerank".equals(c.source())),
                    "All candidates should have a recognized source tag");
        }
    }

    @Test
    void bm25_only_overlapping_chunks_dedup_to_distinct_paths() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            // Same file, multiple chunks — all should match query
            store.add("src/Search.java#0", "Lucene search query parsing and indexing engine", null);
            store.add("src/Search.java#1", "Lucene BM25 scoring and retrieval ranking", null);
            store.add("src/Other.java#0", "Completely unrelated topic about cooking", null);
            store.commit();

            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene search", null, 5);
            RetrievalResult result = pipeline.execute(request);

            List<RetrievalCandidate> candidates = result.candidates();

            // Both Search.java chunks are different paths (they have different #N suffixes)
            // so both may appear — dedup is by exact path, not by base file
            Set<String> paths = candidates.stream()
                    .map(RetrievalCandidate::path)
                    .collect(Collectors.toSet());
            assertEquals(candidates.size(), paths.size(), "No duplicate paths");
        }
    }

    @Test
    void result_count_respects_topK_even_with_many_hits() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            // Index 20 chunks all containing the query terms
            for (int i = 0; i < 20; i++) {
                store.add("file" + i + ".java#0",
                        "Lucene search query example number " + i + " with diverse content",
                        null);
            }
            store.commit();

            int topK = 4;
            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene search", null, topK);
            RetrievalResult result = pipeline.execute(request);

            assertTrue(result.candidates().size() <= topK,
                    "Expected ≤ " + topK + ", got " + result.candidates().size());
        }
    }

    @Test
    void trace_records_all_five_stages() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            indexFixture(store, false);

            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene", null, 5);
            RetrievalResult result = pipeline.execute(request);

            RetrievalTrace trace = result.trace();
            assertEquals(5, trace.entries().size(), "Pipeline should have 5 stages");

            List<String> stageNames = trace.entries().stream()
                    .map(RetrievalTrace.Entry::stageName)
                    .toList();
            assertEquals(List.of("bm25", "knn", "rrf", "rerank", "dedup"), stageNames);

            // KNN should note it was skipped (no query vector)
            RetrievalTrace.Entry knnEntry = trace.entries().get(1);
            assertNotNull(knnEntry.note());
            assertTrue(knnEntry.note().contains("skipped"),
                    "KNN should note skip: " + knnEntry.note());
        }
    }

    @Test
    void empty_index_returns_empty_results() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.commit();

            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("anything", null, 5);
            RetrievalResult result = pipeline.execute(request);

            assertTrue(result.candidates().isEmpty());
        }
    }

    @Test
    void text_retrievable_for_all_result_paths() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            indexFixture(store, false);

            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene search", null, 5);
            RetrievalResult result = pipeline.execute(request);

            // Every result path should have retrievable text
            for (RetrievalCandidate c : result.candidates()) {
                String text = store.getTextByPath(c.path());
                assertNotNull(text, "No text for path: " + c.path());
                assertFalse(text.isBlank(), "Blank text for path: " + c.path());
            }
        }
    }

    @Test
    void rrf_fusion_boosts_overlapping_bm25_knn_hits() throws Exception {
        // Use vectors so both BM25 and KNN contribute results
        Path vecDir = tempDir.resolve("vec");
        java.nio.file.Files.createDirectories(vecDir);
        int dim = 4;

        try (var store = new LuceneStore(vecDir, dim)) {
            // Doc A: strong BM25 match + close vector
            store.add("docA#0", "Lucene search index query retrieval engine",
                    new float[]{0.9f, 0.1f, 0.0f, 0.0f});
            // Doc B: strong BM25 match + moderate vector
            store.add("docB#0", "Lucene BM25 ranking and scoring algorithm",
                    new float[]{0.7f, 0.3f, 0.0f, 0.0f});
            // Doc C: weak BM25 + very close vector
            store.add("docC#0", "Something about a unrelated completely different topic",
                    new float[]{0.95f, 0.05f, 0.0f, 0.0f});
            // Doc D: no BM25 match, far vector
            store.add("docD#0", "Cooking recipes and meal preparation tips",
                    new float[]{0.0f, 0.0f, 0.9f, 0.1f});
            store.commit();

            // Query vector closest to docA and docC
            float[] qvec = {1.0f, 0.0f, 0.0f, 0.0f};
            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene search", qvec, 3);
            RetrievalResult result = pipeline.execute(request);

            List<RetrievalCandidate> candidates = result.candidates();
            assertTrue(candidates.size() <= 3);

            // Scores should be descending
            assertDescendingScores(candidates);

            // No duplicates
            Set<String> paths = candidates.stream()
                    .map(RetrievalCandidate::path)
                    .collect(Collectors.toSet());
            assertEquals(candidates.size(), paths.size());
        }
    }

    @Test
    void knn_contributes_candidates_when_vector_present() throws Exception {
        Path vecDir = tempDir.resolve("knn");
        java.nio.file.Files.createDirectories(vecDir);
        int dim = 3;

        try (var store = new LuceneStore(vecDir, dim)) {
            // No BM25 overlap with query, but close vector
            store.add("vectorOnly#0", "Cooking recipes for dinner",
                    new float[]{1.0f, 0.0f, 0.0f});
            // Good BM25 match, distant vector
            store.add("textOnly#0", "Lucene search engine",
                    new float[]{0.0f, 0.0f, 1.0f});
            store.commit();

            float[] qvec = {1.0f, 0.0f, 0.0f};
            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene search", qvec, 5);
            RetrievalResult result = pipeline.execute(request);

            Set<String> paths = result.candidates().stream()
                    .map(RetrievalCandidate::path)
                    .collect(Collectors.toSet());

            // Both should appear: textOnly from BM25, vectorOnly from KNN
            assertTrue(paths.contains("textOnly#0"),
                    "textOnly should appear from BM25: " + paths);
            assertTrue(paths.contains("vectorOnly#0"),
                    "vectorOnly should appear from KNN: " + paths);
        }
    }

    @Test
    void pipeline_paths_convenience_matches_candidates() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            indexFixture(store, false);

            RetrievalPipeline pipeline = defaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest("lucene", null, 5);
            RetrievalResult result = pipeline.execute(request);

            List<String> fromPaths = result.paths();
            List<String> fromCandidates = result.candidates().stream()
                    .map(RetrievalCandidate::path)
                    .toList();
            assertEquals(fromCandidates, fromPaths);
        }
    }

    // ──── helpers ────

    /** Builds the default pipeline: BM25 → KNN → RRF → Rerank(NoOp) → Dedup. */
    private static RetrievalPipeline defaultPipeline(CorpusStore store) {
        return RetrievalPipeline.builder()
                .addStage(new Bm25Stage(store))
                .addStage(new KnnStage(store))
                .addStage(new RrfFusionStage(60))
                .addStage(new RerankerStage(new NoOpReranker()))
                .addStage(new DedupStage())
                .build();
    }

    /** Index a standard fixture of 5 docs with varying relevance. */
    private static void indexFixture(LuceneStore store, boolean withVectors) {
        store.add("src/IndexManager.java#0",
                "Lucene indexing and search manager for local document store",
                withVectors ? new float[]{0.8f, 0.1f, 0.1f} : null);
        store.add("src/QueryParser.java#0",
                "Query parser for Lucene full-text search with BM25 scoring",
                withVectors ? new float[]{0.7f, 0.2f, 0.1f} : null);
        store.add("src/Config.java#0",
                "Application configuration loader and YAML parser",
                withVectors ? new float[]{0.1f, 0.1f, 0.8f} : null);
        store.add("README.md#0",
                "Project readme with getting started and architecture notes",
                withVectors ? new float[]{0.3f, 0.5f, 0.2f} : null);
        store.add("docs/design.md#0",
                "Design document covering search retrieval pipeline stages",
                withVectors ? new float[]{0.6f, 0.3f, 0.1f} : null);
        store.commit();
    }

    private static void assertDescendingScores(List<RetrievalCandidate> candidates) {
        for (int i = 1; i < candidates.size(); i++) {
            assertTrue(candidates.get(i - 1).score() >= candidates.get(i).score(),
                    String.format("Score at [%d]=%.6f < score at [%d]=%.6f",
                            i - 1, candidates.get(i - 1).score(),
                            i, candidates.get(i).score()));
        }
    }
}

