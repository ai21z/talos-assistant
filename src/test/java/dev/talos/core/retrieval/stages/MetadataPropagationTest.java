package dev.talos.core.retrieval.stages;
import dev.talos.core.ingest.ChunkMetadata;
import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
/**
 * Tests metadata propagation through pipeline stages:
 * - RRF fusion preserves first-seen metadata per path
 * - Dedup preserves metadata on surviving candidates
 * - Reranker preserves metadata passthrough
 */
class MetadataPropagationTest {
    private static final RetrievalRequest REQ = new RetrievalRequest("test query", null, 6);
    @Test
    void rrfFusion_preservesFirstSeenMetadata() {
        var metaBm25 = new ChunkMetadata("java", 1, 10, "## BM25 Source");
        var metaKnn = new ChunkMetadata("java", 1, 10, "## KNN Source");
        var bm25 = RetrievalCandidate.of("src/A.java#0", 5.0f, "bm25", metaBm25);
        var knn = RetrievalCandidate.of("src/A.java#0", 0.9f, "knn", metaKnn);
        var stage = new RrfFusionStage(60);
        var output = stage.process(REQ, List.of(bm25, knn));
        assertEquals(1, output.candidates().size());
        // First-seen (bm25) metadata wins
        assertEquals(metaBm25, output.candidates().get(0).metadata());
    }
    @Test
    void rrfFusion_differentPaths_eachKeepOwnMetadata() {
        var metaA = new ChunkMetadata("java", 1, 10, "## ClassA");
        var metaB = new ChunkMetadata("py", 5, 20, null);
        var a = RetrievalCandidate.of("A.java#0", 5.0f, "bm25", metaA);
        var b = RetrievalCandidate.of("B.py#0", 3.0f, "bm25", metaB);
        var stage = new RrfFusionStage(60);
        var output = stage.process(REQ, List.of(a, b));
        assertEquals(2, output.candidates().size());
        var byPath = new java.util.HashMap<String, ChunkMetadata>();
        for (var c : output.candidates()) byPath.put(c.path(), c.metadata());
        assertEquals(metaA, byPath.get("A.java#0"));
        assertEquals(metaB, byPath.get("B.py#0"));
    }
    @Test
    void dedup_preservesMetadataOnSurvivors() {
        var meta = new ChunkMetadata("java", 10, 25, "## Section");
        var c1 = RetrievalCandidate.of("A.java#0", 5.0f, "rrf", meta);
        var c2 = RetrievalCandidate.of("A.java#0", 3.0f, "rrf", ChunkMetadata.empty());
        var stage = new DedupStage();
        var output = stage.process(REQ, List.of(c1, c2));
        assertEquals(1, output.candidates().size());
        assertEquals(meta, output.candidates().get(0).metadata());
    }
    @Test
    void reranker_preservesMetadata() {
        var meta = new ChunkMetadata("md", 1, 50, "# Getting Started");
        var candidate = RetrievalCandidate.of("README.md#0", 5.0f, "rrf", meta);
        var stage = new RerankerStage();
        var output = stage.process(REQ, List.of(candidate));
        assertEquals(1, output.candidates().size());
        assertEquals(meta, output.candidates().get(0).metadata());
    }
    @Test
    void candidate_withoutMetadata_getsEmpty() {
        var c = RetrievalCandidate.of("file.txt#0", 1.0f, "bm25");
        assertNotNull(c.metadata());
        assertFalse(c.metadata().hasContent());
    }
    @Test
    void candidate_withMetadata_factory() {
        var meta = new ChunkMetadata("java", 10, 25, "## Architecture");
        var c = RetrievalCandidate.of("Foo.java#0", 1.0f, "bm25", meta);
        assertEquals(meta, c.metadata());
    }
    @Test
    void candidate_withScore_preservesMetadata() {
        var meta = new ChunkMetadata("java", 10, 25, "## Arch");
        var c = RetrievalCandidate.of("Foo.java#0", 1.0f, "bm25", meta);
        var rescored = c.withScore(2.0f);
        assertEquals(meta, rescored.metadata());
        assertEquals(2.0f, rescored.score());
    }
    @Test
    void candidate_withSource_preservesMetadata() {
        var meta = new ChunkMetadata("java", 10, 25, "## Arch");
        var c = RetrievalCandidate.of("Foo.java#0", 1.0f, "bm25", meta);
        var retagged = c.withSource("rrf");
        assertEquals(meta, retagged.metadata());
        assertEquals("rrf", retagged.source());
    }
    @Test
    void candidate_withMetadata_replaces() {
        var oldMeta = new ChunkMetadata("java", 1, 5, null);
        var newMeta = new ChunkMetadata("java", 10, 25, "## New");
        var c = RetrievalCandidate.of("Foo.java#0", 1.0f, "bm25", oldMeta);
        var updated = c.withMetadata(newMeta);
        assertEquals(newMeta, updated.metadata());
    }
}

