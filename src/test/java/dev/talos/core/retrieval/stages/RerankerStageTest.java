package dev.talos.core.retrieval.stages;

import dev.talos.core.rerank.NoOpReranker;
import dev.talos.core.rerank.Reranker;
import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RerankerStage and the Reranker interface seam.
 */
class RerankerStageTest {

    @Test
    void noOpReranker_passes_through() {
        RerankerStage stage = new RerankerStage(new NoOpReranker());
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("a", 1.0f, "rrf"),
                RetrievalCandidate.of("b", 0.5f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 5);
        List<RetrievalCandidate> result = stage.process(req, input).candidates();

        assertEquals(input, result);
    }

    @Test
    void default_constructor_uses_noOp() {
        RerankerStage stage = new RerankerStage();
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("x", 0.8f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 5);
        List<RetrievalCandidate> result = stage.process(req, input).candidates();

        assertEquals(input, result);
    }

    @Test
    void custom_reranker_is_invoked() {
        // A simple reranker that reverses the list
        Reranker reverser = (query, candidates) -> {
            var reversed = new java.util.ArrayList<>(candidates);
            java.util.Collections.reverse(reversed);
            return reversed;
        };

        RerankerStage stage = new RerankerStage(reverser);
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("first", 1.0f, "rrf"),
                RetrievalCandidate.of("second", 0.5f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 5);
        List<RetrievalCandidate> result = stage.process(req, input).candidates();

        assertEquals("second", result.get(0).path());
        assertEquals("first", result.get(1).path());
    }

    @Test
    void stage_name_is_rerank() {
        assertEquals("rerank", new RerankerStage().name());
    }

    @Test
    void null_reranker_falls_back_to_noOp() {
        RerankerStage stage = new RerankerStage(null);
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("a", 1.0f, "rrf")
        );

        RetrievalRequest req = new RetrievalRequest("q", null, 5);
        List<RetrievalCandidate> result = stage.process(req, input).candidates();

        assertEquals(input, result);
    }
}
