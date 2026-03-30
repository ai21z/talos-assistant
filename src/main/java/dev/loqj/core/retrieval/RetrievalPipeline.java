package dev.loqj.core.retrieval;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
/**
 * Executes an ordered sequence of RetrievalStage instances against a RetrievalRequest.
 * Records timing and candidate counts into a RetrievalTrace for observability.
 * Immutable after construction; reusable across queries.
 */
public final class RetrievalPipeline {
    private final List<RetrievalStage> stages;
    private RetrievalPipeline(List<RetrievalStage> stages) {
        this.stages = List.copyOf(stages);
    }
    /**
     * Execute the pipeline for the given request.
     * Each stage receives the candidates produced by the prior stage.
     * A fresh RetrievalTrace records all stage decisions.
     */
    public RetrievalResult execute(RetrievalRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        RetrievalTrace trace = new RetrievalTrace();
        List<RetrievalCandidate> candidates = new ArrayList<>();
        for (RetrievalStage stage : stages) {
            int before = candidates.size();
            long t0 = System.nanoTime();
            candidates = stage.process(request, candidates);
            if (candidates == null) candidates = new ArrayList<>();
            long elapsed = System.nanoTime() - t0;
            trace.record(stage.name(), elapsed, before, candidates.size());
        }
        return new RetrievalResult(request, candidates, trace);
    }
    /** Ordered list of stages in this pipeline (for inspection/testing). */
    public List<RetrievalStage> stages() {
        return stages;
    }
    /** Builder for constructing pipelines. */
    public static Builder builder() {
        return new Builder();
    }
    public static final class Builder {
        private final List<RetrievalStage> stages = new ArrayList<>();
        public Builder addStage(RetrievalStage stage) {
            if (stage != null) stages.add(stage);
            return this;
        }
        public RetrievalPipeline build() {
            if (stages.isEmpty()) {
                throw new IllegalStateException("Pipeline must have at least one stage");
            }
            return new RetrievalPipeline(stages);
        }
    }
}
