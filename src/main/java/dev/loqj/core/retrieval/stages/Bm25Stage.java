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
 *
 * <p>Over-fetches by {@link #FETCH_MULTIPLIER}× the requested topK so that
 * downstream RRF fusion and dedup have a larger candidate pool to work with.
 * The multiplier is intentionally higher than the RRF fusion limit
 * ({@link RrfFusionStage#FUSED_LIMIT_MULTIPLIER}) to ensure each source
 * contributes enough candidates for meaningful rank-based scoring.
 */
public final class Bm25Stage implements RetrievalStage {

    /**
     * Multiplier applied to {@code topK} to determine how many candidates
     * to fetch from the BM25 index. A value of 3 means we fetch 3× topK
     * candidates, giving RRF fusion a richer candidate pool.
     */
    static final int FETCH_MULTIPLIER = 3;

    private final CorpusStore store;
    public Bm25Stage(CorpusStore store) {
        this.store = store;
    }
    @Override
    public String name() { return "bm25"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        int fetchK = request.topK() * FETCH_MULTIPLIER;
        List<CorpusStore.Hit> hits = store.bm25(request.query(), fetchK);
        List<RetrievalCandidate> out = new ArrayList<>(candidates);
        for (CorpusStore.Hit h : hits) {
            out.add(RetrievalCandidate.of(h.path(), h.score(), "bm25", h.metadata()));
        }
        return StageOutput.of(out);
    }
}
