package dev.talos.spi;

public interface Embeddings {
    /** Return model embedding dimension (may lazily probe). */
    int dimension() throws Exception;

    /** Embed a single text into a float vector. */
    float[] embed(String text) throws Exception;
}
