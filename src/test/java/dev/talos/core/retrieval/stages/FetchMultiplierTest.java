package dev.talos.core.retrieval.stages;

import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import dev.talos.core.retrieval.StageOutput;
import dev.talos.spi.CorpusStore;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that verify the named fetch-multiplier constants in
 * {@link Bm25Stage}, {@link KnnStage}, and {@link RrfFusionStage}
 * actually control how many candidates are fetched / retained.
 */
class FetchMultiplierTest {

    @Test
    void bm25Stage_fetches_topK_times_multiplier() {
        int topK = 4;
        int expectedFetch = topK * Bm25Stage.FETCH_MULTIPLIER; // 4 * 3 = 12

        var spy = new SpyStore();
        var stage = new Bm25Stage(spy);
        var req = new RetrievalRequest("test", null, topK);
        stage.process(req, new ArrayList<>());

        assertEquals(expectedFetch, spy.lastBm25K,
                "BM25 should request topK × FETCH_MULTIPLIER docs");
    }

    @Test
    void knnStage_fetches_topK_times_multiplier() {
        int topK = 5;
        int expectedFetch = topK * KnnStage.FETCH_MULTIPLIER; // 5 * 3 = 15

        var spy = new SpyStore();
        var stage = new KnnStage(spy);
        var req = new RetrievalRequest("test", new float[]{1f}, topK);
        stage.process(req, new ArrayList<>());

        assertEquals(expectedFetch, spy.lastKnnK,
                "KNN should request topK × FETCH_MULTIPLIER docs");
    }

    @Test
    void knnStage_skips_when_no_vector() {
        var spy = new SpyStore();
        var stage = new KnnStage(spy);
        var req = new RetrievalRequest("test", null, 5);
        StageOutput out = stage.process(req, List.of());

        assertEquals(-1, spy.lastKnnK, "KNN should not call store.knn when no vector");
        assertNotNull(out.note());
        assertTrue(out.note().contains("skipped"));
    }

    @Test
    void rrfFusionStage_limits_to_topK_times_fusedMultiplier() {
        int topK = 3;
        int expectedLimit = topK * RrfFusionStage.FUSED_LIMIT_MULTIPLIER; // 3 * 2 = 6

        // Feed 20 candidates - RRF should limit output to 6
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            candidates.add(RetrievalCandidate.of("path" + i, 10f - i, "bm25"));
        }

        var stage = new RrfFusionStage(60);
        var req = new RetrievalRequest("q", null, topK);
        List<RetrievalCandidate> fused = stage.process(req, candidates).candidates();

        assertTrue(fused.size() <= expectedLimit,
                "Expected ≤ " + expectedLimit + " fused, got " + fused.size());
    }

    @Test
    void multiplier_constants_are_positive() {
        assertTrue(Bm25Stage.FETCH_MULTIPLIER >= 1);
        assertTrue(KnnStage.FETCH_MULTIPLIER >= 1);
        assertTrue(RrfFusionStage.FUSED_LIMIT_MULTIPLIER >= 1);
    }

    // ──── spy store ────

    /** Minimal CorpusStore that records the fetch-k values passed to bm25/knn. */
    private static final class SpyStore implements CorpusStore {
        int lastBm25K = -1;
        int lastKnnK  = -1;

        @Override public void add(String p, String t, float[] v) {}
        @Override public void add(String p, String t, float[] v, String h, Integer c) {}
        @Override public void commit() {}
        @Override public String getTextByPath(String path) { return null; }
        @Override public void close() {}

        @Override public List<Hit> bm25(String queryText, int k) {
            this.lastBm25K = k;
            return List.of();
        }

        @Override public List<Hit> knn(float[] qvec, int k) {
            this.lastKnnK = k;
            return List.of();
        }
    }
}

