package dev.talos.core.retrieval;

import dev.talos.core.retrieval.stages.DedupStage;
import dev.talos.core.retrieval.stages.RrfFusionStage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden retrieval tests: verify that the pipeline stages produce correct,
 * deterministic results on fixed fixture data.
 *
 * These expected values were originally derived from the legacy
 * Retriever.fuseRrf() + Retriever.mmr() code path, confirming parity
 * before that code was removed.
 */
class RetrievalParityTest {

    // --- Fixture data as RetrievalCandidates ---

    private static final List<RetrievalCandidate> BM25_HITS = List.of(
            RetrievalCandidate.of("src/Main.java#0", 12.5f, "bm25"),
            RetrievalCandidate.of("src/Config.java#0", 10.2f, "bm25"),
            RetrievalCandidate.of("src/Utils.java#0", 8.7f, "bm25"),
            RetrievalCandidate.of("README.md#0", 6.1f, "bm25"),
            RetrievalCandidate.of("src/Main.java#1", 5.0f, "bm25"),
            RetrievalCandidate.of("build.gradle#0", 3.2f, "bm25")
    );

    private static final List<RetrievalCandidate> KNN_HITS = List.of(
            RetrievalCandidate.of("src/Config.java#0", 0.95f, "knn"),
            RetrievalCandidate.of("src/Main.java#0", 0.88f, "knn"),
            RetrievalCandidate.of("docs/GUIDE.md#0", 0.82f, "knn"),
            RetrievalCandidate.of("src/Utils.java#0", 0.75f, "knn"),
            RetrievalCandidate.of("src/Service.java#0", 0.70f, "knn")
    );

    private static final int RRF_K = 60;
    private static final int TOP_K = 4;

    /*
     * Pre-computed golden RRF scores (k=60) for the combined BM25+KNN fixture:
     *   src/Config.java#0:  1/62 (bm25 rank 1) + 1/61 (knn rank 0) = 0.032786885...
     *   src/Main.java#0:    1/61 (bm25 rank 0) + 1/62 (knn rank 1) = 0.032786885...
     *   src/Utils.java#0:   1/63 (bm25 rank 2) + 1/64 (knn rank 3) = 0.031498...
     *   docs/GUIDE.md#0:    1/63 (knn rank 2) = 0.015873...
     *   README.md#0:        1/64 (bm25 rank 3) = 0.015625
     *   src/Main.java#1:    1/65 (bm25 rank 4) = 0.015384...
     *   src/Service.java#0: 1/65 (knn rank 4) = 0.015384...
     *   build.gradle#0:     1/66 (bm25 rank 5) = 0.015151...
     *
     * Note: Config and Main have identical sums due to symmetric rank positions.
     * HashMap iteration order is deterministic within a single JVM run but the
     * tie-break between them depends on insertion order into the HashMap.
     * Both orderings are acceptable — the test accepts either order for the top 2.
     */

    private static List<RetrievalCandidate> combinedFixture() {
        var combined = new ArrayList<RetrievalCandidate>();
        combined.addAll(BM25_HITS);
        combined.addAll(KNN_HITS);
        return combined;
    }

    // --- Golden test: RRF fusion path ordering ---

    @Test
    void rrf_fusion_produces_expected_top_paths() {
        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        RetrievalRequest request = new RetrievalRequest("test query", new float[]{1f}, TOP_K);
        List<RetrievalCandidate> fused = rrfStage.process(request, combinedFixture()).candidates();

        // Top 2 are Config and Main (tied score), followed by Utils
        var top2 = List.of(fused.get(0).path(), fused.get(1).path());
        assertTrue(top2.contains("src/Config.java#0"), "Config must be in top 2");
        assertTrue(top2.contains("src/Main.java#0"), "Main must be in top 2");
        assertEquals("src/Utils.java#0", fused.get(2).path());
    }

    @Test
    void rrf_fusion_scores_match_formula() {
        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        RetrievalRequest request = new RetrievalRequest("test query", new float[]{1f}, 10);
        List<RetrievalCandidate> fused = rrfStage.process(request, combinedFixture()).candidates();

        // Config and Main should have identical RRF scores: 1/61 + 1/62
        double expectedTopScore = 1.0 / 61 + 1.0 / 62;
        assertEquals((float) expectedTopScore, fused.get(0).score(), 1e-6);
        assertEquals((float) expectedTopScore, fused.get(1).score(), 1e-6);

        // Utils: 1/63 + 1/64
        double expectedUtilsScore = 1.0 / 63 + 1.0 / 64;
        assertEquals((float) expectedUtilsScore, fused.get(2).score(), 1e-6);
    }

    // --- Golden test: RRF + dedup (full pipeline path) ---

    @Test
    void full_pipeline_produces_expected_final_paths() {
        RetrievalStage seedStage = new RetrievalStage() {
            @Override public String name() { return "seed"; }
            @Override public StageOutput process(RetrievalRequest req, List<RetrievalCandidate> in) {
                return StageOutput.of(combinedFixture());
            }
        };

        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(seedStage)
                .addStage(new RrfFusionStage(RRF_K))
                .addStage(new DedupStage())
                .build();

        RetrievalRequest request = new RetrievalRequest("test query", new float[]{1f}, TOP_K);
        RetrievalResult result = pipeline.execute(request);

        assertEquals(TOP_K, result.candidates().size());
        // Top 2 are Config and Main (tied), then Utils, then one of the remaining
        var top2 = List.of(result.candidates().get(0).path(), result.candidates().get(1).path());
        assertTrue(top2.contains("src/Config.java#0"));
        assertTrue(top2.contains("src/Main.java#0"));
        assertEquals("src/Utils.java#0", result.candidates().get(2).path());

        // Trace must record 3 stages
        assertEquals(3, result.trace().entries().size());
        assertEquals("seed", result.trace().entries().get(0).stageName());
        assertEquals("rrf", result.trace().entries().get(1).stageName());
        assertEquals("dedup", result.trace().entries().get(2).stageName());
    }

    // --- Golden test: BM25-only (no KNN hits) ---

    @Test
    void bm25_only_produces_expected_paths() {
        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        DedupStage dedupStage = new DedupStage();
        RetrievalRequest request = new RetrievalRequest("test query", null, TOP_K);

        List<RetrievalCandidate> afterRrf = rrfStage.process(request, new ArrayList<>(BM25_HITS)).candidates();
        List<RetrievalCandidate> afterDedup = dedupStage.process(request, afterRrf).candidates();

        // With only BM25, order follows original BM25 ranking
        assertEquals(TOP_K, afterDedup.size());
        assertEquals("src/Main.java#0", afterDedup.get(0).path());
        assertEquals("src/Config.java#0", afterDedup.get(1).path());
        assertEquals("src/Utils.java#0", afterDedup.get(2).path());
        assertEquals("README.md#0", afterDedup.get(3).path());
    }

    // --- Golden test: duplicate path dedup ---

    @Test
    void duplicate_paths_deduped_correctly() {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        candidates.add(RetrievalCandidate.of("A", 10f, "bm25"));
        candidates.add(RetrievalCandidate.of("B", 8f, "bm25"));
        candidates.add(RetrievalCandidate.of("C", 5f, "bm25"));
        candidates.add(RetrievalCandidate.of("B", 0.9f, "knn"));
        candidates.add(RetrievalCandidate.of("A", 0.8f, "knn"));
        candidates.add(RetrievalCandidate.of("D", 0.7f, "knn"));

        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        DedupStage dedupStage = new DedupStage();
        RetrievalRequest request = new RetrievalRequest("q", new float[]{1f}, 3);

        List<RetrievalCandidate> afterRrf = rrfStage.process(request, candidates).candidates();
        List<RetrievalCandidate> afterDedup = dedupStage.process(request, afterRrf).candidates();

        // A and B both appear in both sources, so they get boosted above C and D
        var top2 = List.of(afterDedup.get(0).path(), afterDedup.get(1).path());
        assertTrue(top2.contains("A"), "A must be in top 2");
        assertTrue(top2.contains("B"), "B must be in top 2");
        assertEquals(3, afterDedup.size());
    }

    // --- Golden test: score ordering stability ---

    @Test
    void fused_scores_are_always_descending() {
        RrfFusionStage rrfStage = new RrfFusionStage(RRF_K);
        RetrievalRequest request = new RetrievalRequest("q", new float[]{1f}, 10);
        List<RetrievalCandidate> fused = rrfStage.process(request, combinedFixture()).candidates();

        for (int i = 1; i < fused.size(); i++) {
            assertTrue(fused.get(i - 1).score() >= fused.get(i).score(),
                    "Scores must be descending at index " + i);
        }
    }
}
