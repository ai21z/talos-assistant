package dev.loqj.core.embed;

import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.spi.Embeddings;
import dev.loqj.core.util.Hash;

public class CachingEmbeddings implements Embeddings, AutoCloseable {
    private final Embeddings delegate;
    private final CacheDb db;
    private final String modelName;

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
        if (cached != null && cached.length > 0) return cached;
        float[] vec = delegate.embed(text);
        if (vec != null && vec.length > 0) db.putEmbedding(key, vec.length, vec);
        return vec;
    }
    @Override public void close() { db.close(); }
}
