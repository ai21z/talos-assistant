package dev.loqj.core.retrieval.stages;
import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import dev.loqj.core.retrieval.RetrievalStage;
import dev.loqj.core.retrieval.StageOutput;
import dev.loqj.core.spi.CorpusStore;
import java.util.ArrayList;
import java.util.List;
/**
 * Retrieval stage that performs BM25 (lexical) search via a CorpusStore.
 * Adds BM25 hits to the candidate list without removing existing candidates.
 */
public final class Bm25Stage implements RetrievalStage {
    private final CorpusStore store;
    public Bm25Stage(CorpusStore store) {
        this.store = store;
    }
    @Override
    public String name() { return "bm25"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        int fetchK = Math.max(request.topK() * 3, request.topK());
        List<CorpusStore.Hit> hits = store.bm25(request.query(), fetchK);
        List<RetrievalCandidate> out = new ArrayList<>(candidates);
        for (CorpusStore.Hit h : hits) {
            out.add(RetrievalCandidate.of(h.path(), h.score(), "bm25"));
        }
        return StageOutput.of(out);
    }
}
