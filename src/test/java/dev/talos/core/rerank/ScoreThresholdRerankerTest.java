package dev.talos.core.rerank;

import dev.talos.spi.types.ChunkMetadata;
import dev.talos.core.retrieval.RetrievalCandidate;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link ScoreThresholdReranker}: score normalization,
 * threshold filtering, result capping, and edge cases.
 */
class ScoreThresholdRerankerTest {

    // ═══════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════

    private static RetrievalCandidate cand(String path, float score) {
        return RetrievalCandidate.of(path, score, "rrf");
    }

    private static RetrievalCandidate cand(String path, float score, String source) {
        return RetrievalCandidate.of(path, score, source);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Default constructor
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void default_constructor_uses_documented_defaults() {
        var r = new ScoreThresholdReranker();
        assertEquals(ScoreThresholdReranker.DEFAULT_MIN_RELATIVE_SCORE, r.minRelativeScore());
        assertEquals(ScoreThresholdReranker.DEFAULT_MAX_RESULTS, r.maxResults());
    }

    @Test
    void does_not_depend_on_runtime_log_policy() throws Exception {
        String source = Files.readString(Path.of(
                "src/main/java/dev/talos/core/rerank/ScoreThresholdReranker.java"));
        String baseline = Files.readString(Path.of("config/architecture-boundary-baseline.txt"));

        assertFalse(source.contains("dev.talos.runtime.policy.SafeLogFormatter"), source);
        assertFalse(baseline.contains(
                "src/main/java/dev/talos/core/rerank/ScoreThresholdReranker.java"
                        + "|dev.talos.runtime.policy.SafeLogFormatter"), baseline);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Threshold filtering
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ThresholdFiltering {

        @Test
        void drops_candidates_below_threshold() {
            // Top score = 1.0, threshold at 0.5 → anything < 0.5 dropped
            var reranker = new ScoreThresholdReranker(0.5, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.8f),
                    cand("c.java", 0.5f),
                    cand("d.java", 0.3f),  // below threshold
                    cand("e.java", 0.1f)   // below threshold
            );

            List<RetrievalCandidate> result = reranker.rerank("test query", input);

            assertEquals(3, result.size());
            assertEquals("a.java", result.get(0).path());
            assertEquals("b.java", result.get(1).path());
            assertEquals("c.java", result.get(2).path());
        }

        @Test
        void keeps_all_when_above_threshold() {
            var reranker = new ScoreThresholdReranker(0.1, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.9f),
                    cand("c.java", 0.5f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(3, result.size());
        }

        @Test
        void threshold_relative_to_top_score() {
            // Top score is 0.03 (typical RRF range), threshold at 0.25
            // → absolute threshold = 0.03 * 0.25 = 0.0075
            var reranker = new ScoreThresholdReranker(0.25, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 0.03f),
                    cand("b.java", 0.02f),    // 0.02/0.03 = 0.67 → keep
                    cand("c.java", 0.01f),    // 0.01/0.03 = 0.33 → keep
                    cand("d.java", 0.005f),   // 0.005/0.03 = 0.17 → drop
                    cand("e.java", 0.001f)    // 0.001/0.03 = 0.03 → drop
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(3, result.size());
            assertEquals("a.java", result.get(0).path());
            assertEquals("b.java", result.get(1).path());
            assertEquals("c.java", result.get(2).path());
        }

        @Test
        void zero_threshold_keeps_all() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.001f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);
            assertEquals(2, result.size());
        }

        @Test
        void threshold_at_one_keeps_only_max_score() {
            var reranker = new ScoreThresholdReranker(1.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.99f),  // < 1.0 * 1.0 → dropped
                    cand("c.java", 0.5f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);
            assertEquals(1, result.size());
            assertEquals("a.java", result.get(0).path());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Result capping
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ResultCapping {

        @Test
        void caps_at_max_results() {
            var reranker = new ScoreThresholdReranker(0.0, 3);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.9f),
                    cand("c.java", 0.8f),
                    cand("d.java", 0.7f),
                    cand("e.java", 0.6f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(3, result.size());
            assertEquals("a.java", result.get(0).path());
            assertEquals("b.java", result.get(1).path());
            assertEquals("c.java", result.get(2).path());
        }

        @Test
        void returns_all_when_below_max() {
            var reranker = new ScoreThresholdReranker(0.0, 10);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.5f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);
            assertEquals(2, result.size());
        }

        @Test
        void cap_and_threshold_work_together() {
            // maxResults=3, threshold=0.3 → cap before or after threshold
            var reranker = new ScoreThresholdReranker(0.3, 3);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.8f),
                    cand("c.java", 0.6f),
                    cand("d.java", 0.4f),   // above threshold but beyond cap
                    cand("e.java", 0.2f)    // below threshold
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            // a, b, c pass threshold; d passes threshold but cap=3
            assertEquals(3, result.size());
            assertEquals("a.java", result.get(0).path());
            assertEquals("c.java", result.get(2).path());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Score normalization
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class ScoreNormalization {

        @Test
        void top_candidate_gets_score_one() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 0.03f),
                    cand("b.java", 0.01f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(1.0f, result.get(0).score(), 0.001f);
        }

        @Test
        void scores_proportionally_normalized() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 0.04f),
                    cand("b.java", 0.02f),
                    cand("c.java", 0.01f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(1.0f, result.get(0).score(), 0.001f);
            assertEquals(0.5f, result.get(1).score(), 0.001f);
            assertEquals(0.25f, result.get(2).score(), 0.001f);
        }

        @Test
        void source_tag_updated_to_rerank() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 1.0f, "rrf"),
                    cand("b.java", 0.5f, "source-boost")
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            for (var c : result) {
                assertEquals("rerank", c.source(),
                        "All reranked candidates should have source='rerank'");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Sorting
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class Sorting {

        @Test
        void unsorted_input_is_sorted_descending() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("c.java", 0.1f),
                    cand("a.java", 0.5f),
                    cand("b.java", 0.3f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals("a.java", result.get(0).path());
            assertEquals("b.java", result.get(1).path());
            assertEquals("c.java", result.get(2).path());
        }

        @Test
        void equal_scores_are_stable() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("first.java", 0.5f),
                    cand("second.java", 0.5f),
                    cand("third.java", 0.5f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);
            assertEquals(3, result.size());
            // All equal scores → all normalized to 1.0
            for (var c : result) {
                assertEquals(1.0f, c.score(), 0.001f);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Edge cases
    // ═══════════════════════════════════════════════════════════════════════

    @Nested
    class EdgeCases {

        @Test
        void empty_list_returns_empty() {
            var reranker = new ScoreThresholdReranker();
            List<RetrievalCandidate> result = reranker.rerank("query", List.of());
            assertTrue(result.isEmpty());
        }

        @Test
        void null_list_returns_empty() {
            var reranker = new ScoreThresholdReranker();
            List<RetrievalCandidate> result = reranker.rerank("query", null);
            assertTrue(result.isEmpty());
        }

        @Test
        void single_candidate_always_kept() {
            var reranker = new ScoreThresholdReranker(0.5, 10);
            List<RetrievalCandidate> input = List.of(cand("only.java", 0.01f));

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(1, result.size());
            assertEquals("only.java", result.get(0).path());
            assertEquals(1.0f, result.get(0).score(), 0.001f);
        }

        @Test
        void all_zero_scores_returns_up_to_max() {
            var reranker = new ScoreThresholdReranker(0.5, 2);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", 0.0f),
                    cand("b.java", 0.0f),
                    cand("c.java", 0.0f)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(2, result.size(), "Zero scores → return up to maxResults");
        }

        @Test
        void negative_scores_treated_as_zero() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            List<RetrievalCandidate> input = List.of(
                    cand("a.java", -0.5f),
                    cand("b.java", -1.0f)
            );

            // All scores ≤ 0 → no meaningful normalization
            List<RetrievalCandidate> result = reranker.rerank("query", input);
            assertEquals(2, result.size());
        }

        @Test
        void result_list_is_immutable() {
            var reranker = new ScoreThresholdReranker();
            List<RetrievalCandidate> input = List.of(cand("a.java", 1.0f));

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertThrows(UnsupportedOperationException.class,
                    () -> result.add(cand("x.java", 0.5f)));
        }

        @Test
        void does_not_mutate_input_list() {
            var reranker = new ScoreThresholdReranker(0.5, 2);
            List<RetrievalCandidate> input = new ArrayList<>(List.of(
                    cand("a.java", 1.0f),
                    cand("b.java", 0.5f),
                    cand("c.java", 0.1f)
            ));
            int originalSize = input.size();

            reranker.rerank("query", input);

            assertEquals(originalSize, input.size(), "Input list must not be mutated");
        }

        @Test
        void metadata_preserved_through_reranking() {
            var reranker = new ScoreThresholdReranker(0.0, 100);
            var meta = new ChunkMetadata("java", 10, 25, "## Architecture");
            List<RetrievalCandidate> input = List.of(
                    RetrievalCandidate.of("a.java", 1.0f, "rrf", meta)
            );

            List<RetrievalCandidate> result = reranker.rerank("query", input);

            assertEquals(1, result.size());
            assertEquals("java", result.get(0).metadata().language());
            assertEquals(10, result.get(0).metadata().lineStart());
            assertEquals(25, result.get(0).metadata().lineEnd());
            assertEquals("## Architecture", result.get(0).metadata().headingContext());
        }

        @Test
        void constructor_clamps_min_relative_score() {
            var below = new ScoreThresholdReranker(-0.5, 10);
            assertEquals(0.0, below.minRelativeScore());

            var above = new ScoreThresholdReranker(1.5, 10);
            assertEquals(1.0, above.minRelativeScore());
        }

        @Test
        void constructor_clamps_max_results() {
            var reranker = new ScoreThresholdReranker(0.5, 0);
            assertEquals(1, reranker.maxResults(), "maxResults should be at least 1");

            var negMax = new ScoreThresholdReranker(0.5, -5);
            assertEquals(1, negMax.maxResults());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Implements Reranker interface
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void implements_reranker_interface() {
        Reranker r = new ScoreThresholdReranker();
        assertInstanceOf(Reranker.class, r);
    }

    @Test
    void no_op_comparison_same_result_count() {
        // With threshold=0 and maxResults=100, should return all candidates
        var noop = new NoOpReranker();
        var threshold = new ScoreThresholdReranker(0.0, 100);

        List<RetrievalCandidate> input = List.of(
                cand("a.java", 1.0f),
                cand("b.java", 0.5f),
                cand("c.java", 0.1f)
        );

        assertEquals(noop.rerank("q", input).size(),
                threshold.rerank("q", input).size(),
                "With zero threshold and high cap, should return same count as NoOp");
    }
}

