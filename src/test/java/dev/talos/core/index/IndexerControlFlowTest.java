package dev.talos.core.index;

import dev.talos.core.Config;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.embed.BatchEmbeddings;
import dev.talos.spi.Embeddings;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IndexerControlFlowTest {

    @Test
    void interruptedCoordinatorEscapesBeforeCommitAndRestoresInterrupt(@TempDir Path dir)
            throws Exception {
        Path workspace = workspace(dir);
        Path indexDir = dir.resolve("index");
        Indexer indexer = indexer(
                config(),
                indexDir,
                new FixedEmbeddings(),
                dir.resolve("cache.db"));

        try {
            Thread.currentThread().interrupt();
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> indexer.index(workspace, false));

            assertInstanceOf(InterruptedException.class, failure.getCause());
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(Files.exists(indexDir.resolve("talos-index-metadata.json")),
                    "an interrupted coordinator must not publish index metadata");
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void wrappedBatchCancellationEscapesWithoutIndividualFallbackOrCommit(
            @TempDir Path dir
    ) throws Exception {
        Path workspace = workspace(dir);
        Path indexDir = dir.resolve("index");
        CancellationException cancellation = new CancellationException("stop indexing");
        RuntimeException wrapped = new RuntimeException(cancellation);
        AtomicInteger individualCalls = new AtomicInteger();
        BatchEmbeddings embeddings = new BatchEmbeddings() {
            @Override
            public int dimension() {
                return 3;
            }

            @Override
            public float[] embed(String text) {
                individualCalls.incrementAndGet();
                return new float[] {1f, 0f, 0f};
            }

            @Override
            public List<float[]> embedBatch(List<String> texts) {
                throw wrapped;
            }
        };
        Indexer indexer = indexer(
                config(), indexDir, embeddings, dir.resolve("cache.db"));

        RuntimeException observed = assertThrows(
                RuntimeException.class,
                () -> indexer.index(workspace, false));

        assertSame(wrapped, observed);
        assertFalse(Thread.currentThread().isInterrupted());
        assertFalse(Files.exists(indexDir.resolve("talos-index-metadata.json")),
                "a cancelled file task must not publish index metadata");
        assertEquals(0, individualCalls.get(),
                "cancellation must not become an individual embedding fallback");
    }

    private static Indexer indexer(
            Config config,
            Path indexDir,
            Embeddings embeddings,
            Path cachePath
    ) {
        return new Indexer(config, () -> embeddings, () -> new CacheDb(cachePath)) {
            @Override
            public Path indexDirFor(Path root) {
                return indexDir;
            }
        };
    }

    private static Path workspace(Path dir) throws Exception {
        Path workspace = dir.resolve("workspace");
        Path source = workspace.resolve("src").resolve("note.txt");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "control flow must remain typed\n");
        return workspace;
    }

    private static Config config() {
        Config config = new Config();
        config.data.put("rag", Map.of(
                "includes", List.of("**/*.txt"),
                "excludes", List.of(),
                "vectors", Map.of("enabled", true),
                "embed_concurrency", 1));
        return config;
    }

    private static final class FixedEmbeddings implements Embeddings {
        @Override
        public int dimension() {
            return 3;
        }

        @Override
        public float[] embed(String text) {
            return new float[] {1f, 0f, 0f};
        }
    }
}
