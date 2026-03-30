package dev.loqj.core.retrieval;
import java.util.List;
/**
 * A single composable stage in the retrieval pipeline.
 * Each stage receives the current candidates and returns a modified list.
 * The pipeline runner records trace entries automatically.
 */
public interface RetrievalStage {
    /** Short human-readable name for tracing (e.g., "bm25", "knn", "rrf", "dedup"). */
    String name();
    /**
     * Process the current candidate list and return a (possibly modified) list.
     *
     * @param request    the original retrieval request (query, vector, topK)
     * @param candidates current candidates from prior stages (may be empty for first stage)
     * @return updated candidate list
     */
    List<RetrievalCandidate> process(RetrievalRequest request, List<RetrievalCandidate> candidates);

    /**
     * Optional note from the last invocation of {@link #process}, for trace recording.
     * Returns null by default. Stages can override to report skip reasons or diagnostics.
     * Called by the pipeline runner immediately after process().
     */
    default String lastNote() { return null; }
}
