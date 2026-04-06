package dev.talos.core.retrieval;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
/**
 * Immutable result of a retrieval pipeline execution.
 * Carries the final candidates and the trace of all stage decisions.
 */
public final class RetrievalResult {
    private final RetrievalRequest request;
    private final List<RetrievalCandidate> candidates;
    private final RetrievalTrace trace;
    public RetrievalResult(RetrievalRequest request,
                           List<RetrievalCandidate> candidates,
                           RetrievalTrace trace) {
        this.request = request;
        this.candidates = candidates == null ? List.of() : List.copyOf(candidates);
        this.trace = trace;
    }
    public RetrievalRequest request()                 { return request; }
    public List<RetrievalCandidate> candidates()      { return candidates; }
    public RetrievalTrace trace()                     { return trace; }
    /** Convenience: extract just the chunk paths in order. */
    public List<String> paths() {
        List<String> out = new ArrayList<>(candidates.size());
        for (RetrievalCandidate c : candidates) out.add(c.path());
        return Collections.unmodifiableList(out);
    }
    public boolean isEmpty() { return candidates.isEmpty(); }
    @Override
    public String toString() {
        return "RetrievalResult{candidates=" + candidates.size()
                + ", stages=" + trace.entries().size() + '}';
    }
}
