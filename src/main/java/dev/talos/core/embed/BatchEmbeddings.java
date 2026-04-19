package dev.talos.core.embed;

import dev.talos.spi.Embeddings;

import java.util.List;

/**
 * Extended embeddings interface that supports batch processing for performance.
 */
public interface BatchEmbeddings extends Embeddings {

    /**
     * Embed multiple texts in a single request when possible.
     * Preserves ordering - result[i] corresponds to texts[i].
     *
     * @param texts List of texts to embed
     * @return List of embedding vectors, same size and order as input
     * @throws Exception on embedding failure
     */
    List<float[]> embedBatch(List<String> texts) throws Exception;

    /**
     * Get the preferred batch size for this implementation.
     * @return maximum number of texts to batch together
     */
    default int preferredBatchSize() {
        return 16; // Default from acceptance criteria
    }
}
