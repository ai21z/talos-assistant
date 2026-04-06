package dev.talos.core.retrieval.stages;
import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import dev.talos.core.retrieval.RetrievalStage;
import dev.talos.core.retrieval.StageOutput;
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
