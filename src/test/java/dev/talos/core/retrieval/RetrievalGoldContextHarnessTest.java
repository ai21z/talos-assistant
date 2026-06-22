package dev.talos.core.retrieval;

import dev.talos.core.index.LuceneStore;
import dev.talos.core.rerank.NoOpReranker;
import dev.talos.core.retrieval.stages.Bm25Stage;
import dev.talos.core.retrieval.stages.DedupStage;
import dev.talos.core.retrieval.stages.KnnStage;
import dev.talos.core.retrieval.stages.RerankerStage;
import dev.talos.core.retrieval.stages.RrfFusionStage;
import dev.talos.spi.types.ChunkMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class RetrievalGoldContextHarnessTest {

    @TempDir Path tempDir;

    @Test
    void catalogCoversTicketedGoldContextDimensions() {
        List<GoldTask> tasks = GoldContextCatalog.tasks();

        assertEquals(20, tasks.size(), "T847 starts with exactly 20 gold-context tasks");
        assertTrue(tasks.stream().allMatch(task -> !task.id().isBlank()));
        assertTrue(tasks.stream().allMatch(task -> !task.query().isBlank()));
        assertTrue(tasks.stream().filter(GoldTask::expectsRetrieval)
                .allMatch(task -> !task.expectedPaths().isEmpty()));
        assertTrue(tasks.stream().anyMatch(task -> !task.expectedSymbols().isEmpty()),
                "Benchmark must record expected symbols");
        assertTrue(tasks.stream().anyMatch(task -> !task.expectedLineRanges().isEmpty()),
                "Benchmark must record expected line ranges");
        assertTrue(tasks.stream().anyMatch(task -> !task.relatedTests().isEmpty()),
                "Benchmark must record related tests");
        assertTrue(tasks.stream().anyMatch(GoldTask::requiresVectorLane),
                "Benchmark must identify vector-sensitive tasks");
        assertTrue(tasks.stream().anyMatch(task -> task.negativeCase() == NegativeCase.PROTECTED_PATH_EXCLUSION),
                "Benchmark must record a protected-path negative case");
        assertTrue(tasks.stream().anyMatch(task -> task.negativeCase() == NegativeCase.PRIVATE_MODE_RAG_DISABLED),
                "Benchmark must record a private-mode negative case");
    }

    @Test
    void bm25OnlyRunComputesRetrievalQualityMetrics() {
        try (LuceneStore store = new LuceneStore(tempDir.resolve("bm25"), 0)) {
            GoldContextCatalog.index(store, false);
            RetrievalPipeline pipeline = defaultPipeline(store);

            HarnessRun run = GoldContextHarness.evaluate(
                    "BM25_ONLY",
                    GoldContextCatalog.tasks(),
                    store,
                    pipeline,
                    false);

            GoldMetrics metrics = run.metrics();
            assertEquals(20, metrics.taskCount());
            assertEquals(18, metrics.retrievalTaskCount());
            assertEquals(2, metrics.negativeTaskCount());
            assertMetricRange(metrics.fileRecall());
            assertMetricRange(metrics.filePrecision());
            assertMetricRange(metrics.mrr());
            assertMetricRange(metrics.ndcg());
            assertMetricRange(metrics.junkContextRate());
            assertMetricRange(metrics.missingCoreEvidenceRate());
            assertTrue(metrics.fileRecall() >= 0.60,
                    "BM25 fixture should find most core expected files, got " + metrics.fileRecall());
            assertTrue(metrics.laneContributionCount("bm25") > 0,
                    "BM25 lane contribution must be measured");
            assertEquals(0, metrics.laneContributionCount("knn"),
                    "BM25-only run must not record KNN contribution");
            assertTrue(run.negativeCase("protected-path-exclusion").forbiddenPathsAbsent());
            assertTrue(run.negativeCase("private-mode-rag-disabled").retrievalSkipped());
        }
    }

    @Test
    void hybridRunRecordsVectorLaneContributionWhenVectorsAreAvailable() {
        try (LuceneStore store = new LuceneStore(tempDir.resolve("hybrid"), 3)) {
            GoldContextCatalog.index(store, true);
            RetrievalPipeline pipeline = defaultPipeline(store);

            HarnessRun run = GoldContextHarness.evaluate(
                    "HYBRID_SYNTHETIC",
                    GoldContextCatalog.tasks(),
                    store,
                    pipeline,
                    true);

            GoldMetrics metrics = run.metrics();
            assertEquals(20, metrics.taskCount());
            assertTrue(metrics.laneContributionCount("bm25") > 0,
                    "Hybrid run must still record lexical lane contribution");
            assertTrue(metrics.laneContributionCount("knn") > 0,
                    "Hybrid run must record vector lane contribution when query vectors exist");
            assertTrue(run.result("semantic-embedding-endpoint").laneContributions().contains("knn"),
                    "Vector-sensitive embedding task should show KNN lane contribution");
            assertTrue(metrics.fileRecall() >= 0.60,
                    "Hybrid fixture should find most core expected files, got " + metrics.fileRecall());
        }
    }

    @Test
    void trackedReportPinsHarnessScopeAndNonAuthorization() throws IOException {
        Path report = Path.of("work-cycle-docs/reports/t847-retrieval-evidence-and-gold-context-harness.md");

        assertTrue(Files.exists(report), "T847 report must be tracked with harness scope and measured baseline");
        String text = Files.readString(report);
        assertAll(
                () -> assertTrue(text.contains("20 gold-context tasks"), text),
                () -> assertTrue(text.contains("BM25-only"), text),
                () -> assertTrue(text.contains("hybrid"), text),
                () -> assertTrue(text.contains("file recall"), text),
                () -> assertTrue(text.contains("junk context"), text),
                () -> assertTrue(text.contains("missing-core-evidence"), text),
                () -> assertTrue(text.contains("protected-path negative"), text),
                () -> assertTrue(text.contains("private-mode negative"), text),
                () -> assertTrue(text.contains("T847 does not change retrieval ranking"), text)
        );
    }

    private static RetrievalPipeline defaultPipeline(LuceneStore store) {
        return RetrievalPipeline.builder()
                .addStage(new Bm25Stage(store))
                .addStage(new KnnStage(store))
                .addStage(new RrfFusionStage(60))
                .addStage(new RerankerStage(new NoOpReranker()))
                .addStage(new DedupStage())
                .build();
    }

    private static void assertMetricRange(double value) {
        assertFalse(Double.isNaN(value), "metric must not be NaN");
        assertTrue(value >= 0.0 && value <= 1.0, "metric out of range: " + value);
    }

    private enum NegativeCase {
        NONE,
        PROTECTED_PATH_EXCLUSION,
        PRIVATE_MODE_RAG_DISABLED
    }

    private record GoldDocument(
            String path,
            String text,
            ChunkMetadata metadata,
            float[] vector
    ) {}

    private record GoldTask(
            String id,
            String query,
            int topK,
            Set<String> expectedPaths,
            Set<String> expectedSymbols,
            List<LineRange> expectedLineRanges,
            Set<String> relatedTests,
            Set<String> forbiddenPaths,
            boolean requiresVectorLane,
            NegativeCase negativeCase,
            float[] queryVector
    ) {
        boolean expectsRetrieval() {
            return negativeCase == NegativeCase.NONE;
        }
    }

    private record LineRange(String path, int start, int end) {
        boolean intersects(ChunkMetadata metadata) {
            return metadata != null
                    && metadata.lineStart() <= end
                    && metadata.lineEnd() >= start;
        }
    }

    private record TaskResult(
            GoldTask task,
            List<String> actualPaths,
            Set<String> laneContributions,
            int firstRelevantRank,
            int relevantRetrieved,
            boolean forbiddenPathsAbsent,
            boolean retrievalSkipped,
            boolean lineRangeHit
    ) {}

    private record NegativeCaseResult(boolean forbiddenPathsAbsent, boolean retrievalSkipped) {}

    private record HarnessRun(List<TaskResult> results, GoldMetrics metrics) {
        TaskResult result(String id) {
            return results.stream()
                    .filter(result -> result.task().id().equals(id))
                    .findFirst()
                    .orElseThrow();
        }

        NegativeCaseResult negativeCase(String id) {
            TaskResult result = result(id);
            return new NegativeCaseResult(result.forbiddenPathsAbsent(), result.retrievalSkipped());
        }
    }

    private record GoldMetrics(
            int taskCount,
            int retrievalTaskCount,
            int negativeTaskCount,
            double fileRecall,
            double filePrecision,
            double mrr,
            double ndcg,
            double junkContextRate,
            double missingCoreEvidenceRate,
            int lineRangeHitCount,
            Map<String, Integer> laneContributionCounts
    ) {
        int laneContributionCount(String lane) {
            return laneContributionCounts.getOrDefault(lane, 0);
        }
    }

    private static final class GoldContextHarness {
        static HarnessRun evaluate(
                String mode,
                List<GoldTask> tasks,
                LuceneStore store,
                RetrievalPipeline pipeline,
                boolean hybrid
        ) {
            List<TaskResult> results = new ArrayList<>();
            for (GoldTask task : tasks) {
                if (task.negativeCase() == NegativeCase.PRIVATE_MODE_RAG_DISABLED) {
                    results.add(new TaskResult(task, List.of(), Set.of(), -1, 0, true, true, false));
                    continue;
                }

                float[] qvec = hybrid ? task.queryVector() : null;
                RetrievalResult result = pipeline.execute(new RetrievalRequest(task.query(), qvec, task.topK()));
                List<String> actualPaths = result.paths();
                Set<String> lanes = directLaneContributions(task, store, hybrid);
                int firstRank = firstRelevantRank(task.expectedPaths(), actualPaths);
                int relevant = relevantRetrieved(task.expectedPaths(), actualPaths);
                boolean forbiddenAbsent = actualPaths.stream().noneMatch(task.forbiddenPaths()::contains);
                boolean lineHit = lineRangeHit(task, result);
                results.add(new TaskResult(task, actualPaths, lanes, firstRank, relevant, forbiddenAbsent, false, lineHit));
            }
            return new HarnessRun(results, metrics(results));
        }

        private static Set<String> directLaneContributions(GoldTask task, LuceneStore store, boolean hybrid) {
            Set<String> lanes = new HashSet<>();
            if (!task.expectsRetrieval()) return lanes;
            Set<String> expected = task.expectedPaths();
            if (store.bm25(task.query(), task.topK() * 3).stream().anyMatch(hit -> expected.contains(hit.path()))) {
                lanes.add("bm25");
            }
            if (hybrid && task.queryVector() != null
                    && store.knn(task.queryVector(), task.topK() * 3).stream().anyMatch(hit -> expected.contains(hit.path()))) {
                lanes.add("knn");
            }
            return lanes;
        }

        private static boolean lineRangeHit(GoldTask task, RetrievalResult result) {
            if (task.expectedLineRanges().isEmpty()) return false;
            for (RetrievalCandidate candidate : result.candidates()) {
                for (LineRange range : task.expectedLineRanges()) {
                    if (range.path().equals(candidate.path()) && range.intersects(candidate.metadata())) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static int firstRelevantRank(Set<String> expected, List<String> actual) {
            for (int i = 0; i < actual.size(); i++) {
                if (expected.contains(actual.get(i))) return i + 1;
            }
            return -1;
        }

        private static int relevantRetrieved(Set<String> expected, List<String> actual) {
            int count = 0;
            for (String path : actual) {
                if (expected.contains(path)) count++;
            }
            return count;
        }

        private static GoldMetrics metrics(List<TaskResult> results) {
            List<TaskResult> retrieval = results.stream()
                    .filter(result -> result.task().expectsRetrieval())
                    .toList();
            int expectedTotal = retrieval.stream().mapToInt(result -> result.task().expectedPaths().size()).sum();
            int relevantTotal = retrieval.stream().mapToInt(TaskResult::relevantRetrieved).sum();
            int retrievedTotal = retrieval.stream().mapToInt(result -> result.actualPaths().size()).sum();
            long missing = retrieval.stream().filter(result -> result.firstRelevantRank() < 0).count();
            double reciprocalSum = retrieval.stream()
                    .filter(result -> result.firstRelevantRank() > 0)
                    .mapToDouble(result -> 1.0 / result.firstRelevantRank())
                    .sum();
            double ndcgSum = retrieval.stream().mapToDouble(GoldContextHarness::ndcg).sum();
            Map<String, Integer> laneCounts = new LinkedHashMap<>();
            for (TaskResult result : results) {
                for (String lane : result.laneContributions()) {
                    laneCounts.merge(lane, 1, Integer::sum);
                }
            }
            return new GoldMetrics(
                    results.size(),
                    retrieval.size(),
                    results.size() - retrieval.size(),
                    expectedTotal == 0 ? 0.0 : (double) relevantTotal / expectedTotal,
                    retrievedTotal == 0 ? 0.0 : (double) relevantTotal / retrievedTotal,
                    retrieval.isEmpty() ? 0.0 : reciprocalSum / retrieval.size(),
                    retrieval.isEmpty() ? 0.0 : ndcgSum / retrieval.size(),
                    retrievedTotal == 0 ? 0.0 : (double) (retrievedTotal - relevantTotal) / retrievedTotal,
                    retrieval.isEmpty() ? 0.0 : (double) missing / retrieval.size(),
                    (int) retrieval.stream().filter(TaskResult::lineRangeHit).count(),
                    Map.copyOf(laneCounts));
        }

        private static double ndcg(TaskResult result) {
            if (result.actualPaths().isEmpty() || result.task().expectedPaths().isEmpty()) return 0.0;
            double dcg = 0.0;
            for (int i = 0; i < result.actualPaths().size(); i++) {
                if (result.task().expectedPaths().contains(result.actualPaths().get(i))) {
                    dcg += 1.0 / (Math.log(i + 2) / Math.log(2));
                }
            }
            int idealHits = Math.min(result.task().expectedPaths().size(), result.actualPaths().size());
            double ideal = 0.0;
            for (int i = 0; i < idealHits; i++) {
                ideal += 1.0 / (Math.log(i + 2) / Math.log(2));
            }
            return ideal == 0.0 ? 0.0 : dcg / ideal;
        }
    }

    private static final class GoldContextCatalog {
        static void index(LuceneStore store, boolean withVectors) {
            for (GoldDocument doc : documents()) {
                float[] vector = withVectors ? doc.vector() : null;
                store.add(doc.path(), doc.text(), vector, "fixture", chunkId(doc.path()), doc.metadata());
            }
            store.commit();
        }

        static List<GoldDocument> documents() {
            return List.of(
                    doc("src/main/java/dev/talos/cli/doctor/DoctorCommand.java#0",
                            "DoctorCommand renders OS architecture Java version CPU memory disk chat backend model and retrieval diagnostics.",
                            "java", 12, 72, "doctor diagnostics", vec(1, 0, 0)),
                    doc("src/test/java/dev/talos/cli/doctor/DoctorCommandTest.java#0",
                            "DoctorCommandTest verifies doctor output includes model setup embedding state BM25 fallback and no secret snippets.",
                            "java", 18, 88, "doctor tests", vec(1, 0, 0)),
                    doc("src/main/java/dev/talos/core/rag/RagService.java#0",
                            "RagService prepares local retrieval snippets, skips RAG in private mode unless enabled, and falls back to BM25 when embeddings fail.",
                            "java", 41, 132, "rag prepare", vec(0, 1, 0)),
                    doc("src/main/java/dev/talos/core/rag/RagService.java#1",
                            "RagService combines BM25, vector KNN, reciprocal rank fusion, source boosts, reranking, and dedup into prepared context.",
                            "java", 260, 340, "rag hybrid pipeline", vec(0, 1, 0)),
                    doc("src/test/java/dev/talos/core/rag/RagServiceTest.java#0",
                            "RagService tests cover private mode retrieval disabled by default, BM25-only fallback, and prepared citation shape.",
                            "java", 25, 118, "rag tests", vec(0, 1, 0)),
                    doc("src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java#0",
                            "CompatEmbeddingsClient probes a local embedding endpoint, checks host locality, model name, and vector dimension.",
                            "java", 44, 130, "embedding endpoint", vec(0, 0, 1)),
                    doc("src/main/java/dev/talos/core/index/LuceneStore.java#0",
                            "LuceneStore indexes text fields for BM25 and KnnFloatVectorField vectors for local KNN search.",
                            "java", 25, 118, "lucene fields", vec(0, 1, 1)),
                    doc("src/main/java/dev/talos/core/index/SymbolIndexStore.java#0",
                            "SymbolIndexStore writes a sidecar for class method function and symbol definitions used by workspace intelligence.",
                            "java", 20, 96, "symbol sidecar", vec(1, 1, 0)),
                    doc("src/main/java/dev/talos/core/retrieval/RetrievalPipeline.java#0",
                            "RetrievalPipeline executes BM25 KNN RRF rerank and dedup stages while recording a RetrievalTrace.",
                            "java", 16, 74, "retrieval pipeline", vec(1, 1, 0)),
                    doc("src/main/java/dev/talos/core/retrieval/stages/RrfFusionStage.java#0",
                            "RrfFusionStage applies reciprocal rank fusion so overlapping BM25 and vector candidates rise in the final ranking.",
                            "java", 20, 86, "rrf fusion", vec(0, 1, 1)),
                    doc("src/test/java/dev/talos/core/retrieval/RetrievalQualityGoldenTest.java#0",
                            "RetrievalQualityGoldenTest provides BM25 golden queries and trace invariants for baseline retrieval quality.",
                            "java", 24, 122, "retrieval golden tests", vec(1, 1, 0)),
                    doc("docs/user/retrieval-and-vectors.md#0",
                            "Retrieval and vectors docs explain that RAG is local Lucene, BM25 works without embeddings, vectors require a local embedding endpoint, and hybrid falls back to BM25.",
                            "markdown", 1, 145, "retrieval docs", vec(0, 1, 1)),
                    doc("docs/user/beta-best-practices.md#0",
                            "Beta best practices advise starting Talos in a narrow project directory, indexing only acceptable workspaces, using RAG for discovery, and direct reads for exact facts.",
                            "markdown", 1, 96, "best practices", vec(1, 0, 1)),
                    doc("config/default-config.yaml#0",
                            "Default config ships rag vectors enabled true, embedding provider compat, and localhost-gated model endpoints.",
                            "yaml", 104, 118, "default rag config", vec(0, 0, 1)),
                    doc("src/main/java/dev/talos/runtime/MutationIntent.java#0",
                            "MutationIntent classifies fix bug in file prompts as file edit while keeping advisory how would you fix questions read-only.",
                            "java", 70, 210, "mutation intent", vec(1, 0, 0)),
                    doc("src/test/java/dev/talos/runtime/MutationIntentTest.java#0",
                            "MutationIntentTest covers fix bug in calc.py positives and how would you fix it no-change negatives.",
                            "java", 32, 150, "mutation tests", vec(1, 0, 0)),
                    doc("src/main/java/dev/talos/tools/impl/ReadFileTool.java#0",
                            "ReadFileTool reads workspace files, enforces protected path policy, and renders numbered read display lines.",
                            "java", 40, 116, "read file tool", vec(1, 0, 1)),
                    doc("src/main/java/dev/talos/tools/impl/WriteFileTool.java#0",
                            "WriteFileTool writes approved content, preserves exact bytes, and must not accept copied read display line prefixes.",
                            "java", 46, 124, "write file tool", vec(1, 0, 1)),
                    doc("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java#0",
                            "LocalTurnTraceCapture records local turn trace events for approvals tools retrieval and evidence without making traces tamper-evident.",
                            "java", 18, 104, "local trace", vec(0, 1, 0)),
                    doc("docs/architecture/03-local-turn-trace-model-v1.md#0",
                            "Local turn trace model documents durable local evidence, trace schema, event records, and the non tamper-evident limitation.",
                            "markdown", 1, 120, "trace docs", vec(0, 1, 0)),
                    doc("src/main/java/dev/talos/core/secret/FileSecretStore.java#0",
                            "FileSecretStore encrypts local slash secret entries and protects the Windows master key with DPAPI CurrentUser.",
                            "java", 42, 190, "secret store", vec(0, 0, 1)),
                    doc("src/test/java/dev/talos/core/secret/FileSecretStoreTest.java#0",
                            "FileSecretStoreTest verifies secret put get delete and Windows DPAPI migration behavior.",
                            "java", 20, 110, "secret tests", vec(0, 0, 1)),
                    doc("README.md#0",
                            "README introduces Talos as a local-first CLI assistant with inspect ask verify local trace and planned beta setup.",
                            "markdown", 1, 80, "readme overview", vec(1, 1, 1)),
                    doc("src/main/java/dev/talos/core/context/ContextPacker.java#0",
                            "ContextPacker budgets model context, packs snippets, reserves response room, and avoids unsafe oversized evidence stuffing.",
                            "java", 30, 144, "context packer", vec(1, 1, 0))
            );
        }

        static List<GoldTask> tasks() {
            return List.of(
                    task("doctor-runtime-diagnostics", "doctor command OS Java memory model retrieval diagnostics",
                            set("src/main/java/dev/talos/cli/doctor/DoctorCommand.java#0"),
                            set("DoctorCommand"), ranges(range("src/main/java/dev/talos/cli/doctor/DoctorCommand.java#0", 12, 72)),
                            set("src/test/java/dev/talos/cli/doctor/DoctorCommandTest.java#0"), false, NegativeCase.NONE, vec(1, 0, 0)),
                    task("bm25-fallback", "BM25-only fallback when embedding endpoint fails",
                            set("src/main/java/dev/talos/core/rag/RagService.java#0", "docs/user/retrieval-and-vectors.md#0"),
                            set("RagService"), ranges(range("src/main/java/dev/talos/core/rag/RagService.java#0", 41, 132)),
                            set("src/test/java/dev/talos/core/rag/RagServiceTest.java#0"), false, NegativeCase.NONE, vec(0, 1, 0)),
                    task("semantic-embedding-endpoint", "semantic fuzzy setup for local embeddings endpoint dimensions",
                            set("src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java#0"),
                            set("CompatEmbeddingsClient"), ranges(range("src/main/java/dev/talos/core/embed/CompatEmbeddingsClient.java#0", 44, 130)),
                            set("src/test/java/dev/talos/core/embed/CompatEmbeddingsClientTest.java#0"), true, NegativeCase.NONE, vec(0, 0, 1)),
                    task("lucene-vector-storage", "KnnFloatVectorField BM25 text fields Lucene store",
                            set("src/main/java/dev/talos/core/index/LuceneStore.java#0"),
                            set("LuceneStore"), ranges(range("src/main/java/dev/talos/core/index/LuceneStore.java#0", 25, 118)),
                            set("src/test/java/dev/talos/core/index/LuceneStoreKnnTest.java#0"), false, NegativeCase.NONE, vec(0, 1, 1)),
                    task("symbol-sidecar", "symbol sidecar class method definition index",
                            set("src/main/java/dev/talos/core/index/SymbolIndexStore.java#0"),
                            set("SymbolIndexStore"), ranges(range("src/main/java/dev/talos/core/index/SymbolIndexStore.java#0", 20, 96)),
                            set("src/test/java/dev/talos/core/index/SymbolIndexStoreTest.java#0"), false, NegativeCase.NONE, vec(1, 1, 0)),
                    task("rrf-fusion", "reciprocal rank fusion overlapping BM25 vector candidates",
                            set("src/main/java/dev/talos/core/retrieval/stages/RrfFusionStage.java#0", "src/main/java/dev/talos/core/retrieval/RetrievalPipeline.java#0"),
                            set("RrfFusionStage", "RetrievalPipeline"), ranges(range("src/main/java/dev/talos/core/retrieval/stages/RrfFusionStage.java#0", 20, 86)),
                            set("src/test/java/dev/talos/core/retrieval/RetrievalParityTest.java#0"), false, NegativeCase.NONE, vec(0, 1, 1)),
                    task("retrieval-trace", "pipeline trace records BM25 KNN RRF rerank dedup",
                            set("src/main/java/dev/talos/core/retrieval/RetrievalPipeline.java#0"),
                            set("RetrievalTrace"), ranges(range("src/main/java/dev/talos/core/retrieval/RetrievalPipeline.java#0", 16, 74)),
                            set("src/test/java/dev/talos/core/retrieval/RetrievalQualityGoldenTest.java#0"), false, NegativeCase.NONE, vec(1, 1, 0)),
                    task("retrieval-docs", "RAG local Lucene BM25 vectors require local embedding endpoint",
                            set("docs/user/retrieval-and-vectors.md#0"),
                            set(), ranges(range("docs/user/retrieval-and-vectors.md#0", 1, 145)),
                            set("src/test/java/dev/talos/docs/TrustClaimsHonestyTest.java#0"), false, NegativeCase.NONE, vec(0, 1, 1)),
                    task("beta-best-practices", "start Talos in a narrow project directory use RAG for discovery direct reads exact facts",
                            set("docs/user/beta-best-practices.md#0"),
                            set(), ranges(range("docs/user/beta-best-practices.md#0", 1, 96)),
                            set(), false, NegativeCase.NONE, vec(1, 0, 1)),
                    task("default-vector-config", "default config vectors enabled true embedding provider compat",
                            set("config/default-config.yaml#0"),
                            set(), ranges(range("config/default-config.yaml#0", 104, 118)),
                            set("src/test/java/dev/talos/core/index/RagDefaultConfigPrivacyTest.java#0"), false, NegativeCase.NONE, vec(0, 0, 1)),
                    task("fix-bug-classifier", "fix bug in calc.py mutation intent file edit classifier",
                            set("src/main/java/dev/talos/runtime/MutationIntent.java#0", "src/test/java/dev/talos/runtime/MutationIntentTest.java#0"),
                            set("MutationIntent"), ranges(range("src/main/java/dev/talos/runtime/MutationIntent.java#0", 70, 210)),
                            set("src/test/java/dev/talos/runtime/MutationIntentTest.java#0"), false, NegativeCase.NONE, vec(1, 0, 0)),
                    task("read-file-display", "read_file numbered display protected path policy",
                            set("src/main/java/dev/talos/tools/impl/ReadFileTool.java#0"),
                            set("ReadFileTool"), ranges(range("src/main/java/dev/talos/tools/impl/ReadFileTool.java#0", 40, 116)),
                            set("src/test/java/dev/talos/tools/impl/ReadFileToolTest.java#0"), false, NegativeCase.NONE, vec(1, 0, 1)),
                    task("write-file-containment", "write_file exact bytes copied read display line prefixes",
                            set("src/main/java/dev/talos/tools/impl/WriteFileTool.java#0"),
                            set("WriteFileTool"), ranges(range("src/main/java/dev/talos/tools/impl/WriteFileTool.java#0", 46, 124)),
                            set("src/test/java/dev/talos/tools/impl/WriteFileToolTest.java#0"), false, NegativeCase.NONE, vec(1, 0, 1)),
                    task("local-trace-capture", "local turn trace approvals tools retrieval evidence events",
                            set("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java#0", "docs/architecture/03-local-turn-trace-model-v1.md#0"),
                            set("LocalTurnTraceCapture"), ranges(range("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java#0", 18, 104)),
                            set("src/test/java/dev/talos/runtime/trace/LocalTurnTraceCaptureTest.java#0"), false, NegativeCase.NONE, vec(0, 1, 0)),
                    task("secret-store-custody", "Windows DPAPI CurrentUser master key custody secret store",
                            set("src/main/java/dev/talos/core/secret/FileSecretStore.java#0"),
                            set("FileSecretStore"), ranges(range("src/main/java/dev/talos/core/secret/FileSecretStore.java#0", 42, 190)),
                            set("src/test/java/dev/talos/core/secret/FileSecretStoreTest.java#0"), false, NegativeCase.NONE, vec(0, 0, 1)),
                    task("context-packer", "token budget context packer reserves response room snippets",
                            set("src/main/java/dev/talos/core/context/ContextPacker.java#0"),
                            set("ContextPacker"), ranges(range("src/main/java/dev/talos/core/context/ContextPacker.java#0", 30, 144)),
                            set("src/test/java/dev/talos/core/context/ContextPackerTest.java#0"), false, NegativeCase.NONE, vec(1, 1, 0)),
                    task("project-overview", "local-first CLI assistant inspect ask verify local trace planned beta",
                            set("README.md#0"),
                            set(), ranges(range("README.md#0", 1, 80)),
                            set("src/test/java/dev/talos/docs/TrustClaimsHonestyTest.java#0"), false, NegativeCase.NONE, vec(1, 1, 1)),
                    task("golden-retrieval-tests", "BM25 golden query baseline retrieval quality test",
                            set("src/test/java/dev/talos/core/retrieval/RetrievalQualityGoldenTest.java#0"),
                            set("RetrievalQualityGoldenTest"), ranges(range("src/test/java/dev/talos/core/retrieval/RetrievalQualityGoldenTest.java#0", 24, 122)),
                            set(), false, NegativeCase.NONE, vec(1, 1, 0)),
                    negative("protected-path-exclusion", "retrieve .env TALOS_FAKE_SECRET protected path value",
                            set(".env#0"), NegativeCase.PROTECTED_PATH_EXCLUSION),
                    negative("private-mode-rag-disabled", "private mode should not retrieve indexed snippets",
                            set(), NegativeCase.PRIVATE_MODE_RAG_DISABLED)
            );
        }

        private static GoldDocument doc(
                String path,
                String text,
                String language,
                int lineStart,
                int lineEnd,
                String heading,
                float[] vector
        ) {
            return new GoldDocument(path, text, new ChunkMetadata(language, lineStart, lineEnd, heading), vector);
        }

        private static GoldTask task(
                String id,
                String query,
                Set<String> expectedPaths,
                Set<String> symbols,
                List<LineRange> ranges,
                Set<String> relatedTests,
                boolean requiresVector,
                NegativeCase negativeCase,
                float[] queryVector
        ) {
            return new GoldTask(
                    id,
                    query,
                    5,
                    expectedPaths,
                    symbols,
                    ranges,
                    relatedTests,
                    Set.of(),
                    requiresVector,
                    negativeCase,
                    queryVector);
        }

        private static GoldTask negative(String id, String query, Set<String> forbiddenPaths, NegativeCase negativeCase) {
            return new GoldTask(id, query, 5, Set.of(), Set.of(), List.of(), Set.of(),
                    forbiddenPaths, false, negativeCase, null);
        }

        private static int chunkId(String path) {
            int hash = path.indexOf('#');
            if (hash < 0) return 0;
            return Integer.parseInt(path.substring(hash + 1));
        }

        private static LineRange range(String path, int start, int end) {
            return new LineRange(path, start, end);
        }

        private static List<LineRange> ranges(LineRange... ranges) {
            return List.of(ranges);
        }

        private static Set<String> set(String... values) {
            return Set.of(values);
        }

        private static float[] vec(float a, float b, float c) {
            return new float[]{a, b, c};
        }
    }
}
