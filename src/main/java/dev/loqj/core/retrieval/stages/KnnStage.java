package dev.loqj.core.retrieval.stages;
import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import dev.loqj.core.retrieval.RetrievalStage;
import dev.loqj.core.retrieval.StageOutput;
import dev.loqj.core.spi.CorpusStore;
import java.util.ArrayList;
import java.util.List;
/**
 * Retrieval stage that performs KNN (vector) search via a CorpusStore.
 * Skipped gracefully if the request has no query vector.
 */
public final class KnnStage implements RetrievalStage {
    private final CorpusStore store;
    public KnnStage(CorpusStore store) {
        this.store = store;
    }
    @Override
    public String name() { return "knn"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        if (!request.hasVector()) {
            return StageOutput.of(candidates, "skipped: no query vector");
        }
        int fetchK = Math.max(request.topK() * 3, request.topK());
        List<CorpusStore.Hit> hits = store.knn(request.queryVector(), fetchK);
        List<RetrievalCandidate> out = new ArrayList<>(candidates);
        for (CorpusStore.Hit h : hits) {
            out.add(RetrievalCandidate.of(h.path(), h.score(), "knn"));
        }
        return StageOutput.of(out);
    }
}
