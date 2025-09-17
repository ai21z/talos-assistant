package dev.loqj.core.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

public class CacheDbSqlInjectionTest {

    @TempDir
    Path tempDir;

    @Test
    void sqlInjectionProofKeys() {
        // Test various SQL injection attempts in keys
        String[] maliciousKeys = {
            "ollama/bge-m3/abc';--",
            "ollama/bge-m3/abc\";DROP TABLE embedding_cache;--",
            "ollama/bge-m3/abc' OR '1'='1",
            "ollama/bge-m3/abc\\\"; DELETE FROM embedding_cache; --",
            "ollama/bge-m3/abc'; INSERT INTO embedding_cache VALUES ('evil', 1, 'data', 123); --"
        };

        Path dbPath = tempDir.resolve("test-cache.db");
        try (CacheDb cache = new CacheDb(dbPath)) {
            for (String maliciousKey : maliciousKeys) {
                // Test embedding operations
                float[] testVector = {1.0f, 2.0f, 3.0f};

                // Should not throw exception and should safely store/retrieve
                assertDoesNotThrow(() -> cache.putEmbedding(maliciousKey, testVector.length, testVector));

                float[] retrieved = cache.getEmbedding(maliciousKey);
                assertNotNull(retrieved, "Should retrieve embedding for key: " + maliciousKey);
                assertArrayEquals(testVector, retrieved, 0.001f);

                // Test answer operations
                String testAnswer = "Safe answer";
                assertDoesNotThrow(() -> cache.putAnswer(maliciousKey, testAnswer));

                String retrievedAnswer = cache.getAnswer(maliciousKey);
                assertEquals(testAnswer, retrievedAnswer);

                // Verify database integrity - should still have our data
                CacheDb.CacheStats stats = cache.getStats();
                assertTrue(stats.embeddingCount() > 0, "Database should contain embeddings");
                assertTrue(stats.answerCount() > 0, "Database should contain answers");
            }
        }
    }

    @Test
    void sqlInjectionProofWorkspacePaths() {
        // Test SQL injection in workspace paths for session management
        String[] maliciousWorkspaces = {
            "/path/to/workspace'; DROP TABLE sessions; --",
            "/path/with'quote",
            "/path/with\"doublequote",
            "/path\\with\\backslashes"
        };

        Path dbPath = tempDir.resolve("test-cache2.db");
        try (CacheDb cache = new CacheDb(dbPath)) {
            for (String workspace : maliciousWorkspaces) {
                // Should safely create/retrieve session
                String sessionId1 = cache.getOrCreateSession(workspace);
                assertNotNull(sessionId1);

                String sessionId2 = cache.getOrCreateSession(workspace);
                assertEquals(sessionId1, sessionId2, "Should return same session for same workspace");

                // Test memory operations with session
                CacheDb.Memory testMemory = new CacheDb.Memory("test sketch", "[\"entity1\"]");
                assertDoesNotThrow(() -> cache.saveMemory(sessionId1, testMemory));

                CacheDb.Memory retrieved = cache.loadMemory(sessionId1);
                assertEquals(testMemory.sketch(), retrieved.sketch());
                assertEquals(testMemory.entitiesJson(), retrieved.entitiesJson());
            }
        }
    }
}
