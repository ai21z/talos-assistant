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
 *
 * <p>Over-fetches by {@link #FETCH_MULTIPLIER}× the requested topK so that
 * downstream RRF fusion and dedup have a larger candidate pool to work with.
 * Uses the same multiplier as {@link Bm25Stage} for symmetry.
 */
public final class KnnStage implements RetrievalStage {

    /**
     * Multiplier applied to {@code topK} to determine how many candidates
     * to fetch from the KNN index. Symmetric with {@link Bm25Stage#FETCH_MULTIPLIER}.
     */
    static final int FETCH_MULTIPLIER = 3;

    private final CorpusStore store;
    public KnnStage(CorpusStore store) {
        this.store = store;
    }
    @Override
    public String name() { return "knn"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        if (!request.hasVector()) {
            String reason = request.embeddingFailureReason();
            String note = reason != null
                    ? "skipped: embedding failed — " + reason
                    : "skipped: no query vector";
            return StageOutput.of(candidates, note);
        }
        int fetchK = request.topK() * FETCH_MULTIPLIER;
        List<CorpusStore.Hit> hits = store.knn(request.queryVector(), fetchK);
        List<RetrievalCandidate> out = new ArrayList<>(candidates);
        for (CorpusStore.Hit h : hits) {
            out.add(RetrievalCandidate.of(h.path(), h.score(), "knn", h.metadata()));
        }
        return StageOutput.of(out);
    }
}
