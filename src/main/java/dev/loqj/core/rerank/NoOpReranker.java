package dev.loqj.core.rerank;
import dev.loqj.core.retrieval.RetrievalCandidate;
import java.util.List;
/**
 * Passthrough reranker that returns candidates unchanged.
 * Default implementation used when no reranking is configured.
 */
public final class NoOpReranker implements Reranker {
    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        return candidates;
    }
}
