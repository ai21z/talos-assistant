package dev.talos.core.index;

import dev.talos.core.Config;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.embed.BatchEmbeddings;
import dev.talos.core.ingest.ParserUtil;
import dev.talos.core.util.Hash;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class IndexerSnapshotTest {
    @TempDir Path temporary;

    @Test
    void editDuringEmbeddingNeverLabelsOldTextWithNewHashAndPreservesPriorGeneration() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Path source = workspace.resolve("note.txt");
        Path index = temporary.resolve("index");
        String initial = "retained generation\n";
        String parsed = "text parsed before edit\n";
        String changed = "new file bytes during embedding\n";
        Files.writeString(source, initial);
        AtomicBoolean editDuringEmbedding = new AtomicBoolean();
        BatchEmbeddings embeddings = new BatchEmbeddings() {
            @Override public int dimension() { return 3; }
            @Override public float[] embed(String text) { return new float[]{1, 0, 0}; }
            @Override public List<float[]> embedBatch(List<String> texts) throws Exception {
                if (editDuringEmbedding.getAndSet(false)) Files.writeString(source, changed);
                return texts.stream().map(this::embed).toList();
            }
        };
        Config config = new Config(null);
        config.data.put("rag", Map.of("includes", List.of("**/*.txt"), "excludes", List.of(),
                "vectors", Map.of("enabled", true), "embed_concurrency", 1));
        Indexer indexer = new Indexer(config, () -> embeddings,
                () -> new CacheDb(temporary.resolve("cache.db"))) {
            @Override public Path indexDirFor(Path root) { return index; }
        };
        indexer.index(workspace, false);
        Files.writeString(source, parsed);
        editDuringEmbedding.set(true);
        indexer.index(workspace, false);
        try (LuceneStore store = new LuceneStore(index, 3)) {
            assertEquals(initial.trim(), store.getTextByPath("note.txt#0"));
            assertTrue(store.isUpToDate("note.txt", hash(initial)));
            assertFalse(store.isUpToDate("note.txt", hash(changed)));
        }
        assertEquals(0, indexer.getLastRunStats().getChunksWritten());
        assertEquals(1, indexer.getLastRunStats().getFilesSkipped());
        indexer.index(workspace, false);
        try (LuceneStore store = new LuceneStore(index, 3)) {
            assertEquals(changed.trim(), store.getTextByPath("note.txt#0"));
            assertTrue(store.isUpToDate("note.txt", hash(changed)));
        }
    }

    @Test
    void textParserConsumesSuppliedBytesNotChangedPathAndRejectsMalformedUtf8() throws Exception {
        Path source = temporary.resolve("note.md");
        byte[] original = "# Original\n<!-- hidden -->\ntext".getBytes(StandardCharsets.UTF_8);
        Files.writeString(source, "replacement");
        assertEquals("# Original\n\ntext", ParserUtil.smartParse(source, original));
        assertThrows(java.io.IOException.class, () -> ParserUtil.smartParse(source, new byte[]{(byte) 0xc3, 0x28}));
    }

    @Test
    void oldSchemaCannotKeepIncorrectTextUnderCurrentHash() throws Exception {
        Path workspace = Files.createDirectory(temporary.resolve("workspace"));
        Files.writeString(workspace.resolve("note.txt"), "actual current content");
        Path index = temporary.resolve("index");
        Config config = new Config(null);
        config.data.put("rag", Map.of("includes", List.of("**/*.txt"), "excludes", List.of(),
                "vectors", Map.of("enabled", false)));
        Indexer indexer = new Indexer(config, () -> new dev.talos.spi.Embeddings() {
            @Override public int dimension() { return 0; }
            @Override public float[] embed(String text) { throw new AssertionError("BM25 only"); }
        }, () -> new CacheDb(temporary.resolve("cache.db"))) {
            @Override public Path indexDirFor(Path root) { return index; }
        };
        indexer.index(workspace, false);
        try (LuceneStore store = new LuceneStore(index, 0)) {
            store.removeFileChunks("note.txt");
            store.add("note.txt#0", "wrong historical content", null, hash("actual current content"), 0);
            store.commit();
        }
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        Path metadata = indexer.policyMetadataFile(workspace);
        var oldMetadata = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(metadata.toFile());
        oldMetadata.put("schemaVersion", 4);
        mapper.writeValue(metadata.toFile(), oldMetadata);
        assertFalse(indexer.isPolicyMetadataCurrent(workspace));
        indexer.index(workspace, false);
        try (LuceneStore store = new LuceneStore(index, 0)) {
            assertEquals("actual current content", store.getTextByPath("note.txt#0"));
        }
        assertTrue(indexer.isPolicyMetadataCurrent(workspace));
        assertEquals(1, indexer.getLastRunStats().getFilesEmbedded());
    }

    private static String hash(String text) { return Hash.sha256Hex(text.getBytes(StandardCharsets.UTF_8)); }
}
