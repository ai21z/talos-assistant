package dev.loqj.core.index;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.embed.CachingEmbeddings;
import dev.loqj.core.embed.EmbeddingsClient;
import dev.loqj.core.ingest.Chunker;
import dev.loqj.core.ingest.FileWalker;
import dev.loqj.core.ingest.ParsedChunk;
import dev.loqj.core.ingest.ParserUtil;
import dev.loqj.core.spi.Embeddings;
import dev.loqj.core.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;

public class Indexer {
    private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);

    private final Config cfg;
    private volatile IndexingStats lastRunStats;

    public Indexer(Config cfg) {
        this.cfg = cfg;
    }

    public Path indexDirFor(Path root) {
        try {
            String hex = Hash.sha1Hex(root.toAbsolutePath().toString());
            Path base = Path.of(System.getProperty("user.home"), ".loqj", "indices", hex);
            Files.createDirectories(base);
            return base;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public void index(Path root) {
        index(root, false);
    }

    public void index(Path root, boolean forceFullReindex) {
        final IndexingStats stats = new IndexingStats();
        final long startTime = System.currentTimeMillis();

        final Path rootPath = root.toAbsolutePath().normalize();
        LOG.info("Indexing root: {} (force_full={})", rootPath, forceFullReindex);

        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));

        // Check force_full_reindex config
        boolean configForceReindex = CfgUtil.intAt(rag, "force_full_reindex", 0) == 1;
        final boolean skipHashing = forceFullReindex || configForceReindex;

        // Accept either includes/excludes OR include/exclude
        var includeGlobs = firstNonEmptyStrList(
                CfgUtil.strList(rag.get("includes")),
                CfgUtil.strList(rag.get("include"))
        );
        var excludeGlobs = firstNonEmptyStrList(
                CfgUtil.strList(rag.get("excludes")),
                CfgUtil.strList(rag.get("exclude"))
        );

        // Prebuild matchers
        final FileSystem fs = rootPath.getFileSystem();
        final List<PathMatcher> includeMatchers = new ArrayList<>();
        for (String g : includeGlobs) includeMatchers.add(fs.getPathMatcher("glob:" + g));
        final List<PathMatcher> excludeMatchers = new ArrayList<>();
        for (String g : excludeGlobs) excludeMatchers.add(fs.getPathMatcher("glob:" + g));

        final Predicate<Path> pred = p -> {
            Path rel = rootPath.relativize(p);
            boolean inc = includeMatchers.isEmpty() || includeMatchers.stream().anyMatch(m -> m.matches(rel));
            boolean exc = excludeMatchers.stream().anyMatch(m -> m.matches(rel));
            return inc && !exc;
        };

        // Walk files with timing
        final List<Path> files;
        long walkStart = System.currentTimeMillis();
        try {
            files = FileWalker.listFiles(rootPath, pred);
        } catch (IOException ioe) {
            LOG.warn("Failed to walk files under {}: {}", rootPath, ioe.toString());
            return;
        }
        stats.addWalkTime(System.currentTimeMillis() - walkStart);

        if (files.isEmpty()) {
            LOG.info("No files matched include/exclude.");
            return;
        } else {
            LOG.info("Matched {} files after include/exclude filters.", files.size());
        }

        // Vectors toggle (BM25-only fallback if disabled or probe fails)
        boolean vecEnabled = true;
        Object vectorsObj = rag.get("vectors");
        if (vectorsObj instanceof Map<?,?> vm) {
            Object en = ((Map<?,?>) vm).get("enabled");
            if (en instanceof Boolean b) vecEnabled = b;
        }

        // Build an embeddings client (cached) once per indexing run
        Embeddings rawEmb = new EmbeddingsClient(cfg);

        // Choose a stable cache key: "ollama/<embed-model>"
        Map<String,Object> oll = CfgUtil.map(cfg.data.get("ollama"));
        String embedModel = Objects.toString(oll.getOrDefault("embed", "bge-m3"));

        try (CacheDb cache = new CacheDb();
             CachingEmbeddings cachedEmb = new CachingEmbeddings(rawEmb, cache, "ollama/" + embedModel)) {

            int dim = 0;
            boolean useVectors = vecEnabled;
            if (useVectors) {
                try {
                    dim = cachedEmb.dimension();
                } catch (Exception e) {
                    LOG.warn("Embeddings dimension probe failed; falling back to BM25-only: {}", e.toString());
                    useVectors = false;
                }
                if (dim <= 0) {
                    LOG.warn("Embeddings dimension <= 0 ({}). Falling back to BM25-only.", dim);
                    useVectors = false;
                    dim = 0;
                }
            }
            final int vectorDim = useVectors ? dim : 0;

            // Effectively-final reference for lambdas
            final Embeddings embForTasks = useVectors ? cachedEmb : null;

            try (var store = new LuceneStore(indexDirFor(rootPath), vectorDim)) {
                int chunkChars = CfgUtil.intAt(rag, "chunk_chars", 1200);
                int overlap    = CfgUtil.intAt(rag, "chunk_overlap", 150);

                List<Callable<Void>> tasks = new ArrayList<>(files.size());

                for (Path p : files) {
                    tasks.add(() -> {
                        stats.incrementFilesScanned();

                        try {
                            String rel = rootPath.relativize(p).toString().replace('\\','/');

                            // Check if file is unchanged (unless forcing full reindex)
                            if (!skipHashing) {
                                String currentHash = Hash.sha256Hex(Files.readAllBytes(p));
                                if (store.isUpToDate(rel, currentHash)) {
                                    LOG.debug("Skipping unchanged file: {}", rel);
                                    stats.incrementFilesSkipped();
                                    return null; // Skip processing
                                }
                                // File has changed - remove old chunks and reprocess
                                store.removeFileChunks(rel);
                            }

                            stats.incrementFilesEmbedded();

                            // Parse with timing
                            long parseStart = System.currentTimeMillis();
                            String text = ParserUtil.smartParse(p);
                            stats.addParseTime(System.currentTimeMillis() - parseStart);

                            List<ParsedChunk> chunks = Chunker.chunk(rel, text, chunkChars, overlap);

                            for (ParsedChunk c : chunks) {
                                float[] vec = null;
                                if (embForTasks != null) {
                                    long embedStart = System.currentTimeMillis();
                                    try {
                                        vec = embForTasks.embed(c.text());
                                        if (vec == null || vec.length == 0) {
                                            LOG.debug("Empty embedding for {}, BM25-only for this chunk", c.id());
                                            vec = null;
                                        }
                                    } catch (Exception ex) {
                                        LOG.debug("Embedding failed for {}: {} (BM25-only this chunk)", c.id(), ex.toString());
                                        vec = null;
                                    }
                                    stats.addEmbedTime(System.currentTimeMillis() - embedStart);
                                }

                                long luceneStart = System.currentTimeMillis();
                                String currentHash = skipHashing ? null : Hash.sha256Hex(Files.readAllBytes(p));
                                store.add(c.id(), c.text(), vec, currentHash, c.chunkId());
                                stats.addLuceneTime(System.currentTimeMillis() - luceneStart);
                                stats.incrementChunksWritten();
                            }
                        } catch (Exception ex) {
                            LOG.warn("Skip {} : {}", p, ex.toString());
                        }
                        return null;
                    });
                }

                // Get embedding concurrency from config
                int embedConc = CfgUtil.intAt(rag, "embed_concurrency", 4);
                var limits = CfgUtil.map(cfg.data.get("limits"));
                int ratePerSec = Math.max(1, CfgUtil.intAt(limits, "rate_per_sec", 10));
                int cpuConc = Math.max(1, Runtime.getRuntime().availableProcessors());

                // Use embed_concurrency for vector-enabled indexing, fall back to rate_per_sec for compatibility
                int maxConc = useVectors ? Math.min(cpuConc, embedConc) : Math.min(cpuConc, ratePerSec);

                LOG.info("Using concurrency: {} (embed_concurrency={}, vectors={})", maxConc, embedConc, useVectors);

                try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
                    Semaphore gate = new Semaphore(maxConc);
                    List<Future<Void>> futures = new ArrayList<>(tasks.size());
                    for (Callable<Void> t : tasks) {
                        gate.acquire();
                        futures.add(ex.submit(() -> {
                            try { return t.call(); }
                            finally { gate.release(); }
                        }));
                    }
                    for (Future<Void> f : futures) {
                        try { f.get(); }
                        catch (ExecutionException ee) { LOG.warn("task failed", ee.getCause()); }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Indexing interrupted");
                }

                long commitStart = System.currentTimeMillis();
                store.commit();
                stats.addCommitTime(System.currentTimeMillis() - commitStart);

                stats.setTotalTime(System.currentTimeMillis() - startTime);
                this.lastRunStats = stats;

                // Log cache metrics if using CachingEmbeddings
                if (embForTasks instanceof CachingEmbeddings ce) {
                    LOG.info("Embedding cache: hits={}, misses={}", ce.cacheHits(), ce.cacheMisses());
                }

                // Log summary and detailed timings
                LOG.info("Index complete. Files: {} - {}", files.size(), stats.getSummary());
                LOG.info("Performance - {}", stats.getDetailedTimings());

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        } catch (Exception e) {
            throw new RuntimeException("Caching embeddings setup failed", e);
        }
    }

    private static List<String> firstNonEmptyStrList(List<String> a, List<String> b) {
        if (a != null && !a.isEmpty()) return a;
        return (b == null) ? List.of() : b;
    }

    /** Non-breaking reindex API for callers that expect it. */
    public Object reindex(Path root) throws Exception {
        try {
            Method m = this.getClass().getMethod("index", Path.class);
            Object res = m.invoke(this, root);
            return res == null ? "Reindexed." : res;
        } catch (NoSuchMethodException ignore) {
            try {
                Method m2 = this.getClass().getMethod("build", Path.class);
                Object res = m2.invoke(this, root);
                return res == null ? "Reindexed." : res;
            } catch (NoSuchMethodException ignore2) {
                return "Reindexed.";
            }
        }
    }

    public IndexingStats getLastRunStats() {
        return lastRunStats;
    }
}
