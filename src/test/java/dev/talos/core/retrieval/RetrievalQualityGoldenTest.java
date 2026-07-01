package dev.talos.core.retrieval;

import dev.talos.core.index.LuceneStore;
import dev.talos.core.rerank.NoOpReranker;
import dev.talos.core.retrieval.stages.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden retrieval quality test suite.
 *
 * <p>Runs 10 golden queries against a synthetic fixture corpus using
 * BM25-only pipeline (no embedding dependency). Each query asserts that
 * at least one expected path appears in the top-K results, ensuring
 * baseline retrieval quality does not silently degrade.
 *
 * <p>The synthetic corpus simulates a small Java project with:
 * <ul>
 *   <li>Source code files (chunked with #N suffixes)</li>
 *   <li>Configuration files</li>
 *   <li>Documentation files</li>
 *   <li>Test files</li>
 * </ul>
 */
class RetrievalQualityGoldenTest {

    @TempDir Path tempDir;

    private LuceneStore store;
    private RetrievalPipeline pipeline;

    // ── Corpus fixture ───────────────────────────────────────────────────

    /**
     * Synthetic corpus: 15 documents simulating a small Java project.
     * Each document has a path and realistic text content that exercises BM25.
     */
    private static final String[][] CORPUS = {
            // ── Source files ──
            {"src/main/java/App.java#0",
                    "public class App implements Application. Main entry point for the HTTP server. " +
                    "Initializes the Spring Boot application context and starts the embedded Tomcat server " +
                    "on port 8080. Handles graceful shutdown via JVM shutdown hook."},

            {"src/main/java/App.java#1",
                    "Configuration of routes and middleware in App class. " +
                    "Registers health check endpoint at /health, Prometheus metrics at /metrics, " +
                    "and the main REST API handlers under /api/v1 prefix."},

            {"src/main/java/UserService.java#0",
                    "UserService handles user registration, authentication, and profile management. " +
                    "Uses BCrypt for password hashing. Validates email format using RFC 5322 regex. " +
                    "Stores user records in PostgreSQL via UserRepository."},

            {"src/main/java/UserService.java#1",
                    "UserService password reset flow. Generates a secure random token with 256 bits of entropy, " +
                    "stores it with 24-hour TTL in the password_reset_tokens table, " +
                    "and sends a reset link via EmailService. Tokens are single-use and expire after first use."},

            {"src/main/java/UserRepository.java#0",
                    "JPA repository interface for User entities. Extends CrudRepository. " +
                    "Custom query methods: findByEmail, findByUsername, existsByEmail. " +
                    "Uses Spring Data JPA named queries for database access."},

            {"src/main/java/SearchEngine.java#0",
                    "Full-text search engine powered by Apache Lucene. " +
                    "Indexes documents with BM25 similarity scoring. " +
                    "Supports boolean queries, phrase matching, and wildcard search. " +
                    "Maintains an inverted index on disk with near-real-time refresh."},

            {"src/main/java/SearchEngine.java#1",
                    "Search engine query parsing and execution. Tokenizes user input, " +
                    "applies stop-word removal and stemming via StandardAnalyzer. " +
                    "Returns ranked results with highlighted snippets. " +
                    "Configurable top-K parameter controls result count."},

            {"src/main/java/CacheManager.java#0",
                    "In-memory cache with LRU eviction policy. Thread-safe via ConcurrentHashMap. " +
                    "Supports TTL-based expiration with a background cleanup thread. " +
                    "Cache hit ratio tracked for monitoring. Serializes entries to SQLite for persistence."},

            {"src/main/java/EmailService.java#0",
                    "Sends transactional emails via SMTP. Supports HTML templates with Thymeleaf. " +
                    "Rate-limited to 100 emails per minute per sender. " +
                    "Handles bounces and delivery failures with exponential backoff retry."},

            // ── Config files ──
            {"config/application.yaml#0",
                    "Application configuration. Database connection pool: HikariCP with max 20 connections. " +
                    "Server port 8080, context path /api. Logging level INFO for production, " +
                    "DEBUG for dev profile. JWT secret key and token expiration 3600 seconds."},

            {"config/logback.xml#0",
                    "Logging configuration using Logback. Console appender with pattern layout. " +
                    "Rolling file appender with 30-day retention, max 100MB per file. " +
                    "Separate log levels: ERROR for com.zaxxer, WARN for org.hibernate, " +
                    "INFO for application root logger."},

            // ── Documentation ──
            {"README.md#0",
                    "Project README. Getting started guide: clone the repository, install Java 21, " +
                    "run gradle build, then gradle bootRun. Architecture overview: three-layer design " +
                    "with REST API, service layer, and data access layer. MIT license."},

            {"docs/architecture.md#0",
                    "Architecture decision records. Chose PostgreSQL over MongoDB for ACID compliance. " +
                    "REST over gRPC for simpler client integration. Lucene for full-text search " +
                    "instead of Elasticsearch to reduce operational complexity. " +
                    "Event sourcing considered but deferred to v2."},

            // ── Test files ──
            {"src/test/java/UserServiceTest.java#0",
                    "Unit tests for UserService. Tests registration with valid email, " +
                    "duplicate email rejection, password strength validation, " +
                    "BCrypt hash verification, and profile update atomic operations. " +
                    "Uses Mockito for mocking UserRepository and EmailService."},

            {"src/test/java/SearchEngineTest.java#0",
                    "Integration tests for SearchEngine. Tests indexing and retrieval round-trip, " +
                    "BM25 scoring accuracy, phrase query matching, wildcard expansion, " +
                    "concurrent index updates, and near-real-time search visibility. " +
                    "Uses temporary directory for index isolation."},
    };

    @BeforeEach
    void setUp() {
        store = new LuceneStore(tempDir, 0); // dim=0 → no vectors, BM25 only
        for (String[] doc : CORPUS) {
            store.add(doc[0], doc[1], null);
        }
        store.commit();

        pipeline = RetrievalPipeline.builder()
                .addStage(new Bm25Stage(store))
                .addStage(new KnnStage(store))
                .addStage(new RrfFusionStage(60))
                .addStage(new RerankerStage(new NoOpReranker()))
                .addStage(new DedupStage())
                .build();
    }

    @AfterEach
    void tearDown() {
        if (store != null) store.close();
    }

    // ── Golden queries ───────────────────────────────────────────────────

    @Test
    @DisplayName("Q1: 'user registration' → UserService")
    void query_userRegistration_findsUserService() {
        assertGoldenQuery(
                "user registration authentication",
                5,
                Set.of("src/main/java/UserService.java#0"),
                "UserService should be the top hit for registration queries"
        );
    }

    @Test
    @DisplayName("Q2: 'password reset token' → UserService#1")
    void query_passwordReset_findsResetFlow() {
        assertGoldenQuery(
                "password reset token email",
                5,
                Set.of("src/main/java/UserService.java#1"),
                "Password reset chunk should appear for reset-related queries"
        );
    }

    @Test
    @DisplayName("Q3: 'Lucene search BM25' → SearchEngine")
    void query_luceneSearch_findsSearchEngine() {
        assertGoldenQuery(
                "Lucene search BM25 scoring",
                5,
                Set.of("src/main/java/SearchEngine.java#0", "src/main/java/SearchEngine.java#1"),
                "SearchEngine chunks should appear for Lucene/BM25 queries"
        );
    }

    @Test
    @DisplayName("Q4: 'database PostgreSQL' → architecture doc")
    void query_database_findsArchitecture() {
        assertGoldenQuery(
                "database PostgreSQL architecture",
                5,
                Set.of("docs/architecture.md#0"),
                "Architecture doc mentioning PostgreSQL should appear"
        );
    }

    @Test
    @DisplayName("Q5: 'cache eviction LRU' → CacheManager")
    void query_cacheEviction_findsCacheManager() {
        assertGoldenQuery(
                "cache eviction LRU memory",
                5,
                Set.of("src/main/java/CacheManager.java#0"),
                "CacheManager should appear for cache-related queries"
        );
    }

    @Test
    @DisplayName("Q6: 'email SMTP template' → EmailService")
    void query_emailSmtp_findsEmailService() {
        assertGoldenQuery(
                "email SMTP template sending",
                5,
                Set.of("src/main/java/EmailService.java#0"),
                "EmailService should appear for email-related queries"
        );
    }

    @Test
    @DisplayName("Q7: 'logging configuration retention' → logback config")
    void query_loggingConfig_findsLogback() {
        assertGoldenQuery(
                "logging configuration file retention",
                5,
                Set.of("config/logback.xml#0"),
                "Logback config should appear for logging queries"
        );
    }

    @Test
    @DisplayName("Q8: 'getting started gradle build' → README")
    void query_gettingStarted_findsReadme() {
        assertGoldenQuery(
                "getting started gradle build",
                5,
                Set.of("README.md#0"),
                "README should appear for getting-started queries"
        );
    }

    @Test
    @DisplayName("Q9: 'unit test Mockito mock' → UserServiceTest")
    void query_unitTestMockito_findsTestFile() {
        assertGoldenQuery(
                "unit test Mockito mock",
                5,
                Set.of("src/test/java/UserServiceTest.java#0"),
                "Test file should appear for Mockito-related queries"
        );
    }

    @Test
    @DisplayName("Q10: 'server port health check endpoint' → App config")
    void query_serverPort_findsAppOrConfig() {
        assertGoldenQuery(
                "server port health check endpoint",
                5,
                Set.of("src/main/java/App.java#1", "config/application.yaml#0"),
                "App routes or config should appear for server/port queries"
        );
    }

    // ── Trace assertions ─────────────────────────────────────────────────

    @Test
    @DisplayName("Trace: all 5 stages recorded for every query")
    void trace_recordsAllFiveStages() {
        RetrievalRequest request = new RetrievalRequest("user registration", null, 5);
        RetrievalResult result = pipeline.execute(request);

        RetrievalTrace trace = result.trace();
        assertEquals(5, trace.entries().size(), "Pipeline should have 5 stages");

        List<String> stageNames = trace.entries().stream()
                .map(RetrievalTrace.Entry::stageName)
                .toList();
        assertEquals(List.of("bm25", "knn", "rrf", "rerank", "dedup"), stageNames,
                "Stage names should follow canonical order");
    }

    @Test
    @DisplayName("Trace: KNN stage skipped when no vector")
    void trace_knnSkippedWithoutVector() {
        RetrievalRequest request = new RetrievalRequest("Lucene search", null, 5);
        RetrievalResult result = pipeline.execute(request);

        RetrievalTrace.Entry knnEntry = result.trace().entries().get(1);
        assertEquals("knn", knnEntry.stageName());
        assertNotNull(knnEntry.note(), "KNN should have a note when skipped");
        assertTrue(knnEntry.note().contains("skipped"),
                "KNN note should mention 'skipped': " + knnEntry.note());
    }

    @Test
    @DisplayName("Trace: BM25 produces candidates for matching query")
    void trace_bm25ProducesCandidates() {
        RetrievalRequest request = new RetrievalRequest("user password", null, 5);
        RetrievalResult result = pipeline.execute(request);

        RetrievalTrace.Entry bm25Entry = result.trace().entries().getFirst();
        assertEquals("bm25", bm25Entry.stageName());
        assertEquals(0, bm25Entry.candidatesBefore(), "BM25 is first stage, should start with 0");
        assertTrue(bm25Entry.candidatesAfter() > 0,
                "BM25 should find matches for 'user password': got " + bm25Entry.candidatesAfter());
    }

    @Test
    @DisplayName("Trace: total pipeline duration is positive")
    void trace_totalDurationPositive() {
        RetrievalRequest request = new RetrievalRequest("search engine", null, 5);
        RetrievalResult result = pipeline.execute(request);

        assertTrue(result.trace().totalNanos() > 0, "Total duration should be positive");
        assertTrue(result.trace().totalMs() > 0, "Total ms should be positive");
    }

    // ── Quality invariants ───────────────────────────────────────────────

    @Test
    @DisplayName("No duplicates in any golden query result")
    void noDuplicatesInResults() {
        String[] queries = {
                "user registration", "password reset", "Lucene search",
                "database PostgreSQL", "cache eviction", "email SMTP"
        };
        for (String query : queries) {
            RetrievalRequest request = new RetrievalRequest(query, null, 5);
            RetrievalResult result = pipeline.execute(request);

            Set<String> paths = result.candidates().stream()
                    .map(RetrievalCandidate::path)
                    .collect(Collectors.toSet());
            assertEquals(result.candidates().size(), paths.size(),
                    "Duplicate paths for query '" + query + "'");
        }
    }

    @Test
    @DisplayName("Scores descending for all golden queries")
    void scoresDescendingForAllQueries() {
        String[] queries = {
                "user registration", "Lucene BM25", "cache LRU",
                "email template", "logging", "getting started"
        };
        for (String query : queries) {
            RetrievalRequest request = new RetrievalRequest(query, null, 5);
            RetrievalResult result = pipeline.execute(request);

            List<RetrievalCandidate> candidates = result.candidates();
            for (int i = 1; i < candidates.size(); i++) {
                assertTrue(candidates.get(i - 1).score() >= candidates.get(i).score(),
                        String.format("Query '%s': score[%d]=%.4f < score[%d]=%.4f",
                                query, i - 1, candidates.get(i - 1).score(),
                                i, candidates.get(i).score()));
            }
        }
    }

    @Test
    @DisplayName("topK is respected")
    void topKRespected() {
        for (int k = 1; k <= 5; k++) {
            RetrievalRequest request = new RetrievalRequest("Lucene search user password", null, k);
            RetrievalResult result = pipeline.execute(request);
            assertTrue(result.candidates().size() <= k,
                    "topK=" + k + " but got " + result.candidates().size() + " results");
        }
    }

    @Test
    @DisplayName("Irrelevant query returns fewer results")
    void irrelevantQueryReturnsFewerResults() {
        // A query with no matching terms should return fewer/no results
        RetrievalRequest request = new RetrievalRequest("xyzzy frobnicator quux", null, 5);
        RetrievalResult result = pipeline.execute(request);

        // With nonsense terms, BM25 should find zero or very few matches
        assertTrue(result.candidates().size() <= 1,
                "Nonsense query should return ≤ 1 result, got " + result.candidates().size());
    }

    // ── Helper ───────────────────────────────────────────────────────────

    /**
     * Asserts that at least one of the expected paths appears in the top-K results.
     */
    private void assertGoldenQuery(String query, int topK, Set<String> expectedPaths, String message) {
        RetrievalRequest request = new RetrievalRequest(query, null, topK);
        RetrievalResult result = pipeline.execute(request);

        Set<String> actualPaths = result.candidates().stream()
                .map(RetrievalCandidate::path)
                .collect(Collectors.toSet());

        boolean found = expectedPaths.stream().anyMatch(actualPaths::contains);
        assertTrue(found,
                message + "\nQuery: '" + query + "'"
                        + "\nExpected one of: " + expectedPaths
                        + "\nActual results: " + actualPaths
                        + "\nTrace:\n" + result.trace().summary());
    }
}




