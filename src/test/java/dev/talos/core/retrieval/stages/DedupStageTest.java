package dev.talos.core.retrieval.stages;

import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for DedupStage: verifies deduplication by path,
 * score preservation (first occurrence wins), and topK limiting.
 */
class DedupStageTest {

    private final DedupStage stage = new DedupStage();

    @Test
    void removes_duplicate_paths_keeps_first() {
        List<RetrievalCandidate> candidates = List.of(
                RetrievalCandidate.of("A", 0.9f, "rrf"),
                RetrievalCandidate.of("B", 0.8f, "rrf"),
                RetrievalCandidate.of("A", 0.5f, "rrf"),  // dup
                RetrievalCandidate.of("C", 0.4f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 10);
        List<RetrievalCandidate> result = stage.process(req, candidates).candidates();

        assertEquals(3, result.size());
        assertEquals("A", result.get(0).path());
        assertEquals(0.9f, result.get(0).score(), 1e-6);
        assertEquals("B", result.get(1).path());
        assertEquals("C", result.get(2).path());
    }

    @Test
    void limits_to_topK() {
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(RetrievalCandidate.of("file-" + i, 1.0f - i * 0.1f, "rrf"));
        }

        RetrievalRequest req = new RetrievalRequest("q", null, 3);
        List<RetrievalCandidate> result = stage.process(req, candidates).candidates();

        assertEquals(3, result.size());
        assertEquals("file-0", result.get(0).path());
        assertEquals("file-1", result.get(1).path());
        assertEquals("file-2", result.get(2).path());
    }

    @Test
    void empty_input_returns_empty() {
        RetrievalRequest req = new RetrievalRequest("q", null, 5);
        List<RetrievalCandidate> result = stage.process(req, new ArrayList<>()).candidates();
        assertTrue(result.isEmpty());
    }

    @Test
    void fewer_than_topK_returns_all_unique() {
        List<RetrievalCandidate> candidates = List.of(
                RetrievalCandidate.of("A", 1.0f, "rrf"),
                RetrievalCandidate.of("B", 0.9f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 10);
        List<RetrievalCandidate> result = stage.process(req, candidates).candidates();

        assertEquals(2, result.size());
    }

    @Test
    void all_duplicates_returns_one() {
        List<RetrievalCandidate> candidates = List.of(
                RetrievalCandidate.of("same", 1.0f, "bm25"),
                RetrievalCandidate.of("same", 0.8f, "knn"),
                RetrievalCandidate.of("same", 0.5f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 10);
        List<RetrievalCandidate> result = stage.process(req, candidates).candidates();

        assertEquals(1, result.size());
        assertEquals("same", result.get(0).path());
        assertEquals(1.0f, result.get(0).score(), 1e-6);
    }
}
