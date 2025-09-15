package dev.loqj.core.embed;

import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.spi.Embeddings;
import dev.loqj.core.util.Hash;

public class CachingEmbeddings implements Embeddings, AutoCloseable {
    private final Embeddings delegate;
    private final CacheDb db;
    private final String modelName;
    private final java.util.concurrent.atomic.AtomicLong hits = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicLong misses = new java.util.concurrent.atomic.AtomicLong();

    public CachingEmbeddings(Embeddings delegate, CacheDb db, String modelName) {
        this.delegate = delegate;
        this.db = db;
        this.modelName = modelName;
    }
    @Override public int dimension() throws Exception { return delegate.dimension(); }

    @Override
    public float[] embed(String text) throws Exception {
        String key = Hash.sha1Hex(modelName + "\n" + text);
        float[] cached = db.getEmbedding(key);
        if (cached != null && cached.length > 0) {
            hits.incrementAndGet();
            return cached;
        }
        float[] vec = delegate.embed(text);
        if (vec != null && vec.length > 0) {
            db.putEmbedding(key, vec.length, vec);
            misses.incrementAndGet();
        }
        return vec;
    }

    public long cacheHits() { return hits.get(); }
    public long cacheMisses() { return misses.get(); }

    @Override public void close() { db.close(); }
}
