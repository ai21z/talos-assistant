package dev.loqj.core.retrieval;
import java.util.Objects;
/**
 * A single retrieval candidate: a chunk path with a relevance score
 * and a tag indicating which stage produced or last modified it.
 */
public record RetrievalCandidate(String path, float score, String source) {
    public RetrievalCandidate {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(source, "source must not be null");
    }
    public static RetrievalCandidate of(String path, float score, String source) {
        return new RetrievalCandidate(path, score, source);
    }
    public RetrievalCandidate withScore(float newScore) {
        return new RetrievalCandidate(path, newScore, source);
    }
    public RetrievalCandidate withSource(String newSource) {
        return new RetrievalCandidate(path, score, newSource);
    }
}
