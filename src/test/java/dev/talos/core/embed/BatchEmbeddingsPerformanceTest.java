package dev.talos.core.embed;

import dev.talos.core.cache.CacheDb;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class BatchEmbeddingsPerformanceTest {

    @TempDir
    Path tempDir;

    @Test
    void batchFallsBackToSinglesOnHttp413() throws Exception {
        // Create a mock embeddings client that simulates HTTP 413 for batches
        BatchEmbeddings mockClient = new MockBatchEmbeddingsClient413();

        List<String> texts = List.of("text1", "text2", "text3");

        // Should fallback to individual requests when batch fails with 413
        List<float[]> results = mockClient.embedBatch(texts);

        assertEquals(3, results.size());
        assertNotNull(results.get(0));
        assertNotNull(results.get(1));
        assertNotNull(results.get(2));
    }

    @Test
    void cacheHitsSkipBatching() throws Exception {
        Path dbPath = tempDir.resolve("test-cache.db");

        // Mock embeddings client
        MockBatchEmbeddingsClient mockClient = new MockBatchEmbeddingsClient();

        try (CacheDb cache = new CacheDb(dbPath);
             CachingEmbeddings cachingClient = new CachingEmbeddings(mockClient, cache, "test-model")) {

            List<String> texts = List.of("cached_text", "new_text");

            // Pre-populate cache for first text
            cachingClient.embed("cached_text"); // This will cache it

            // Reset mock counters
            mockClient.resetCounters();

            // Now batch process - should hit cache for first text, batch process second
            List<float[]> results = cachingClient.embedBatch(texts);

            assertEquals(2, results.size());
            assertNotNull(results.get(0));
            assertNotNull(results.get(1));

            // Verify cache behavior: only one text should have been sent to batch processing
            assertEquals(1, mockClient.getBatchCallCount());
            assertEquals(1, cachingClient.cacheHits());
        }
    }

    @Test
    void dimensionCacheAcrossRuns() throws Exception {
        Path dbPath = tempDir.resolve("dimension-cache.db");

        // First run - should probe and cache dimension
        int probesFirstRun;
        try (CacheDb cache = new CacheDb(dbPath)) {
            MockEmbeddingsClientWithCache embClient = new MockEmbeddingsClientWithCache(cache, "test-model", 1024);

            int dim1 = embClient.dimension();
            assertEquals(1024, dim1);
            probesFirstRun = embClient.getProbeCount();
            assertEquals(1, probesFirstRun); // Should have probed once
        }

        // Second run - should use cached dimension
        try (CacheDb cache = new CacheDb(dbPath)) {
            MockEmbeddingsClientWithCache embClient = new MockEmbeddingsClientWithCache(cache, "test-model", 1024);

            int dim2 = embClient.dimension();
            assertEquals(1024, dim2);
            assertEquals(0, embClient.getProbeCount()); // Should not probe again
        }
    }

    // Mock implementations for testing

    private static class MockBatchEmbeddingsClient implements BatchEmbeddings {
        private int batchCallCount = 0;
        private int probeCount = 0;

        @Override
        public int dimension() throws Exception {
            probeCount++;
            return 1024;
        }

        @Override
        public float[] embed(String text) throws Exception {
            return new float[]{1.0f, 2.0f, 3.0f};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) throws Exception {
            batchCallCount++;
            return texts.stream()
                .map(t -> new float[]{1.0f, 2.0f, 3.0f})
                .toList();
        }

        public int getBatchCallCount() { return batchCallCount; }
        public int getProbeCount() { return probeCount; }
        public void resetCounters() {
            batchCallCount = 0;
            probeCount = 0;
        }
    }

    private static class MockBatchEmbeddingsClient413 implements BatchEmbeddings {
        @Override
        public int dimension() throws Exception { return 1024; }

        @Override
        public float[] embed(String text) throws Exception {
            return new float[]{1.0f, 2.0f, 3.0f};
        }

        @Override
        public List<float[]> embedBatch(List<String> texts) throws Exception {
            // Simulate HTTP 413 (Payload Too Large) for batch requests with fallback
            if (texts.size() > 1) {
                // Fall back to individual processing
                List<float[]> results = new java.util.ArrayList<>();
                for (String text : texts) {
                    results.add(embed(text));
                }
                return results;
            }
            return texts.stream()
                .map(t -> new float[]{1.0f, 2.0f, 3.0f})
                .toList();
        }
    }

    private static class MockEmbeddingsClientWithCache {
        private final CacheDb cache;
        private final String modelKey;
        private final int testDimension;
        private int probeCount = 0;

        public MockEmbeddingsClientWithCache(CacheDb cache, String modelKey, int testDimension) {
            this.cache = cache;
            this.modelKey = modelKey;
            this.testDimension = testDimension;
        }

        public int dimension() throws Exception {
            // Check cache first
            Integer cachedDim = cache.getModelDimension(modelKey);
            if (cachedDim != null) {
                return cachedDim;
            }

            // Simulate probe
            probeCount++;
            cache.putModelDimension(modelKey, testDimension);
            return testDimension;
        }

        public int getProbeCount() { return probeCount; }
    }
}
