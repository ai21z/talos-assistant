package dev.loqj.core.retrieval.stages;
import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import dev.loqj.core.retrieval.RetrievalStage;
import dev.loqj.core.retrieval.StageOutput;
import java.util.*;
import java.util.stream.Collectors;
/**
 * Reciprocal Rank Fusion stage. Merges candidates from multiple sources (e.g., BM25 + KNN)
 * into a single fused and ranked list using the formula: score(d) = Σ 1/(k + rank_i + 1).
 */
public final class RrfFusionStage implements RetrievalStage {
    private final int rrfK;
    /** @param rrfK the RRF smoothing constant (typically 60). */
    public RrfFusionStage(int rrfK) {
        this.rrfK = Math.max(1, rrfK);
    }
    public RrfFusionStage() {
        this(60);
    }
    @Override
    public String name() { return "rrf"; }
    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) return StageOutput.of(candidates);
        // Group candidates by source, preserving order within each source
        Map<String, List<RetrievalCandidate>> bySource = new LinkedHashMap<>();
        for (RetrievalCandidate c : candidates) {
            bySource.computeIfAbsent(c.source(), k -> new ArrayList<>()).add(c);
        }
        // Compute RRF score per path across all sources
        Map<String, Double> fusedScores = new HashMap<>();
        for (List<RetrievalCandidate> sourceList : bySource.values()) {
            for (int i = 0; i < sourceList.size(); i++) {
                String path = sourceList.get(i).path();
                double rrfScore = 1.0 / (rrfK + i + 1);
                fusedScores.merge(path, rrfScore, Double::sum);
            }
        }
        // Sort by fused score descending, limit to topK * 2
        int limit = Math.max(request.topK() * 2, request.topK());
        return StageOutput.of(fusedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> RetrievalCandidate.of(e.getKey(), e.getValue().floatValue(), "rrf"))
                .collect(Collectors.toList()));
    }
}
