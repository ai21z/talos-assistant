package dev.loqj.core.retrieval.stages;

import dev.loqj.core.ingest.ChunkMetadata;
import dev.loqj.core.ingest.MediaType;
import dev.loqj.core.ingest.SourceFormat;
import dev.loqj.core.ingest.SourceIdentity;
import dev.loqj.core.ingest.SourceType;
import dev.loqj.core.retrieval.RetrievalCandidate;
import dev.loqj.core.retrieval.RetrievalRequest;
import dev.loqj.core.retrieval.StageOutput;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SourceBoostStage}: path-based retrieval bias toward
 * production code, with query-dependent skip for test-intent queries.
 */
class SourceBoostStageTest {

    private final SourceBoostStage stage = new SourceBoostStage();

    // ── Path classification ──

    @Test
    void productionPath_boosted() {
        float factor = SourceBoostStage.classifyPath("src/main/java/dev/loqj/core/rag/ragservice.java");
        assertEquals(SourceBoostStage.PROD_BOOST, factor, 0.001f);
    }

    @Test
    void testPath_penalized() {
        float factor = SourceBoostStage.classifyPath("src/test/java/dev/loqj/core/rag/ragservicetest.java");
        assertEquals(SourceBoostStage.TEST_PENALTY, factor, 0.001f);
    }

    @Test
    void docsPath_penalized() {
        float factor = SourceBoostStage.classifyPath("docs/architecture/00-executive-summary.md");
        assertEquals(SourceBoostStage.DOCS_PENALTY, factor, 0.001f);
    }

    @Test
    void unclassifiedPath_unchanged() {
        float factor = SourceBoostStage.classifyPath("scripts/deploy.sh");
        assertEquals(1.0f, factor, 0.001f);
    }

    @Test
    void configFile_penalized() {
        float factor = SourceBoostStage.classifyPath("config/default-config.yaml");
        assertEquals(SourceBoostStage.DOCS_PENALTY, factor, 0.001f);
    }

    // ── Query intent detection ──

    @Test
    void testIntent_detected_for_test_keyword() {
        assertTrue(SourceBoostStage.isTestIntent("show me the test for FooService"));
    }

    @Test
    void testIntent_detected_for_junit() {
        assertTrue(SourceBoostStage.isTestIntent("where is the JUnit class for LuceneStore?"));
    }

    @Test
    void testIntent_detected_for_mock() {
        assertTrue(SourceBoostStage.isTestIntent("how does the mock store work?"));
    }

    @Test
    void testIntent_not_detected_for_implementation_query() {
        assertFalse(SourceBoostStage.isTestIntent("how does the retrieval pipeline work?"));
    }

    @Test
    void testIntent_not_detected_for_null() {
        assertFalse(SourceBoostStage.isTestIntent(null));
    }

    // ── Stage processing ──

    @Test
    void productionCode_outranks_testCode_after_boost() {
        // Setup: test file ranked first by raw score, production file second
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("src/test/java/FooTest.java#0", 0.9f, "rrf"),
                RetrievalCandidate.of("src/main/java/Foo.java#0", 0.8f, "rrf"),
                RetrievalCandidate.of("docs/readme.md#0", 0.7f, "rrf")
        );

        StageOutput output = stage.process(
                new RetrievalRequest("how does Foo work?", null, 10),
                input
        );

        List<RetrievalCandidate> result = output.candidates();
        assertEquals(3, result.size());
        // After boost: prod 0.8*1.3=1.04, test 0.9*0.7=0.63, docs 0.7*0.75=0.525
        assertEquals("src/main/java/Foo.java#0", result.get(0).path(),
                "Production code should be ranked first after boost");
        assertEquals("src/test/java/FooTest.java#0", result.get(1).path());
        assertEquals("docs/readme.md#0", result.get(2).path());
    }

    @Test
    void testIntent_skips_boosting_entirely() {
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("src/test/java/FooTest.java#0", 0.9f, "rrf"),
                RetrievalCandidate.of("src/main/java/Foo.java#0", 0.8f, "rrf")
        );

        StageOutput output = stage.process(
                new RetrievalRequest("show me the test for Foo", null, 10),
                input
        );

        // Scores unchanged — test file still first
        assertEquals("src/test/java/FooTest.java#0", output.candidates().get(0).path());
        assertEquals(0.9f, output.candidates().get(0).score(), 0.001f);
        assertNotNull(output.note());
        assertTrue(output.note().contains("skipped"));
    }

    @Test
    void emptyCandidates_passthrough() {
        StageOutput output = stage.process(
                new RetrievalRequest("anything", null, 5),
                List.of()
        );
        assertTrue(output.candidates().isEmpty());
    }

    @Test
    void mixedPaths_correctNoteFormat() {
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("src/main/java/A.java#0", 1.0f, "rrf"),
                RetrievalCandidate.of("src/test/java/B.java#0", 0.9f, "rrf"),
                RetrievalCandidate.of("docs/arch.md#0", 0.8f, "rrf"),
                RetrievalCandidate.of("scripts/run.sh", 0.7f, "rrf")
        );

        StageOutput output = stage.process(
                new RetrievalRequest("how does A work?", null, 10),
                input
        );

        assertNotNull(output.note());
        assertTrue(output.note().contains("prod+1"));
        assertTrue(output.note().contains("test-1"));
        assertTrue(output.note().contains("docs-1"));
    }

    @Test
    void backslashPaths_normalizedForClassification() {
        // Windows-style path should still be classified
        List<RetrievalCandidate> input = List.of(
                RetrievalCandidate.of("src\\main\\java\\Foo.java#0", 0.5f, "rrf")
        );

        StageOutput output = stage.process(
                new RetrievalRequest("what is Foo?", null, 5),
                input
        );

        // Should be boosted (backslash normalized to forward slash for matching)
        assertTrue(output.candidates().get(0).score() > 0.5f,
                "Backslash path should still get production boost");
    }

    @Test
    void stageName_is_source_boost() {
        assertEquals("source-boost", stage.name());
    }

    // ── Metadata-based classification (SourceType) ──

    @Test
    void candidateWithCodeMetadata_prodPath_boosted() {
        var si = new SourceIdentity("src/main/java/Foo.java", SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL);
        var meta = new ChunkMetadata("java", 1, 20, null, si);
        var c = RetrievalCandidate.of("src/main/java/Foo.java#0", 1.0f, "rrf", meta);

        float factor = SourceBoostStage.classifyCandidate(c);
        assertEquals(SourceBoostStage.PROD_BOOST, factor, 0.001f);
    }

    @Test
    void candidateWithCodeMetadata_testPath_penalized() {
        var si = new SourceIdentity("src/test/java/FooTest.java", SourceType.CODE_FILE, SourceFormat.JAVA, MediaType.TEXTUAL);
        var meta = new ChunkMetadata("java", 1, 20, null, si);
        var c = RetrievalCandidate.of("src/test/java/FooTest.java#0", 1.0f, "rrf", meta);

        float factor = SourceBoostStage.classifyCandidate(c);
        assertEquals(SourceBoostStage.TEST_PENALTY, factor, 0.001f);
    }

    @Test
    void candidateWithDocumentMetadata_penalized() {
        var si = new SourceIdentity("docs/README.md", SourceType.DOCUMENT, SourceFormat.MARKDOWN, MediaType.TEXTUAL);
        var meta = new ChunkMetadata("md", 1, 10, null, si);
        var c = RetrievalCandidate.of("docs/README.md#0", 1.0f, "rrf", meta);

        float factor = SourceBoostStage.classifyCandidate(c);
        assertEquals(SourceBoostStage.DOCS_PENALTY, factor, 0.001f);
    }

    @Test
    void candidateWithConfigMetadata_penalized() {
        var si = new SourceIdentity("config.yaml", SourceType.CONFIG, SourceFormat.YAML, MediaType.STRUCTURED);
        var meta = new ChunkMetadata(null, -1, -1, null, si);
        var c = RetrievalCandidate.of("config.yaml#0", 1.0f, "rrf", meta);

        float factor = SourceBoostStage.classifyCandidate(c);
        assertEquals(SourceBoostStage.DOCS_PENALTY, factor, 0.001f);
    }

    @Test
    void candidateWithBuildMetadata_neutral() {
        var si = new SourceIdentity("Dockerfile", SourceType.BUILD_FILE, SourceFormat.DOCKERFILE, MediaType.TEXTUAL);
        var meta = new ChunkMetadata(null, -1, -1, null, si);
        var c = RetrievalCandidate.of("Dockerfile#0", 1.0f, "rrf", meta);

        float factor = SourceBoostStage.classifyCandidate(c);
        assertEquals(1.0f, factor, 0.001f);
    }

    @Test
    void candidateWithoutMetadata_fallsBackToPathClassification() {
        // No sourceIdentity — should use legacy path-based classification
        var c = RetrievalCandidate.of("src/main/java/Foo.java#0", 1.0f, "rrf");

        float factor = SourceBoostStage.classifyCandidate(c);
        assertEquals(SourceBoostStage.PROD_BOOST, factor, 0.001f);
    }

    @Test
    void factorForSourceType_codeFile_unknownPath_neutral() {
        float factor = SourceBoostStage.factorForSourceType(SourceType.CODE_FILE, "lib/util.java");
        assertEquals(1.0f, factor, 0.001f, "CODE_FILE at unclassifiable path should be neutral");
    }
}

