package dev.loqj.core.retrieval;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for RetrievalTrace enhancements: optional notes, skip reasons,
 * and the wasSkipped() helper.
 */
class RetrievalTraceNotesTest {

    @Test
    void record_without_note_has_null_note() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("bm25", 1_000_000L, 0, 5);

        RetrievalTrace.Entry entry = trace.entries().get(0);
        assertNull(entry.note());
        assertFalse(entry.wasSkipped());
    }

    @Test
    void record_with_note_preserves_note() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("knn", 500_000L, 3, 3, "skipped: no query vector");

        RetrievalTrace.Entry entry = trace.entries().get(0);
        assertEquals("skipped: no query vector", entry.note());
    }

    @Test
    void wasSkipped_true_when_count_unchanged_and_note_present() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("knn", 100L, 5, 5, "skipped: no query vector");

        assertTrue(trace.entries().get(0).wasSkipped());
    }

    @Test
    void wasSkipped_false_when_count_changed_even_with_note() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("bm25", 100L, 0, 5, "fetched 5 hits");

        assertFalse(trace.entries().get(0).wasSkipped());
    }

    @Test
    void wasSkipped_false_when_count_unchanged_but_no_note() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("passthrough", 100L, 3, 3);

        assertFalse(trace.entries().get(0).wasSkipped());
    }

    @Test
    void summary_includes_note_when_present() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("bm25", 1_000_000L, 0, 5);
        trace.record("knn", 200_000L, 5, 5, "skipped: no query vector");

        String summary = trace.summary();
        assertTrue(summary.contains("bm25"));
        assertTrue(summary.contains("knn"));
        assertTrue(summary.contains("skipped: no query vector"));
    }

    @Test
    void toString_includes_note() {
        RetrievalTrace.Entry entry = new RetrievalTrace.Entry("knn", 100_000L, 3, 3, "skipped: disabled");
        String str = entry.toString();
        assertTrue(str.contains("(skipped: disabled)"));
    }

    @Test
    void toString_omits_parentheses_when_no_note() {
        RetrievalTrace.Entry entry = new RetrievalTrace.Entry("bm25", 100_000L, 0, 5);
        String str = entry.toString();
        assertFalse(str.contains("("));
    }

    @Test
    void pipeline_captures_knn_skip_note_when_no_vector() {
        // Use a stage that reports a skip note via lastNote()
        RetrievalStage skipStage = new RetrievalStage() {
            @Override public String name() { return "knn"; }
            @Override
            public List<RetrievalCandidate> process(RetrievalRequest r, List<RetrievalCandidate> c) {
                return c; // passthrough
            }
            @Override public String lastNote() { return "skipped: no query vector"; }
        };

        RetrievalStage addStage = new RetrievalStage() {
            @Override public String name() { return "bm25"; }
            @Override
            public List<RetrievalCandidate> process(RetrievalRequest r, List<RetrievalCandidate> c) {
                var out = new ArrayList<>(c);
                out.add(RetrievalCandidate.of("test", 1f, "bm25"));
                return out;
            }
        };

        RetrievalPipeline pipeline = RetrievalPipeline.builder()
                .addStage(addStage)
                .addStage(skipStage)
                .build();

        RetrievalResult result = pipeline.execute(new RetrievalRequest("q", null, 5));

        // bm25 stage: no note
        assertNull(result.trace().entries().get(0).note());
        // knn stage: has skip note
        assertEquals("skipped: no query vector", result.trace().entries().get(1).note());
        assertTrue(result.trace().entries().get(1).wasSkipped());
    }
}

