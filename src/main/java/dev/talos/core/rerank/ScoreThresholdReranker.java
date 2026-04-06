package dev.talos.core.rerank;

import dev.talos.core.retrieval.RetrievalCandidate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Score-based reranker that normalizes, filters, and caps retrieval candidates.
 *
 * <h3>What it does</h3>
 * <ol>
 *   <li><b>Sort</b> — descending by score (highest first)</li>
 *   <li><b>Normalize</b> — scale scores to [0, 1] relative to the top candidate</li>
 *   <li><b>Threshold</b> — drop candidates whose normalized score falls below
 *       {@code minRelativeScore}</li>
 *   <li><b>Cap</b> — limit output to at most {@code maxResults} candidates</li>
 *   <li><b>Re-tag</b> — update the source tag to "rerank" with normalized scores</li>
 * </ol>
 *
 * <h3>Why this matters</h3>
 * <p>After RRF fusion, candidates have scores in a narrow band (typically 0.01–0.03).
 * Without filtering, all fused candidates pass through to context packing — including
 * low-confidence noise that wastes the LLM's context window. This reranker removes
 * candidates that scored far below the best match, ensuring only meaningfully
 * relevant chunks reach the LLM.
 *
 * <h3>Defaults</h3>
 * <ul>
 *   <li>{@code minRelativeScore = 0.25} — drop anything below 25% of the top score</li>
 *   <li>{@code maxResults = 8} — cap at 8 candidates (focused context)</li>
 * </ul>
 *
 * <p>Both values are configurable at construction time and via the config key
 * {@code retrieval.rerank.*} in future config-driven wiring.
 */
public final class ScoreThresholdReranker implements Reranker {

    private static final Logger LOG = LoggerFactory.getLogger(ScoreThresholdReranker.class);

    /** Default: drop candidates below 25% of the top score. */
    public static final double DEFAULT_MIN_RELATIVE_SCORE = 0.25;

    /** Default: return at most 8 candidates. */
    public static final int DEFAULT_MAX_RESULTS = 8;

    private final double minRelativeScore;
    private final int maxResults;

    /**
     * @param minRelativeScore threshold in [0, 1]; candidates below
     *        {@code topScore * minRelativeScore} are dropped
     * @param maxResults       maximum number of candidates to return (≥ 1)
     */
    public ScoreThresholdReranker(double minRelativeScore, int maxResults) {
        this.minRelativeScore = Math.max(0.0, Math.min(1.0, minRelativeScore));
        this.maxResults = Math.max(1, maxResults);
    }

    /** Creates a reranker with default settings. */
    public ScoreThresholdReranker() {
        this(DEFAULT_MIN_RELATIVE_SCORE, DEFAULT_MAX_RESULTS);
    }

    @Override
    public List<RetrievalCandidate> rerank(String query, List<RetrievalCandidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        // 1. Sort descending by score
        List<RetrievalCandidate> sorted = new ArrayList<>(candidates);
        sorted.sort(Comparator.comparingDouble(RetrievalCandidate::score).reversed());

        // 2. Determine the top score for normalization
        float topScore = sorted.getFirst().score();
        if (topScore <= 0f) {
            // All scores are zero or negative — can't meaningfully threshold.
            // Return up to maxResults, preserving input order.
            LOG.debug("Rerank: all scores ≤ 0, returning top {} of {} candidates",
                    Math.min(maxResults, sorted.size()), sorted.size());
            return List.copyOf(sorted.subList(0, Math.min(maxResults, sorted.size())));
        }

        // 3. Normalize, threshold, and cap
        float threshold = (float) (topScore * minRelativeScore);
        List<RetrievalCandidate> result = new ArrayList<>();

        for (RetrievalCandidate c : sorted) {
            if (result.size() >= maxResults) break;
            if (c.score() < threshold) {
                LOG.debug("Rerank: dropping '{}' (score {}, below threshold {})",
                        c.path(), c.score(), threshold);
                continue;
            }
            // Normalize score to [0, 1] and re-tag
            float normalizedScore = c.score() / topScore;
            result.add(c.withScore(normalizedScore).withSource("rerank"));
        }

        int dropped = candidates.size() - result.size();
        if (dropped > 0) {
            LOG.debug("Rerank: {} → {} candidates (dropped {} below threshold {}, max {})",
                    candidates.size(), result.size(), dropped, minRelativeScore, maxResults);
        }

        return List.copyOf(result);
    }

    /** Returns the configured minimum relative score threshold. */
    public double minRelativeScore() { return minRelativeScore; }

    /** Returns the configured maximum result count. */
    public int maxResults() { return maxResults; }
}

