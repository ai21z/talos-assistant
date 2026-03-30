package dev.loqj.core.rerank;
import dev.loqj.core.retrieval.RetrievalCandidate;
import java.util.List;
/**
 * Second-stage reranker interface. Receives candidates after initial retrieval
 * and returns a rescored/reordered list. Implementations may call an LLM,
 * cross-encoder, or any other scoring mechanism.
 */
public interface Reranker {
    /** Rerank the given candidates for the query. Must preserve or reduce the list size. */
    List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates);
}
