package dev.loqj.core.retrieval.stages;
import dev.loqj.core.rerank.NoOpReranker;
import dev.loqj.core.rerank.Reranker;
import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import dev.loqj.core.retrieval.RetrievalStage;
import dev.loqj.core.retrieval.StageOutput;
import java.util.List;
/**
 * Pipeline stage that delegates to a Reranker implementation.
 * Defaults to NoOpReranker if none is provided.
 */
public final class RerankerStage implements RetrievalStage {
    private final Reranker reranker;
    public RerankerStage(Reranker reranker) {
        this.reranker = (reranker != null) ? reranker : new NoOpReranker();
    }
    public RerankerStage() {
        this(new NoOpReranker());
    }
    @Override
    public String name() { return "rerank"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        return StageOutput.of(reranker.rerank(request.query(), candidates));
    }
}
