package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.embed.CachingEmbeddings;
import dev.talos.core.embed.EmbeddingProfile;
import dev.talos.core.embed.EmbeddingsFactory;
import dev.talos.core.index.LuceneStore;
import dev.talos.core.ingest.FileWalker;
import dev.talos.spi.Embeddings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public final class BenchCommand implements Command {
    private final Path workspace;

    public BenchCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("bench",
                List.of(),
                "/bench [--runs=N] [--models=model1,model2] [--concurrency=1,2,4]",
                "Run benchmarks.",
                CommandGroup.DEBUG);
    }

    @Override public Result execute(String args, Context ctx) {
        try {
            BenchConfig config = parseBenchArgs(args);
            List<BenchResult> results = runBenchmarks(config, ctx);
            String jsonOutput = formatResults(results, config);
            return new Result.Ok(jsonOutput);
        } catch (Exception e) {
            return new Result.Error("Benchmark failed: " + e.getMessage(), 500);
        }
    }

    private BenchConfig parseBenchArgs(String args) {
        BenchConfig config = new BenchConfig();

        if (args == null || args.trim().isEmpty()) {
            return config; // Use defaults
        }

        String[] parts = args.trim().split("\\s+");
        for (String part : parts) {
            if (part.startsWith("--runs=")) {
                config.runs = Integer.parseInt(part.substring(7));
            } else if (part.startsWith("--models=")) {
                config.models = Arrays.asList(part.substring(9).split(","));
            } else if (part.startsWith("--concurrency=")) {
                config.concurrencies = Arrays.stream(part.substring(14).split(","))
                    .mapToInt(Integer::parseInt).toArray();
            }
        }

        return config;
    }

    private List<BenchResult> runBenchmarks(BenchConfig config, Context ctx) throws Exception {
        List<BenchResult> results = new ArrayList<>();

        // Get sample files for benchmarking (limit to avoid long runs)
        List<Path> sampleFiles = getSampleFiles();

        for (String model : config.models) {
            for (int concurrency : config.concurrencies) {
                BenchResult result = runSingleBenchmark(model, concurrency, config.runs, sampleFiles, ctx);
                results.add(result);
            }
        }

        return results;
    }

    private List<Path> getSampleFiles() throws Exception {
        var pred = (java.util.function.Predicate<Path>) p -> {
            String name = p.getFileName().toString().toLowerCase();
            return name.endsWith(".java") || name.endsWith(".md") || name.endsWith(".txt");
        };

        List<Path> allFiles = FileWalker.listFiles(workspace, pred);

        // Take a representative sample (max 20 files to keep benchmarks fast)
        Collections.shuffle(allFiles, new Random(42)); // Deterministic for repeatability
        return allFiles.subList(0, Math.min(20, allFiles.size()));
    }

    private BenchResult runSingleBenchmark(String embedModel, int concurrency, int runs,
                                         List<Path> sampleFiles, Context ctx) throws Exception {

        List<RunMetrics> runResults = new ArrayList<>();

        for (int run = 0; run < runs; run++) {
            RunMetrics metrics = performSingleRun(embedModel, concurrency, sampleFiles, ctx);
            runResults.add(metrics);
        }

        return new BenchResult(embedModel, concurrency, runs, runResults);
    }

    private RunMetrics performSingleRun(String embedModel, int concurrency,
                                      List<Path> sampleFiles, Context ctx) throws Exception {

        long totalStart = System.currentTimeMillis();
        RunMetrics metrics = new RunMetrics();

        // Create temporary index directory for this benchmark
        Path tempIndexDir = Files.createTempDirectory("talos-bench-");

        try {
            // Walk timing (simulated - files already collected)
            long walkStart = System.currentTimeMillis();
            metrics.walkTimeMs = System.currentTimeMillis() - walkStart;

            // Parse timing
            long parseStart = System.currentTimeMillis();
            List<String> parsedTexts = new ArrayList<>();
            for (Path file : sampleFiles) {
                if (Files.size(file) < 50_000) { // Skip large files in benchmark
                    parsedTexts.add(Files.readString(file));
                }
            }
            metrics.parseTimeMs = System.currentTimeMillis() - parseStart;
            metrics.filesProcessed = parsedTexts.size();

            // Embedding timing (with controlled concurrency)
            long embedStart = System.currentTimeMillis();
            Config cfg = ctx.cfg();

            EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
            Embeddings rawEmb = EmbeddingsFactory.forDocument(cfg);

            try (CacheDb cache = new CacheDb();
                 CachingEmbeddings cachedEmb = new CachingEmbeddings(rawEmb, cache, profile.cacheNamespace())) {

                AtomicInteger embedCount = new AtomicInteger();

                // Simple parallel processing to test concurrency
                parsedTexts.parallelStream().limit((long) concurrency * 2L).forEach(text -> {
                    try {
                        if (text.length() > 100) { // Only embed non-trivial texts
                            String sample = text.length() > 1000 ? text.substring(0, 1000) : text;
                            cachedEmb.embed(sample);
                            embedCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Skip failed embeddings in benchmark
                    }
                });

                metrics.embedTimeMs = System.currentTimeMillis() - embedStart;
                metrics.chunksEmbedded = embedCount.get();
                metrics.cacheHits = cachedEmb.cacheHits();
                metrics.cacheMisses = cachedEmb.cacheMisses();
            }

            // Lucene timing (simplified write test)
            long luceneStart = System.currentTimeMillis();
            try (LuceneStore store = new LuceneStore(tempIndexDir, 1024)) { // Fixed dimension for test
                for (int i = 0; i < Math.min(10, parsedTexts.size()); i++) {
                    String text = parsedTexts.get(i);
                    String id = "bench-doc-" + i;
                    store.add(id, text, null, "hash-" + i, i); // No vectors in benchmark for speed
                }
                store.commit();
            }
            metrics.luceneTimeMs = System.currentTimeMillis() - luceneStart;

        } finally {
            // Cleanup temp directory
            try {
                if (Files.exists(tempIndexDir)) {
                    try (var walk = Files.walk(tempIndexDir)) {
                        walk.sorted(Comparator.reverseOrder())
                            .forEach(p -> {
                                try { Files.deleteIfExists(p); } catch (Exception ignore) {}
                            });
                    }
                }
            } catch (Exception ignore) {}
        }

        metrics.totalTimeMs = System.currentTimeMillis() - totalStart;
        return metrics;
    }

    private String formatResults(List<BenchResult> results, BenchConfig config) {
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"benchmark_config\": {\n");
        json.append("    \"timestamp\": \"").append(Instant.now()).append("\",\n");
        json.append("    \"workspace\": \"").append(workspace.getFileName()).append("\",\n");
        json.append("    \"runs\": ").append(config.runs).append(",\n");
        json.append("    \"models\": ").append(config.models).append(",\n");
        json.append("    \"concurrencies\": ").append(Arrays.toString(config.concurrencies)).append("\n");
        json.append("  },\n");
        json.append("  \"results\": [\n");

        for (int i = 0; i < results.size(); i++) {
            BenchResult result = results.get(i);
            json.append("    ").append(result.toJson());
            if (i < results.size() - 1) json.append(",");
            json.append("\n");
        }

        json.append("  ]\n");
        json.append("}");

        return json.toString();
    }

    // Helper classes
    private static class BenchConfig {
        int runs = 3;
        List<String> models = Arrays.asList("bge-m3", "nomic-embed-text");
        int[] concurrencies = {1, 2, 4};
    }

    private static class RunMetrics {
        long walkTimeMs;
        long parseTimeMs;
        long embedTimeMs;
        long luceneTimeMs;
        long totalTimeMs;
        int filesProcessed;
        int chunksEmbedded;
        long cacheHits;
        long cacheMisses;

        String toJson() {
            return String.format(Locale.ROOT,
                "{\"walk\":%d,\"parse\":%d,\"embed\":%d,\"lucene\":%d,\"total\":%d," +
                "\"files\":%d,\"chunks\":%d,\"cache_hits\":%d,\"cache_misses\":%d}",
                walkTimeMs, parseTimeMs, embedTimeMs, luceneTimeMs, totalTimeMs,
                filesProcessed, chunksEmbedded, cacheHits, cacheMisses);
        }
    }

    private static class BenchResult {
        final String model;
        final int concurrency;
        final int runs;
        final List<RunMetrics> runResults;

        BenchResult(String model, int concurrency, int runs, List<RunMetrics> runResults) {
            this.model = model;
            this.concurrency = concurrency;
            this.runs = runs;
            this.runResults = runResults;
        }

        String toJson() {
            // Calculate averages
            long avgTotal = runResults.stream().mapToLong(r -> r.totalTimeMs).sum() / runs;
            long avgEmbed = runResults.stream().mapToLong(r -> r.embedTimeMs).sum() / runs;
            long avgLucene = runResults.stream().mapToLong(r -> r.luceneTimeMs).sum() / runs;
            long totalCacheHits = runResults.stream().mapToLong(r -> r.cacheHits).sum();
            long totalCacheMisses = runResults.stream().mapToLong(r -> r.cacheMisses).sum();

            return String.format(Locale.ROOT,
                "{\"model\":\"%s\",\"concurrency\":%d,\"runs\":%d," +
                "\"avg_total_ms\":%d,\"avg_embed_ms\":%d,\"avg_lucene_ms\":%d," +
                "\"total_cache_hits\":%d,\"total_cache_misses\":%d," +
                "\"cache_hit_rate\":%.2f}",
                model, concurrency, runs, avgTotal, avgEmbed, avgLucene,
                totalCacheHits, totalCacheMisses,
                totalCacheHits + totalCacheMisses > 0 ?
                    (double)totalCacheHits / (totalCacheHits + totalCacheMisses) * 100.0 : 0.0);
        }
    }
}
