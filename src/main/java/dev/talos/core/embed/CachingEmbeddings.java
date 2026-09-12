package dev.talos.core.embed;

import dev.talos.core.cache.CacheDb;
import dev.talos.spi.Embeddings;
import dev.talos.core.util.Hash;

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
        // Earlier batch adapters could persist misordered or partial responses. Do not
        // reuse that unauthenticated generation; leave historical rows untouched.
        this.modelName = "validated-batch-v2\n" + modelName;
    }

    @Override
    public int dimension() throws Exception {
        return delegate.dimension();
    }

    @Override
    public float[] embed(String text) throws Exception {
        String key = Hash.sha1Hex(modelName + "\n" + text);
        float[] cached = db.getEmbedding(key);
        if (EmbeddingsClient.isValidVector(cached)) {
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

            if (EmbeddingsClient.isValidVector(cached)) {
                results.add(cached);
                hits.incrementAndGet();
            } else {
                results.add(null); // Placeholder for cache miss
                cacheMisses.add(text);
                missIndices.add(i);
            }
        }

        int cachedDimension = 0;
        for (float[] vector : results) {
            if (vector == null) continue;
            if (cachedDimension != 0 && vector.length != cachedDimension) {
                throw new IllegalStateException("Inconsistent cached embedding dimensions");
            }
            cachedDimension = vector.length;
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

        // Authenticate the entire response before publishing even one cache entry. A
        // partial/oversized response must not poison the cache or misbind later texts.
        if (batchResults == null || batchResults.size() != cacheMisses.size()) {
            throw new IllegalStateException("Embedding batch cardinality mismatch");
        }
        int expectedDimension = results.stream().filter(java.util.Objects::nonNull)
                .findFirst().map(vector -> vector.length).orElseGet(() ->
                        batchResults.get(0) == null ? 0 : batchResults.get(0).length);
        for (float[] vector : batchResults) {
            if (!EmbeddingsClient.isValidVector(vector) || vector.length != expectedDimension) {
                throw new IllegalStateException("Invalid embedding batch vector or dimension");
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
        try {
            if (delegate instanceof AutoCloseable closeable) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // Transport close is best-effort; cache close must still happen.
        } finally {
            db.close();
        }
    }
}
