package dev.loqj.core.embed;

import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.spi.Embeddings;
import dev.loqj.core.util.Hash;

import java.util.ArrayList;
import java.util.List;

public class CachingEmbeddings implements BatchEmbeddings, AutoCloseable {
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

    @Override
    public int dimension() throws Exception {
        return delegate.dimension();
    }

    @Override
    public float[] embed(String text) throws Exception {
        String key = Hash.sha1Hex(modelName + "\n" + text);
        float[] cached = db.getEmbedding(key);
        if (cached != null && cached.length > 0) {
            hits.incrementAndGet();
            return cached;
        }
        float[] vec = delegate.embed(text);
        if (vec != null && vec.length > 0 && EmbeddingsClient.isValidVector(vec)) {
            db.putEmbedding(key, vec.length, vec);
            misses.incrementAndGet();
        }
        return vec;
    }

    @Override
    public List<float[]> embedBatch(List<String> texts) throws Exception {
        if (texts.isEmpty()) return List.of();

        List<float[]> results = new ArrayList<>(texts.size());
        List<String> cacheMisses = new ArrayList<>();
        List<Integer> missIndices = new ArrayList<>();

        // First pass: check cache for each text
        for (int i = 0; i < texts.size(); i++) {
            String text = texts.get(i);
            String key = Hash.sha1Hex(modelName + "\n" + text);
            float[] cached = db.getEmbedding(key);

            if (cached != null && cached.length > 0) {
                results.add(cached);
                hits.incrementAndGet();
            } else {
                results.add(null); // Placeholder for cache miss
                cacheMisses.add(text);
                missIndices.add(i);
            }
        }

        // If all were cache hits, return immediately
        if (cacheMisses.isEmpty()) {
            return results;
        }

        // Second pass: batch process cache misses
        List<float[]> batchResults;
        if (delegate instanceof BatchEmbeddings batchDelegate) {
            // Use batch processing for cache misses
            batchResults = batchDelegate.embedBatch(cacheMisses);
        } else {
            // Fallback: process individually if delegate doesn't support batching
            batchResults = new ArrayList<>();
            for (String text : cacheMisses) {
                batchResults.add(delegate.embed(text));
            }
        }

        // Third pass: fill in results and cache new embeddings
        for (int i = 0; i < batchResults.size(); i++) {
            float[] vec = batchResults.get(i);
            int originalIndex = missIndices.get(i);
            String text = cacheMisses.get(i);

            results.set(originalIndex, vec);

            if (vec != null && vec.length > 0 && EmbeddingsClient.isValidVector(vec)) {
                // Cache the new embedding
                String key = Hash.sha1Hex(modelName + "\n" + text);
                db.putEmbedding(key, vec.length, vec);
                misses.incrementAndGet();
            }
        }

        return results;
    }

    @Override
    public int preferredBatchSize() {
        if (delegate instanceof BatchEmbeddings batchDelegate) {
            return batchDelegate.preferredBatchSize();
        }
        return 16; // Default fallback
    }

    public long cacheHits() { return hits.get(); }
    public long cacheMisses() { return misses.get(); }

    @Override
    public void close() {
        db.close();
    }
}
