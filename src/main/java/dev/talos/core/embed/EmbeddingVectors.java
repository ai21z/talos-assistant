package dev.talos.core.embed;

/** Input normalization and vector validation shared by embedding transports and caches. */
public final class EmbeddingVectors {
    private EmbeddingVectors() {
    }

    /**
     * Returns {@code true} if the vector is usable for KNN search.
     * Rejects NaN, Infinity, and all-zero vectors.
     */
    public static boolean isValidVector(float[] vec) {
        if (vec == null || vec.length == 0) return false;
        boolean allZero = true;
        for (float v : vec) {
            if (Float.isNaN(v) || Float.isInfinite(v)) return false;
            if (v != 0.0f) allZero = false;
        }
        return !allZero;
    }

    /**
     * Normalizes text before sending to the embedding model.
     * Strips control characters (except newline/tab), collapses runs of whitespace,
     * and trims - reducing the chance of NaN embeddings from models that choke on
     * unusual input. Empty/blank input becomes a single space to avoid zero-length
     * requests.
     */
    static String normalizeEmbedInput(String text) {
        if (text == null || text.isBlank()) return " ";
        // Strip control chars except \n and \t
        String cleaned = text.replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        // Collapse runs of whitespace
        cleaned = cleaned.replaceAll("[ \\t]+", " ");
        cleaned = cleaned.trim();
        return cleaned.isEmpty() ? " " : cleaned;
    }
}
