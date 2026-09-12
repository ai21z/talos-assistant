package dev.talos.core.embed;

import dev.talos.core.cache.CacheDb;
import dev.talos.core.util.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CachingEmbeddingsBatchTest {
    @TempDir Path temporary;

    @Test
    void rejectsEveryInvalidBatchBeforeAnyNewCacheWrite() throws Exception {
        List<List<float[]>> invalid = Arrays.asList(null, List.of(), List.of(new float[]{1, 2}),
                List.of(new float[]{1, 2}, new float[]{3, 4}, new float[]{5, 6}),
                Arrays.asList(new float[]{1, 2}, null),
                List.of(new float[]{1, 2}, new float[]{0, 0}),
                List.of(new float[]{1, 2}, new float[]{Float.NaN, 1}),
                List.of(new float[]{1, 2}, new float[]{3}));
        for (int i = 0; i < invalid.size(); i++) {
            try (CacheDb db = new CacheDb(temporary.resolve(i + ".db"))) {
                CachingEmbeddings caching = new CachingEmbeddings(delegate(invalid.get(i)), db, "model");
                assertThrows(IllegalStateException.class, () -> caching.embedBatch(List.of("a", "b")));
                assertNull(db.getEmbedding(key("a")));
                assertNull(db.getEmbedding(key("b")));
                assertEquals(0, caching.cacheMisses());
            }
        }
    }

    @Test
    void completeBatchPreservesPositionsAroundCacheHits() throws Exception {
        try (CacheDb db = new CacheDb(temporary.resolve("positive.db"))) {
            db.putEmbedding(key("cached"), 2, new float[]{9, 8});
            CachingEmbeddings caching = new CachingEmbeddings(delegate(
                    List.of(new float[]{1, 2}, new float[]{3, 4})), db, "model");
            List<float[]> result = caching.embedBatch(List.of("a", "cached", "b"));
            assertArrayEquals(new float[]{1, 2}, result.get(0));
            assertArrayEquals(new float[]{9, 8}, result.get(1));
            assertArrayEquals(new float[]{3, 4}, result.get(2));
            assertArrayEquals(result.get(0), db.getEmbedding(key("a")));
            assertArrayEquals(result.get(2), db.getEmbedding(key("b")));
            assertEquals(1, caching.cacheHits());
            assertEquals(2, caching.cacheMisses());
        }
    }

    @Test
    void oldUnvalidatedCacheGenerationIsNotReused() throws Exception {
        try (CacheDb db = new CacheDb(temporary.resolve("legacy.db"))) {
            db.putEmbedding(Hash.sha1Hex("model\na"), 2, new float[]{9, 9});
            CachingEmbeddings caching = new CachingEmbeddings(delegate(List.of(new float[]{1, 2})), db, "model");
            assertArrayEquals(new float[]{1, 2}, caching.embedBatch(List.of("a")).get(0));
            assertArrayEquals(new float[]{9, 9}, db.getEmbedding(Hash.sha1Hex("model\na")));
            assertEquals(0, caching.cacheHits());
        }
    }

    private static String key(String text) { return Hash.sha1Hex("validated-batch-v2\nmodel\n" + text); }

    private static BatchEmbeddings delegate(List<float[]> vectors) {
        return new BatchEmbeddings() {
            @Override public int dimension() { return 2; }
            @Override public float[] embed(String text) { throw new AssertionError("unexpected single request"); }
            @Override public List<float[]> embedBatch(List<String> texts) { return vectors; }
        };
    }
}
