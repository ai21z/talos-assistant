package dev.talos.core.retrieval.stages;

import dev.talos.core.ingest.SourceIdentity;
import dev.talos.core.ingest.SourceType;
import dev.talos.core.retrieval.RetrievalCandidate;
import dev.talos.core.retrieval.RetrievalRequest;
import dev.talos.core.retrieval.RetrievalStage;
import dev.talos.core.retrieval.StageOutput;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Post-fusion stage that applies path-based score adjustments to bias
 * retrieval toward production source code and away from tests/docs/config
 * when the query appears to be about implementation.
 *
 * <p>The boost is <strong>query-dependent</strong>: queries that explicitly
 * mention tests, specs, or mocks skip boosting entirely so that test-oriented
 * questions still surface test code.
 *
 * <p>Insert between {@link RrfFusionStage} and {@link RerankerStage} in the
 * default pipeline. Stateless — all decisions are returned via {@link StageOutput}.
 */
public final class SourceBoostStage implements RetrievalStage {

    /** Multiplicative boost applied to production-code paths (e.g., src/main). */
    static final float PROD_BOOST = 1.3f;

    /** Multiplicative penalty applied to test-code paths (e.g., src/test). */
    static final float TEST_PENALTY = 0.7f;

    /** Multiplicative penalty applied to documentation / config paths. */
    static final float DOCS_PENALTY = 0.75f;

    /**
     * Patterns that indicate the query is explicitly about tests or test code.
     * When matched, boosting is skipped to avoid suppressing test results.
     */
    private static final Pattern TEST_INTENT = Pattern.compile(
            "\\b(?:test|tests|spec|specs|mock|mocks|stub|stubs|fixture|fixtures|"
                    + "junit|testcase|test\\s*class|test\\s*method|test\\s*for|"
                    + "unit\\s*test|integration\\s*test|assert)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /** Path fragments that identify production source code. */
    private static final String[] PROD_MARKERS = {
            "src/main/"
    };

    /** Path fragments that identify test code. */
    private static final String[] TEST_MARKERS = {
            "src/test/", "test/", "tests/", "spec/", "specs/",
            "__tests__/", "__test__/"
    };

    /** Path fragments that identify docs/config (not source code). */
    private static final String[] DOCS_MARKERS = {
            "docs/", "doc/", "readme", ".md", ".txt", ".rst", ".adoc",
            ".yaml", ".yml", ".toml", ".json", ".xml", ".properties",
            ".cfg", ".conf", ".ini", ".env"
    };

    @Override
    public String name() { return "source-boost"; }

    @Override
    public StageOutput process(RetrievalRequest request, List<RetrievalCandidate> candidates) {
        if (candidates.isEmpty()) {
            return StageOutput.of(candidates);
        }

        // Skip boosting entirely if the query is explicitly about tests
        if (isTestIntent(request.query())) {
            return StageOutput.of(candidates, "skipped: query has test intent");
        }

        List<RetrievalCandidate> boosted = new ArrayList<>(candidates.size());
        int prodBoosted = 0;
        int testPenalized = 0;
        int docsPenalized = 0;

        for (RetrievalCandidate c : candidates) {
            float factor = classifyCandidate(c);

            if (factor != 1.0f) {
                boosted.add(c.withScore(c.score() * factor).withSource(c.source()));
                if (factor > 1.0f) prodBoosted++;
                else if (isTestOrUnknownTest(c)) testPenalized++;
                else docsPenalized++;
            } else {
                boosted.add(c);
            }
        }

        // Re-sort by adjusted score descending
        boosted.sort(Comparator.comparingDouble(RetrievalCandidate::score).reversed());

        String note = String.format("prod+%d test-%d docs-%d", prodBoosted, testPenalized, docsPenalized);
        return StageOutput.of(boosted, note);
    }

    /**
     * Returns the score multiplier for a candidate, preferring the classified
     * {@link SourceType} from metadata when available, falling back to
     * path-based heuristics for pre-upgrade chunks without source identity.
     */
    static float classifyCandidate(RetrievalCandidate c) {
        SourceIdentity si = c.metadata() != null ? c.metadata().sourceIdentity() : null;
        if (si != null && si.isClassified()) {
            return factorForSourceType(si.type(), c.path());
        }
        // Fallback: legacy path-based classification
        String pathLower = c.path().toLowerCase(Locale.ROOT).replace('\\', '/');
        return classifyPath(pathLower);
    }

    /**
     * Map a {@link SourceType} to a score factor.
     * Test paths still need path-based detection because SourceType does not
     * distinguish production code from test code (both are CODE_FILE).
     */
    static float factorForSourceType(SourceType type, String path) {
        return switch (type) {
            case CODE_FILE -> {
                // CODE_FILE could be prod or test — resolve via path
                String p = path.toLowerCase(Locale.ROOT).replace('\\', '/');
                if (isTestPath(p)) yield TEST_PENALTY;
                if (isProdPath(p)) yield PROD_BOOST;
                yield 1.0f;
            }
            case DOCUMENT -> DOCS_PENALTY;
            case CONFIG   -> DOCS_PENALTY;
            case BUILD_FILE -> 1.0f; // build files are neutral
            case UNKNOWN  -> 1.0f;
        };
    }

    /** Checks if a candidate should count as test-penalized for note formatting. */
    private static boolean isTestOrUnknownTest(RetrievalCandidate c) {
        String p = c.path().toLowerCase(Locale.ROOT).replace('\\', '/');
        return isTestPath(p);
    }

    /**
     * Returns the score multiplier for a given path.
     * Production paths get boosted, test/doc paths get penalized,
     * and unclassified paths pass through unchanged.
     *
     * <p>Legacy path-only classification — used as fallback when metadata
     * does not carry a {@link SourceIdentity}.
     */
    static float classifyPath(String pathLower) {
        // Check test first — more specific than prod (src/test overrides src/main)
        if (isTestPath(pathLower)) return TEST_PENALTY;
        if (isProdPath(pathLower)) return PROD_BOOST;
        if (isDocsPath(pathLower)) return DOCS_PENALTY;
        return 1.0f;
    }

    /** Returns true if the query text suggests the user is asking about tests. */
    static boolean isTestIntent(String query) {
        return query != null && TEST_INTENT.matcher(query).find();
    }

    private static boolean isProdPath(String p) {
        for (String m : PROD_MARKERS) {
            if (p.contains(m)) return true;
        }
        return false;
    }

    private static boolean isTestPath(String p) {
        for (String m : TEST_MARKERS) {
            if (p.contains(m)) return true;
        }
        return false;
    }

    private static boolean isDocsPath(String p) {
        for (String m : DOCS_MARKERS) {
            if (p.contains(m)) return true;
        }
        return false;
    }
}



