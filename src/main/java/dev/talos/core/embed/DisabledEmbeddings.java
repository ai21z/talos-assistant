package dev.talos.core.embed;

import java.util.List;

/** Explicit embedding provider for configs that intentionally disable vectors. */
final class DisabledEmbeddings implements BatchEmbeddings {
    private final String message;

    DisabledEmbeddings(String provider, String model) {
        this.message = "Embedding provider is disabled"
                + (model == null || model.isBlank() ? "" : " for model '" + model + "'")
                + ". Set embed.provider to 'compat', 'llama_cpp', or 'ollama' to enable vector embeddings.";
    }

    @Override public float[] embed(String text) { throw new UnsupportedOperationException(message); }

    @Override public int dimension() { throw new UnsupportedOperationException(message); }

    @Override public List<float[]> embedBatch(List<String> texts) { throw new UnsupportedOperationException(message); }
}
