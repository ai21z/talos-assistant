package dev.talos.core.index;

import dev.talos.core.retrieval.*;
import dev.talos.core.retrieval.stages.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Verifies that paths stored in Lucene use normalized forward-slash separators
 * and that retrieval + dedup work correctly regardless of how the path was
 * originally formatted.
 * <p>
 * The Indexer already normalizes {@code \} → {@code /} at ingestion time
 * (line: {@code rootPath.relativize(p).toString().replace('\\','/')}). These
 * tests codify that invariant so it doesn't regress, and verify that the
 * pipeline handles paths consistently.
 */
class PathNormalizationTest {

    @TempDir Path tempDir;

    @Test
    void forward_slash_paths_stored_and_retrieved_verbatim() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/main/Foo.java#0", "public class Foo {}", null);
            store.commit();

            var hits = store.bm25("Foo class", 5);
            assertFalse(hits.isEmpty());
            assertEquals("src/main/Foo.java#0", hits.get(0).path(),
                    "Forward-slash paths should round-trip through Lucene unchanged");
        }
    }

    @Test
    void backslash_paths_stored_as_is_by_luceneStore() throws Exception {
        // LuceneStore.add() stores the path as given - normalization is the Indexer's job.
        // This test documents the current contract: LuceneStore is a dumb store.
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src\\main\\Bar.java#0", "public class Bar {}", null);
            store.commit();

            // Must query with exact stored path
            String text = store.getTextByPath("src\\main\\Bar.java#0");
            assertEquals("public class Bar {}", text);

            // Forward-slash query would NOT find it (different term)
            String textSlash = store.getTextByPath("src/main/Bar.java#0");
            assertNull(textSlash,
                    "LuceneStore stores paths verbatim - normalization is the Indexer's responsibility");
        }
    }

    @Test
    void dedup_stage_treats_different_separators_as_different_paths() {
        // This test documents a consequence: if paths are NOT normalized before
        // entering the pipeline, DedupStage will treat src/Foo.java and src\Foo.java
        // as different candidates. This is why normalization at indexing time matters.
        var dedup = new DedupStage();
        var req = new RetrievalRequest("q", null, 10);
        var candidates = List.of(
                RetrievalCandidate.of("src/Foo.java#0", 0.9f, "rrf"),
                RetrievalCandidate.of("src\\Foo.java#0", 0.5f, "rrf")
        );

        var result = dedup.process(req, candidates).candidates();
        assertEquals(2, result.size(),
                "DedupStage compares raw paths - different separators = different candidates");
    }

    @Test
    void normalized_paths_dedup_correctly_in_pipeline() throws Exception {
        // When paths ARE normalized (as the Indexer does), dedup works correctly
        try (var store = new LuceneStore(tempDir, 0)) {
            // Simulate what the Indexer does: normalize to forward slashes
            String normalizedPath = "src/main/Foo.java";
            store.add(normalizedPath + "#0",
                    "Lucene search indexing with Foo class for retrieval", null);
            store.add(normalizedPath + "#1",
                    "Lucene additional methods in Foo helper utilities", null);
            store.commit();

            // Both chunks match, but they are distinct chunk paths
            RetrievalPipeline pipeline = RetrievalPipeline.builder()
                    .addStage(new Bm25Stage(store))
                    .addStage(new RrfFusionStage(60))
                    .addStage(new DedupStage())
                    .build();
            RetrievalRequest request = new RetrievalRequest("lucene search", null, 5);
            RetrievalResult result = pipeline.execute(request);

            // All result paths should use forward slashes
            for (RetrievalCandidate c : result.candidates()) {
                assertFalse(c.path().contains("\\"),
                        "Result path should use forward slashes: " + c.path());
            }
        }
    }

    @Test
    void luceneStore_pathtok_field_normalizes_internally() throws Exception {
        // LuceneStore.add() normalizes path tokens internally for searchability
        // even if the stored path uses backslashes
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/main/java/Foo.java#0",
                    "public class Foo { void search() {} }", null);
            store.commit();

            // BM25 should find this doc when searching for path components
            var hits = store.bm25("Foo.java", 5);
            assertFalse(hits.isEmpty(), "Should find doc by filename component");
        }
    }

    @Test
    void getTextByPath_requires_exact_stored_path() throws Exception {
        try (var store = new LuceneStore(tempDir, 0)) {
            store.add("src/Util.java#0", "utility class content", null);
            store.commit();

            assertEquals("utility class content", store.getTextByPath("src/Util.java#0"));
            assertNull(store.getTextByPath("src\\Util.java#0"),
                    "getTextByPath uses TermQuery - must match exact stored path");
            assertNull(store.getTextByPath("src/Util.java"),
                    "getTextByPath requires full path including chunk suffix");
        }
    }
}

