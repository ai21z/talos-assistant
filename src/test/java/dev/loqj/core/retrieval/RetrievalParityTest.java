package dev.loqj.core.retrieval;

import dev.loqj.core.index.LuceneStore;
import dev.loqj.core.search.Retriever;
import dev.loqj.core.retrieval.stages.DedupStage;
import dev.loqj.core.retrieval.stages.RrfFusionStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden parity tests: verify that the new pipeline stages produce identical
 * results to the legacy Retriever.fuseRrf() + Retriever.mmr() on fixed data.
 *
 * These tests compare the legacy code path against the new pipeline stages
 * to prove behavior equivalence before the legacy code is removed.
 */
class RetrievalParityTest {

    // --- Fixture data ---

    /** Simulated BM25 hits (path, score) — fixed ordering. */
    private static final List<LuceneStore.Hit> BM25_HITS = List.of(
            new LuceneStore.Hit("src/Main.java#0", 12.5f),
            new LuceneStore.Hit("src/Config.java#0", 10.2f),
            new LuceneStore.Hit("src/Utils.java#0", 8.7f),
            new LuceneStore.Hit("README.md#0", 6.1f),
            new LuceneStore.Hit("src/Main.java#1", 5.0f),
            new LuceneStore.Hit("build.gradle#0", 3.2f)
    );

    /** Simulated KNN hits (path, score) — overlapping with BM25. */
    private static final List<LuceneStore.Hit> KNN_HITS = List.of(
            new LuceneStore.Hit("src/Config.java#0", 0.95f),
            new LuceneStore.Hit("src/Main.java#0", 0.88f),
            new LuceneStore.Hit("docs/GUIDE.md#0", 0.82f),
            new LuceneStore.Hit("src/Utils.java#0", 0.75f),
            new LuceneStore.Hit("src/Service.java#0", 0.70f)
    );

    private static final int RRF_K = 60;
    private static final int TOP_K = 4;

    // --- Helper: convert LuceneStore.Hit list to RetrievalCandidate list ---

    private List<RetrievalCandidate> toCandidate(List<LuceneStore.Hit> hits, String source) {
        List<RetrievalCandidate> out = new ArrayList<>();
        for (LuceneStore.Hit h : hits) {
            out.add(RetrievalCandidate.of(h.path, h.score, source));
        }
        return out;
    }

    // --- Parity test: RRF fusion ---

    @Test
    void rrf_fusion_produces_same_paths_and_order_as_legacy() {
        // Legacy path
        List<Retriever.Cand> legacyFused = Retriever.fuseRrf(BM25_HITS, KNN_HITS, RRF_K, TOP_K * 2);

        // New pipeline path: merge BM25 + KNN candidates, then RRF stage
        List<RetrievalCandidate> combined = new ArrayList<>();
        combined.addAll(toCandidate(BM25_HITS, "bm25"));
        combined.addAll(toCandidate(KNN_HITS, "knn"));

        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        RetrievalRequest request = new RetrievalRequest("test query", new float[]{1f}, TOP_K);
        List<RetrievalCandidate> pipelineFused = rrfStage.process(request, combined).candidates();

        // Compare: same paths in same order
        List<String> legacyPaths = legacyFused.stream().map(c -> c.path).toList();
        List<String> pipelinePaths = pipelineFused.stream().map(RetrievalCandidate::path).toList();
        assertEquals(legacyPaths, pipelinePaths, "RRF fusion must produce same path ordering");

        // Compare: same scores (float precision)
        for (int i = 0; i < legacyFused.size(); i++) {
            assertEquals(legacyFused.get(i).score, pipelineFused.get(i).score(), 1e-6,
                    "RRF score mismatch at index " + i + " for path " + legacyPaths.get(i));
        }
    }

    // --- Parity test: RRF + dedup (full legacy path) ---

    @Test
    void full_legacy_path_matches_pipeline_rrf_then_dedup() {
        // Legacy: fuseRrf → mmr (dedup + topK)
        List<Retriever.Cand> legacyFused = Retriever.fuseRrf(BM25_HITS, KNN_HITS, RRF_K, TOP_K * 2);
        List<Retriever.Cand> legacyFinal = Retriever.mmr(legacyFused, 0.7, TOP_K);

        // Pipeline: combined candidates → RRF → Dedup
        List<RetrievalCandidate> combined = new ArrayList<>();
        combined.addAll(toCandidate(BM25_HITS, "bm25"));
        combined.addAll(toCandidate(KNN_HITS, "knn"));

        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        DedupStage dedupStage = new DedupStage();
        RetrievalRequest request = new RetrievalRequest("test query", new float[]{1f}, TOP_K);

        List<RetrievalCandidate> afterRrf = rrfStage.process(request, combined).candidates();
        List<RetrievalCandidate> afterDedup = dedupStage.process(request, afterRrf).candidates();

        // Compare final paths
        List<String> legacyPaths = legacyFinal.stream().map(c -> c.path).toList();
        List<String> pipelinePaths = afterDedup.stream().map(RetrievalCandidate::path).toList();
        assertEquals(legacyPaths, pipelinePaths, "Full pipeline must match legacy path ordering");
    }

    // --- Parity test: BM25-only (no KNN hits) ---

    @Test
    void bm25_only_path_matches_legacy() {
        // Legacy: fuseRrf with empty KNN → mmr
        List<Retriever.Cand> legacyFused = Retriever.fuseRrf(BM25_HITS, List.of(), RRF_K, TOP_K * 2);
        List<Retriever.Cand> legacyFinal = Retriever.mmr(legacyFused, 0.7, TOP_K);

        // Pipeline: only BM25 candidates → RRF → Dedup
        List<RetrievalCandidate> bm25Only = toCandidate(BM25_HITS, "bm25");

        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        DedupStage dedupStage = new DedupStage();
        RetrievalRequest request = new RetrievalRequest("test query", null, TOP_K);

        List<RetrievalCandidate> afterRrf = rrfStage.process(request, bm25Only).candidates();
        List<RetrievalCandidate> afterDedup = dedupStage.process(request, afterRrf).candidates();

        List<String> legacyPaths = legacyFinal.stream().map(c -> c.path).toList();
        List<String> pipelinePaths = afterDedup.stream().map(RetrievalCandidate::path).toList();
        assertEquals(legacyPaths, pipelinePaths, "BM25-only pipeline must match legacy");
    }

    // --- Parity test: duplicate path dedup ---

    @Test
    void duplicate_paths_deduped_same_as_legacy_mmr() {
        // Construct hits where same path appears in both BM25 and KNN
        List<LuceneStore.Hit> bm25 = List.of(
                new LuceneStore.Hit("A", 10f),
                new LuceneStore.Hit("B", 8f),
                new LuceneStore.Hit("C", 5f)
        );
        List<LuceneStore.Hit> knn = List.of(
                new LuceneStore.Hit("B", 0.9f),
                new LuceneStore.Hit("A", 0.8f),
                new LuceneStore.Hit("D", 0.7f)
        );

        // Legacy
        List<Retriever.Cand> legacyFused = Retriever.fuseRrf(bm25, knn, RRF_K, 10);
        List<Retriever.Cand> legacyFinal = Retriever.mmr(legacyFused, 0.7, 3);

        // Pipeline
        List<RetrievalCandidate> combined = new ArrayList<>();
        combined.addAll(toCandidate(bm25, "bm25"));
        combined.addAll(toCandidate(knn, "knn"));

        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        DedupStage dedupStage = new DedupStage();
        RetrievalRequest request = new RetrievalRequest("q", new float[]{1f}, 3);

        List<RetrievalCandidate> afterRrf = rrfStage.process(request, combined).candidates();
        List<RetrievalCandidate> afterDedup = dedupStage.process(request, afterRrf).candidates();

        List<String> legacyPaths = legacyFinal.stream().map(c -> c.path).toList();
        List<String> pipelinePaths = afterDedup.stream().map(RetrievalCandidate::path).toList();
        assertEquals(legacyPaths, pipelinePaths, "Dedup parity must hold for overlapping paths");
    }

    // --- Parity test: score ordering stability ---

    @Test
    void fused_scores_are_always_descending() {
        List<RetrievalCandidate> combined = new ArrayList<>();
        combined.addAll(toCandidate(BM25_HITS, "bm25"));
        combined.addAll(toCandidate(KNN_HITS, "knn"));

        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        RetrievalRequest request = new RetrievalRequest("q", new float[]{1f}, 10);
        List<RetrievalCandidate> fused = rrfStage.process(request, combined).candidates();

        for (int i = 1; i < fused.size(); i++) {
            assertTrue(fused.get(i - 1).score() >= fused.get(i).score(),
                    "Scores must be descending at index " + i);
        }
    }

    // --- Pipeline integration test: full pipeline on fixture data ---

    @Test
    void full_pipeline_matches_legacy_end_to_end() {
        // Legacy path
        List<Retriever.Cand> legacyFused = Retriever.fuseRrf(BM25_HITS, KNN_HITS, RRF_K, TOP_K * 2);
        List<Retriever.Cand> legacyFinal = Retriever.mmr(legacyFused, 0.7, TOP_K);

        // Pipeline path (no real store needed — we simulate BM25/KNN via a custom first stage)
        List<RetrievalCandidate> combined = new ArrayList<>();
        combined.addAll(toCandidate(BM25_HITS, "bm25"));
        combined.addAll(toCandidate(KNN_HITS, "knn"));

        // Inject combined candidates as a "seed" stage
        RetrievalStage seedStage = new RetrievalStage() {
            @Override public String name() { return "seed"; }
            @Override
            public StageOutput process(RetrievalRequest req, List<RetrievalCandidate> in) {
                return StageOutput.of(combined);
            }
        };

        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(seedStage)
                .addStage(new RrfFusionStage(RRF_K))
                .addStage(new DedupStage())
                .build();

        RetrievalRequest request = new RetrievalRequest("test query", new float[]{1f}, TOP_K);
        RetrievalResult result = pipeline.execute(request);

        // Compare
        List<String> legacyPaths = legacyFinal.stream().map(c -> c.path).toList();
        List<String> pipelinePaths = result.paths();
        assertEquals(legacyPaths, pipelinePaths, "Full pipeline must match legacy end-to-end");

        // Trace must record 3 stages
        assertEquals(3, result.trace().entries().size());
        assertEquals("seed", result.trace().entries().get(0).stageName());
        assertEquals("rrf", result.trace().entries().get(1).stageName());
        assertEquals("dedup", result.trace().entries().get(2).stageName());
    }
}

