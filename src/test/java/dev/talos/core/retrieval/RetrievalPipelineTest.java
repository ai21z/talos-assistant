package dev.talos.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for RetrievalPipeline: verifies stage ordering,
 * trace recording, and edge cases.
 */
class RetrievalPipelineTest {

    /** A trivial stage that appends one fixed candidate. */
    static class FixedStage implements RetrievalStage {
        private final String tag;
        FixedStage(String tag) { this.tag = tag; }
        @Override public String name() { return tag; }
        @Override
        public StageOutput process(RetrievalRequest req, List<RetrievalCandidate> in) {
            var out = new ArrayList<>(in);
            out.add(RetrievalCandidate.of("path/" + tag, 1.0f, tag));
            return StageOutput.of(out);
        }
    }

    /** A stage that clears all candidates. */
    static class ClearStage implements RetrievalStage {
        @Override public String name() { return "clear"; }
        @Override
        public StageOutput process(RetrievalRequest req, List<RetrievalCandidate> in) {
            return StageOutput.of(new ArrayList<>());
        }
    }

    @Test
    void pipeline_executes_stages_in_order() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(new FixedStage("a"))
                .addStage(new FixedStage("b"))
                .addStage(new FixedStage("c"))
                .build();

        RetrievalRequest request = new RetrievalRequest("test query", null, 10);
        RetrievalResult result = pipeline.execute(request);

        assertEquals(3, result.candidates().size());
        assertEquals("path/a", result.candidates().get(0).path());
        assertEquals("path/b", result.candidates().get(1).path());
        assertEquals("path/c", result.candidates().get(2).path());
    }

    @Test
    void trace_records_all_stages() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(new FixedStage("x"))
                .addStage(new FixedStage("y"))
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));
        RetrievalTrace trace = result.trace();

        assertEquals(2, trace.entries().size());
        assertEquals("x", trace.entries().get(0).stageName());
        assertEquals("y", trace.entries().get(1).stageName());

        // x: 0 -> 1, y: 1 -> 2
        assertEquals(0, trace.entries().get(0).candidatesBefore());
        assertEquals(1, trace.entries().get(0).candidatesAfter());
        assertEquals(1, trace.entries().get(1).candidatesBefore());
        assertEquals(2, trace.entries().get(1).candidatesAfter());
    }

    @Test
    void trace_timing_is_positive() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(new FixedStage("s"))
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));
        assertTrue(result.trace().totalNanos() >= 0);
    }

    @Test
    void null_stage_is_ignored_by_builder() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(null)
                .addStage(new FixedStage("a"))
                .build();

        assertEquals(1, pipeline.stages().size());
    }

    @Test
    void builder_rejects_empty_pipeline() {
        assertThrows(IllegalStateException.class, () ->
                RetrievalPipeline.builder().build());
    }

    @Test
    void pipeline_handles_stage_returning_empty_list() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(new FixedStage("a"))
                .addStage(new ClearStage())
                .addStage(new FixedStage("b"))
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));
        // After clear, only "b" is added
        assertEquals(1, result.candidates().size());
        assertEquals("path/b", result.candidates().get(0).path());
    }

    @Test
    void pipeline_handles_stage_returning_null() {
        RetrievalStage nullStage = new RetrievalStage() {
            @Override public String name() { return "null-returner"; }
            @Override public StageOutput process(RetrievalRequest r, List<RetrievalCandidate> c) {
                return null;
            }
        };

        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(nullStage)
                .addStage(new FixedStage("after"))
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));
        assertEquals(1, result.candidates().size());
    }

    @Test
    void result_paths_convenience() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(new FixedStage("a"))
                .addStage(new FixedStage("b"))
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));
        List<String> paths = result.paths();
        assertEquals(List.of("path/a", "path/b"), paths);
    }

    @Test
    void trace_summary_is_non_empty() {
        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(new FixedStage("s1"))
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));
        String summary = result.trace().summary();
        assertNotNull(summary);
        assertTrue(summary.contains("s1"));
        assertTrue(summary.contains("ms total"));
    }
}

