package dev.talos.core.embed;
import dev.talos.spi.Embeddings;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
/**
 * Decorator that prepends an instruction prefix to every text before
 * delegating to the underlying {@link Embeddings} implementation.
 * <p>
 * Used by instruction-aware models (e.g. Qwen3-Embedding-8B) that require
 * different prefixes for queries vs documents. For models like bge-m3 that
 * do not use instructions, this decorator is simply not applied.
 * <p>
 * Implements {@link BatchEmbeddings} so batch-capable delegates retain
 * their batch path.
 */
public final class InstructionEmbeddings implements BatchEmbeddings, AutoCloseable {
    private final Embeddings delegate;
    private final String prefix;
    public InstructionEmbeddings(Embeddings delegate, String prefix) {
        this.delegate = Objects.requireNonNull(delegate, "delegate must not be null");
        this.prefix = Objects.requireNonNull(prefix, "prefix must not be null");
    }
    @Override
    public int dimension() throws Exception {
        return delegate.dimension();
    }
    @Override
    public float[] embed(String text) throws Exception {
        return delegate.embed(prefix + Objects.toString(text, ""));
    }
    @Override
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        List<String> prefixed = texts.stream()
                .map(t -> prefix + Objects.toString(t, ""))
                .toList();
        if (delegate instanceof BatchEmbeddings batch) {
            return batch.embedBatch(prefixed);
        }
        List<float[]> results = new ArrayList<>(prefixed.size());
        for (String t : prefixed) {
            results.add(delegate.embed(t));
        }
        return results;
    }
    @Override
    public int preferredBatchSize() {
        if (delegate instanceof BatchEmbeddings batch) {
            return batch.preferredBatchSize();
        }
        return BatchEmbeddings.super.preferredBatchSize();
    }
    /** Visible for testing. */
    String prefix() { return prefix; }
    /** Visible for testing. */
    Embeddings delegate() { return delegate; }
    @Override
    public void close() throws Exception {
        if (delegate instanceof AutoCloseable closeable) {
            closeable.close();
        }
    }
}
