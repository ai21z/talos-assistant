package dev.loqj.core.retrieval.stages;

import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RrfFusionStage. Verifies RRF scoring formula correctness
 * and edge case handling.
 */
class RrfFusionStageTest {

    private final RrfFusionStage stage = new RrfFusionStage(60);

    @Test
    void single_source_ranks_by_position() {
        List<RetrievalCandidate> candidates = List.of(
                RetrievalCandidate.of("file-a", 10f, "bm25"),
                RetrievalCandidate.of("file-b", 8f, "bm25"),
                RetrievalCandidate.of("file-c", 5f, "bm25")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 10);
        List<RetrievalCandidate> fused = stage.process(req, candidates).candidates();

        // file-a should have highest RRF score: 1/(60+0+1) = 1/61
        assertEquals("file-a", fused.get(0).path());
        assertEquals("file-b", fused.get(1).path());
        assertEquals("file-c", fused.get(2).path());

        // All should be tagged "rrf"
        assertTrue(fused.stream().allMatch(c -> "rrf".equals(c.source())));
    }

    @Test
    void two_sources_fuse_scores() {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        // BM25 results: A rank 0, B rank 1
        candidates.add(RetrievalCandidate.of("A", 10f, "bm25"));
        candidates.add(RetrievalCandidate.of("B", 8f, "bm25"));
        // KNN results: B rank 0, C rank 1
        candidates.add(RetrievalCandidate.of("B", 0.9f, "knn"));
        candidates.add(RetrievalCandidate.of("C", 0.7f, "knn"));

        RetrievalRequest req = new RetrievalRequest("q", new float[]{1f}, 10);
        List<RetrievalCandidate> fused = stage.process(req, candidates).candidates();

        // B appears in both sources: 1/(60+1+1) + 1/(60+0+1) = 1/62 + 1/61
        // A appears only in bm25: 1/(60+0+1) = 1/61
        // C appears only in knn: 1/(60+1+1) = 1/62
        // B > A > C
        assertEquals("B", fused.get(0).path());
        assertEquals("A", fused.get(1).path());
        assertEquals("C", fused.get(2).path());
    }

    @Test
    void rrf_score_values_match_formula() {
        // Single source, single candidate: score should be 1/(k + 0 + 1)
        List<RetrievalCandidate> candidates = List.of(
                RetrievalCandidate.of("X", 5f, "bm25")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 10);
        List<RetrievalCandidate> fused = stage.process(req, candidates).candidates();

        float expected = (float) (1.0 / (60 + 0 + 1));
        assertEquals(expected, fused.get(0).score(), 1e-6);
    }

    @Test
    void empty_candidates_returns_empty() {
        RetrievalRequest req = new RetrievalRequest("q", null, 5);
        List<RetrievalCandidate> fused = stage.process(req, new ArrayList<>()).candidates();
        assertTrue(fused.isEmpty());
    }

    @Test
    void respects_topK_limit() {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(RetrievalCandidate.of("file-" + i, 10f - i, "bm25"));
        }

        // topK=3, limit should be topK*2 = 6
        RetrievalRequest req = new RetrievalRequest("q", null, 3);
        List<RetrievalCandidate> fused = stage.process(req, candidates).candidates();

        assertTrue(fused.size() <= 6, "Should limit to topK*2");
    }

    @Test
    void custom_rrfK_changes_scoring() {
        RrfFusionStage stageK1 = new RrfFusionStage(1);

        List<RetrievalCandidate> candidates = List.of(
                RetrievalCandidate.of("A", 10f, "bm25")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 10);
        List<RetrievalCandidate> fused = stageK1.process(req, candidates).candidates();

        // With k=1: score = 1/(1+0+1) = 0.5
        float expected = (float) (1.0 / (1 + 0 + 1));
        assertEquals(expected, fused.get(0).score(), 1e-6);
    }

    @Test
    void parity_with_original_retriever_fuseRrf() {
        // Golden RRF values for this fixture (k=60):
        // bm25 = [A(rank 0), B(rank 1), C(rank 2)]
        // knn  = [B(rank 0), D(rank 1)]
        // Expected RRF (k=60):
        //   A: 1/61
        //   B: 1/62 (from bm25, rank 1) + 1/61 (from knn, rank 0)
        //   C: 1/63 (from bm25, rank 2)
        //   D: 1/62 (from knn, rank 1)

        List<RetrievalCandidate> candidates = new ArrayList<>();
        // BM25 results
        candidates.add(RetrievalCandidate.of("A", 10f, "bm25"));
        candidates.add(RetrievalCandidate.of("B", 8f, "bm25"));
        candidates.add(RetrievalCandidate.of("C", 5f, "bm25"));
        // KNN results
        candidates.add(RetrievalCandidate.of("B", 0.9f, "knn"));
        candidates.add(RetrievalCandidate.of("D", 0.7f, "knn"));

        RetrievalRequest req = new RetrievalRequest("q", new float[]{1f}, 10);
        List<RetrievalCandidate> fused = stage.process(req, candidates).candidates();

        double scoreA = 1.0 / 61;
        double scoreB = 1.0 / 62 + 1.0 / 61;
        double scoreC = 1.0 / 63;
        double scoreD = 1.0 / 62;

        // B > A > D > C
        assertEquals("B", fused.get(0).path());
        assertEquals("A", fused.get(1).path());
        assertEquals("D", fused.get(2).path());
        assertEquals("C", fused.get(3).path());

        // Verify actual score values
        assertEquals((float) scoreB, fused.get(0).score(), 1e-6);
        assertEquals((float) scoreA, fused.get(1).score(), 1e-6);
        assertEquals((float) scoreD, fused.get(2).score(), 1e-6);
        assertEquals((float) scoreC, fused.get(3).score(), 1e-6);
    }
}
