package dev.talos.core.retrieval.stages;

import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import dev.talos.core.retrieval.StageOutput;
import dev.talos.core.spi.CorpusStore;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests that {@link KnnStage} produces descriptive skip notes depending
 * on whether the vector is simply absent or embedding failed with a reason.
 */
class KnnEmbeddingFailureTest {

    @Test
    void noVector_noReason_genericSkipNote() {
        var store = new StubStore();
        var stage = new KnnStage(store);
        var req = new RetrievalRequest("query", null, 5);

        StageOutput out = stage.process(req, List.of());

        assertNotNull(out.note());
        assertEquals("skipped: no query vector", out.note());
    }

    @Test
    void noVector_withEmbeddingFailureReason_descriptiveSkipNote() {
        var store = new StubStore();
        var stage = new KnnStage(store);
        var req = new RetrievalRequest("query", null, 5,
                "json: unsupported value: NaN");

        StageOutput out = stage.process(req, List.of());

        assertNotNull(out.note());
        assertTrue(out.note().contains("embedding failed"),
                "Note should indicate embedding failure");
        assertTrue(out.note().contains("NaN"),
                "Note should include the failure reason");
    }

    @Test
    void withVector_noSkip_regardless_of_failureReason() {
        var store = new StubStore();
        var stage = new KnnStage(store);
        // Even if a failure reason is set, having a valid vector should proceed
        var req = new RetrievalRequest("query", new float[]{0.1f, 0.2f}, 5,
                "previous failure ignored");

        StageOutput out = stage.process(req, List.of());

        assertNull(out.note(), "Should not skip when vector is present");
    }

    @Test
    void embeddingFailure_preserves_existing_candidates() {
        var store = new StubStore();
        var stage = new KnnStage(store);

        var existing = List.of(
                RetrievalCandidate.of("file1.java#0", 1.0f, "bm25"),
                RetrievalCandidate.of("file2.java#0", 0.8f, "bm25")
        );

        var req = new RetrievalRequest("query", null, 5, "HTTP 500");
        StageOutput out = stage.process(req, existing);

        assertEquals(existing, out.candidates(),
                "Existing candidates should pass through unchanged on skip");
    }

    private static final class StubStore implements CorpusStore {
        @Override public void add(String p, String t, float[] v) {}
        @Override public void add(String p, String t, float[] v, String h, Integer c) {}
        @Override public void commit() {}
        @Override public String getTextByPath(String path) { return null; }
        @Override public void close() {}
        @Override public List<Hit> bm25(String q, int k) { return List.of(); }
        @Override public List<Hit> knn(float[] qvec, int k) { return List.of(); }
    }
}

