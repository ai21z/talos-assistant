package dev.talos.spi;

import dev.talos.spi.types.ChunkMetadata;

import java.util.List;

public interface CorpusStore extends AutoCloseable {
    /**
     * A single retrieval hit from the corpus.
     * Carries optional {@link ChunkMetadata} when the store has metadata for this chunk.
     *
     * @param score    relevance score from the retrieval method
     * @param metadata structured chunk metadata, or {@code null} if unavailable
     */
    record Hit(String path, float score, ChunkMetadata metadata) {
        /** Backwards-compatible constructor for hits without metadata. */
        public Hit(String path, float score) {
            this(path, score, null);
        }
    }

    void add(String path, String text, float[] vec);
    void add(String path, String text, float[] vec, String fileHash, Integer chunkId);

    /** Store a chunk with full structured metadata. Implementations that do not support metadata may ignore it. */
    default void add(String path, String text, float[] vec, String fileHash, Integer chunkId, ChunkMetadata metadata) {
        add(path, text, vec, fileHash, chunkId);
    }

    void commit();

    // Named to avoid overloading conflicts with existing LuceneStore methods
    List<Hit> bm25(String queryText, int k);
    List<Hit> knn(float[] qvec, int k);

    String getTextByPath(String path);

    /**
     * Retrieve stored metadata for a chunk by its exact path.
     * Returns {@link ChunkMetadata#empty()} if not available.
     */
    default ChunkMetadata getMetadataByPath(String path) {
        return ChunkMetadata.empty();
    }

    @Override void close();
}
