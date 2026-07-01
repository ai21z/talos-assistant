package dev.talos.core.retrieval;
import java.util.List;
/**
 * A single composable stage in the retrieval pipeline.
 * Each stage receives the current candidates and returns a {@link StageOutput}
 * carrying the updated candidate list and an optional diagnostic note.
 * Stages must be stateless - all per-invocation state is returned in the output.
 * The pipeline runner records trace entries automatically.
 */
public interface RetrievalStage {
    /** Short human-readable name for tracing (e.g., "bm25", "knn", "rrf", "dedup"). */
    String name();
    /**
     * Process the current candidate list and return a stage output.
     *
     * @param request    the original retrieval request (query, vector, topK)
     * @param candidates current candidates from prior stages (may be empty for first stage)
     * @return stage output containing the updated candidate list and an optional note
     */
    StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates);
}
