package dev.talos.core.rerank;
import dev.talos.core.retrieval.RetrievalCandidate;
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
