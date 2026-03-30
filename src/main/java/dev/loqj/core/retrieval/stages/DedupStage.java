package dev.loqj.core.retrieval.stages;
import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import dev.loqj.core.retrieval.RetrievalStage;
import dev.loqj.core.retrieval.StageOutput;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
/**
 * Deduplication stage. Keeps the first (highest-scored) occurrence of each path
 * and trims the list to the requested topK.
 */
public final class DedupStage implements RetrievalStage {
    @Override
    public String name() { return "dedup"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        List<RetrievalCandidate> deduped = new ArrayList<>();
        for (RetrievalCandidate c : candidates) {
            if (seen.add(c.path())) {
                deduped.add(c);
            }
        }
        int limit = Math.min(request.topK(), deduped.size());
        return StageOutput.of(deduped.subList(0, limit));
    }
}
