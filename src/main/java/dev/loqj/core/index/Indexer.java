package dev.loqj.core.index;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
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
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;

public class Indexer {
    private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);

    private final Config cfg;
    private final Embeddings emb;

    public Indexer(Config cfg) {
        this.cfg = cfg;
        this.emb = new EmbeddingsClient(cfg);
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
        final Path rootPath = root.toAbsolutePath().normalize();
        LOG.info("Indexing root: {}", rootPath);

        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        var includeGlobs = CfgUtil.strList(rag.get("include"));
        var excludeGlobs = CfgUtil.strList(rag.get("exclude"));

        // Prebuild matchers to keep lambda captures minimal & effectively-final
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

        final List<Path> files;
        try {
            files = FileWalker.listFiles(rootPath, pred);
        } catch (IOException ioe) {
            LOG.warn("Failed to walk files under {}: {}", rootPath, ioe.toString());
            return;
        }

        if (files.isEmpty()) {
            LOG.info("No files matched include/exclude.");
            return;
        }

        // Vectors toggle (BM25-only fallback if disabled or probe fails)
        boolean vecEnabled = true;
        Object vectorsObj = rag.get("vectors");
        if (vectorsObj instanceof Map<?,?> vm) {
            Object en = ((Map<?,?>) vm).get("enabled");
            if (en instanceof Boolean b) vecEnabled = b;
        }

        int dim = 0;
        if (vecEnabled) {
            try {
                dim = emb.dimension();
            } catch (Exception e) {
                LOG.warn("Embeddings dimension probe failed; falling back to BM25-only: {}", e.toString());
                vecEnabled = false;
            }
            if (dim <= 0) {
                LOG.warn("Embeddings dimension <= 0 ({}). Falling back to BM25-only.", dim);
                vecEnabled = false;
            }
        }

        final boolean useVectors = vecEnabled;     // <— snapshot for lambdas
        final int vectorDim = useVectors ? dim : 0;

        try (var store = new LuceneStore(indexDirFor(rootPath), vectorDim)) {
            int chunkChars = CfgUtil.intAt(rag, "chunk_chars", 1200);
            int overlap    = CfgUtil.intAt(rag, "chunk_overlap", 150);

            List<Callable<Void>> tasks = new ArrayList<>(files.size());
            for (Path p : files) {
                tasks.add(() -> {
                    try {
                        String text = ParserUtil.smartParse(p);
                        String rel  = rootPath.relativize(p).toString().replace('\\','/');
                        List<ParsedChunk> chunks = Chunker.chunk(rel, text, chunkChars, overlap);
                        for (ParsedChunk c : chunks) {
                            float[] vec = null;
                            if (useVectors) {                     // <— use the final snapshot here
                                try {
                                    vec = emb.embed(c.text());
                                    if (vec == null || vec.length == 0) {
                                        LOG.debug("Empty embedding for {}, BM25-only for this chunk", c.id());
                                        vec = null;
                                    }
                                } catch (Exception ex) {
                                    LOG.debug("Embedding failed for {}: {} (BM25-only this chunk)", c.id(), ex.toString());
                                    vec = null;
                                }
                            }
                            store.add(c.id(), c.text(), vec, c.fileHash(), c.chunkId());
                        }
                    } catch (Exception ex) {
                        LOG.warn("Skip {} : {}", p, ex.toString());
                    }
                    return null;
                });
            }

            int maxConc = Math.max(1, Runtime.getRuntime().availableProcessors());
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

            store.commit();
            LOG.info("Index complete. Files: {}", files.size());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
