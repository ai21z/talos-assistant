package dev.talos.core.retrieval;
import dev.talos.core.ingest.ChunkMetadata;
import java.util.Objects;
/**
 * A single retrieval candidate: a chunk path with a relevance score,
 * a tag indicating which stage produced or last modified it,
 * and optional structured metadata from the corpus.
 */
public record RetrievalCandidate(String path, float score, String source, ChunkMetadata metadata) {
    public RetrievalCandidate {
        Objects.requireNonNull(path, "path must not be null");
        Objects.requireNonNull(source, "source must not be null");
        if (metadata == null) metadata = ChunkMetadata.empty();
    }
    /** Backwards-compatible factory without metadata. */
    public static RetrievalCandidate of(String path, float score, String source) {
        return new RetrievalCandidate(path, score, source, ChunkMetadata.empty());
    }
    /** Factory with metadata. */
    public static RetrievalCandidate of(String path, float score, String source, ChunkMetadata metadata) {
        return new RetrievalCandidate(path, score, source, metadata);
    }
    public RetrievalCandidate withScore(float newScore) {
        return new RetrievalCandidate(path, newScore, source, metadata);
    }
    public RetrievalCandidate withSource(String newSource) {
        return new RetrievalCandidate(path, score, newSource, metadata);
    }
    public RetrievalCandidate withMetadata(ChunkMetadata newMetadata) {
        return new RetrievalCandidate(path, score, source, newMetadata);
    }
}
