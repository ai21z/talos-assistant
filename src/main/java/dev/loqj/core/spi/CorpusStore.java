package dev.loqj.core.spi;

import dev.loqj.core.ingest.ChunkMetadata;

import java.util.List;

public interface CorpusStore extends AutoCloseable {
    record Hit(String path, float score) {}

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

    @Override void close();
}
