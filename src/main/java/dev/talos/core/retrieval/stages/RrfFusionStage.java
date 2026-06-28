package dev.talos.core.retrieval.stages;
import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import dev.talos.core.retrieval.RetrievalStage;
import dev.talos.core.retrieval.StageOutput;
import dev.talos.spi.types.ChunkMetadata;
import java.util.*;
import java.util.stream.Collectors;
/**
 * Reciprocal Rank Fusion stage. Merges candidates from multiple sources (e.g., BM25 + KNN)
 * into a single fused and ranked list using the formula: score(d) = Σ 1/(k + rank_i + 1).
 * Metadata is preserved using first-seen-wins: the first candidate encountered for a given
 * path determines the metadata carried through fusion.
 *
 * <p>The fused list is limited to {@code topK × }{@link #FUSED_LIMIT_MULTIPLIER} so that
 * downstream stages (reranker, dedup) still have room to drop or reorder candidates
 * before the final topK cut. The multiplier is intentionally lower than the per-source
 * {@link Bm25Stage#FETCH_MULTIPLIER}/{@link KnnStage#FETCH_MULTIPLIER} - RRF has
 * already merged and ranked; keeping 2× is enough headroom.
 */
public final class RrfFusionStage implements RetrievalStage {

    /**
     * After fusion, keep at most {@code topK × FUSED_LIMIT_MULTIPLIER} candidates.
     * This leaves headroom for downstream rerank and dedup before the final topK cut.
     */
    static final int FUSED_LIMIT_MULTIPLIER = 2;

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
        // First-seen metadata per path (same chunk always has the same metadata)
        Map<String, ChunkMetadata> metadataByPath = new HashMap<>();
        for (RetrievalCandidate c : candidates) {
            metadataByPath.putIfAbsent(c.path(), c.metadata());
        }
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
        // Sort by fused score descending, limit to topK × FUSED_LIMIT_MULTIPLIER
        int limit = request.topK() * FUSED_LIMIT_MULTIPLIER;
        return StageOutput.of(fusedScores.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> RetrievalCandidate.of(e.getKey(), e.getValue().floatValue(), "rrf",
                        metadataByPath.getOrDefault(e.getKey(), ChunkMetadata.empty())))
                .collect(Collectors.toList()));
    }
}
