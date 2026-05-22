package dev.talos.core.index;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.embed.CachingEmbeddings;
import dev.talos.core.embed.EmbeddingProfile;
import dev.talos.core.embed.EmbeddingsFactory;
import dev.talos.core.ingest.Chunker;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.core.ingest.FileWalker;
import dev.talos.core.ingest.ParsedChunk;
import dev.talos.core.ingest.ParserUtil;
import dev.talos.core.ingest.UnsupportedDocumentFormats;
import dev.talos.core.privacy.PrivateDocumentIndexingPolicy;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.safety.ProtectedWorkspacePaths;
import dev.talos.spi.Embeddings;
import dev.talos.core.util.BuildInfo;
import dev.talos.core.util.Hash;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.regex.Pattern;

public class Indexer {
    private static final Logger LOG = LoggerFactory.getLogger(Indexer.class);
    private static final boolean IS_WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("windows");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int INDEX_METADATA_SCHEMA_VERSION = 2;

    private final Config cfg;
    private volatile IndexingStats lastRunStats;

    private static final class PrivacyIndexingSkip extends IOException {
        private PrivacyIndexingSkip(String message) {
            super(message);
        }
    }

    public Indexer(Config cfg) {
        this.cfg = cfg;
    }

    public Path indexDirFor(Path root) {
        try {
            String hex = Hash.sha1Hex(root.toAbsolutePath().toString());
            Path base = Path.of(System.getProperty("user.home"), ".talos", "indices", hex);
            Files.createDirectories(base);
            return base;
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public Path policyMetadataFile(Path root) {
        return indexDirFor(root).resolve("talos-index-metadata.json");
    }

    public boolean isPolicyMetadataCurrent(Path root) {
        Path metadata = policyMetadataFile(root);
        if (!Files.isRegularFile(metadata)) return false;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> data = JSON.readValue(metadata.toFile(), Map.class);
            return INDEX_METADATA_SCHEMA_VERSION == intValue(data.get("schemaVersion"))
                    && ProtectedWorkspacePaths.POLICY_VERSION.equals(String.valueOf(data.get("privacyPolicyVersion")))
                    && FileCapabilityPolicy.POLICY_VERSION.equals(String.valueOf(data.get("fileCapabilityPolicyVersion")))
                    && DocumentExtractionService.EXTRACTION_POLICY_VERSION.equals(String.valueOf(data.get("documentExtractionPolicyVersion")))
                    && currentRagConfigHash().equals(String.valueOf(data.get("ragConfigHash")))
                    && currentDocumentExtractionConfigHash().equals(String.valueOf(data.get("documentExtractionConfigHash")))
                    && currentPrivacyConfigHash().equals(String.valueOf(data.get("privacyConfigHash")));
        } catch (Exception e) {
            return false;
        }
    }

    public void invalidateIndex(Path root) {
        Path indexDir = indexDirFor(root);
        if (!Files.exists(indexDir)) return;
        try (var paths = Files.walk(indexDir)) {
            paths.sorted(Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        } catch (IOException e) {
            throw new RuntimeException("Failed to invalidate stale RAG index: " + e.getMessage(), e);
        }
    }

    public void index(Path root) {
        index(root, false);
    }

    public void index(Path root, boolean forceFullReindex) {
        index(root, forceFullReindex, IndexProgressListener.NOOP);
    }

    public void index(Path root, boolean forceFullReindex, IndexProgressListener listener) {
        final IndexingStats stats = new IndexingStats();
        final long startTime = System.currentTimeMillis();

        final Path rootPath = root.toAbsolutePath().normalize();
        LOG.info("Indexing root: {} (force_full={})", SafeLogFormatter.value(rootPath), forceFullReindex);

        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));

        // Check force_full_reindex config
        boolean configForceReindex = CfgUtil.intAt(rag, "force_full_reindex", 0) == 1;
        if (forceFullReindex || configForceReindex) {
            invalidateIndex(rootPath);
        }
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

        // Create the file filter predicate (Windows case-insensitive, others case-sensitive)
        final Predicate<Path> pred = createFileFilter(rootPath, includeGlobs, excludeGlobs);

        // Walk files with timing
        final List<Path> files;
        long walkStart = System.currentTimeMillis();
        try {
            files = FileWalker.listFiles(rootPath, pred);
        } catch (IOException ioe) {
            LOG.warn("Failed to walk files under {}: {}",
                    SafeLogFormatter.value(rootPath), SafeLogFormatter.throwableMessage(ioe));
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

        // Resolve embedding profile and build a document embedder (cached)
        EmbeddingProfile profile = EmbeddingsFactory.profileFrom(cfg);
        Embeddings rawEmb = EmbeddingsFactory.forDocument(cfg);

        try (CacheDb cache = new CacheDb();
             CachingEmbeddings cachedEmb = new CachingEmbeddings(rawEmb, cache, profile.cacheNamespace())) {

            int dim = 0;
            boolean useVectors = vecEnabled;
            if (useVectors) {
                try {
                    dim = cachedEmb.dimension();
                } catch (Exception e) {
                    LOG.warn("Embeddings dimension probe failed; falling back to BM25-only: {}",
                            SafeLogFormatter.throwableMessage(e));
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
            final CachingEmbeddings embForTasks = useVectors ? cachedEmb : null;

            try (var store = new LuceneStore(indexDirFor(rootPath), vectorDim)) {
                int chunkChars = CfgUtil.intAt(rag, "chunk_chars", 1200);
                int overlap    = CfgUtil.intAt(rag, "chunk_overlap", 150);

                List<Callable<Void>> tasks = new ArrayList<>(files.size());
                final int totalFiles = files.size();
                final AtomicInteger filesCompleted = new AtomicInteger();

                for (Path p : files) {
                    tasks.add(() -> {
                        stats.incrementFilesScanned();
                        String rel = rootPath.relativize(p).toString().replace('\\','/');

                        try {
                            // Check if file is unchanged (unless forcing full reindex)
                            if (!skipHashing) {
                                String currentHash = Hash.sha256Hex(Files.readAllBytes(p));
                                if (store.isUpToDate(rel, currentHash)) {
                                    LOG.debug("Skipping unchanged file: {}", SafeLogFormatter.value(rel));
                                    stats.incrementFilesSkipped();
                                    return null; // Skip processing
                                }
                                // File has changed - remove old chunks and reprocess
                                store.removeFileChunks(rel);
                            }

                            // Parse with timing
                            long parseStart = System.currentTimeMillis();
                            String text = parseIndexableText(rootPath, p);
                            stats.addParseTime(System.currentTimeMillis() - parseStart);
                            stats.incrementFilesEmbedded();

                            List<ParsedChunk> chunks = Chunker.chunk(rel, text, chunkChars, overlap);

                            // Batch process embeddings for better performance
                            if (embForTasks != null) {
                                // Extract texts for batch processing
                                List<String> chunkTexts = chunks.stream()
                                    .map(ParsedChunk::text)
                                    .toList();

                                long embedStart = System.currentTimeMillis();
                                List<float[]> vectors;
                                try {
                                    vectors = embForTasks.embedBatch(chunkTexts);
                                } catch (Exception ex) {
                                    LOG.debug("Batch embedding failed for {}: {} (falling back to individual)",
                                            SafeLogFormatter.value(rel), SafeLogFormatter.throwableMessage(ex));
                                    // Fallback to individual processing
                                    vectors = new ArrayList<>();
                                    for (String chunkText : chunkTexts) {
                                        try {
                                            float[] vec = embForTasks.embed(chunkText);
                                            vectors.add(vec);
                                        } catch (Exception e) {
                                            LOG.debug("Individual embedding failed: {}", SafeLogFormatter.throwableMessage(e));
                                            vectors.add(null);
                                        }
                                    }
                                }
                                stats.addEmbedTime(System.currentTimeMillis() - embedStart);

                                // Store chunks with their corresponding embeddings
                                for (int i = 0; i < chunks.size(); i++) {
                                    ParsedChunk c = chunks.get(i);
                                    float[] vec = i < vectors.size() ? vectors.get(i) : null;

                                    if (vec == null || vec.length == 0) {
                                        LOG.debug("Empty/null embedding for {}, BM25-only for this chunk",
                                                SafeLogFormatter.value(c.id()));
                                        vec = null;
                                    }

                                    long luceneStart = System.currentTimeMillis();
                                    String currentHash = skipHashing ? null : Hash.sha256Hex(Files.readAllBytes(p));
                                    store.add(c.id(), c.text(), vec, currentHash, c.chunkId(), c.metadata());
                                    stats.incrementChunksWritten();
                                    stats.addLuceneTime(System.currentTimeMillis() - luceneStart);
                                }
                            } else {
                                // BM25-only processing when vectors are disabled or unavailable.
                                for (ParsedChunk c : chunks) {
                                    long luceneStart = System.currentTimeMillis();
                                    String currentHash = skipHashing ? null : Hash.sha256Hex(Files.readAllBytes(p));
                                    store.add(c.id(), c.text(), null, currentHash, c.chunkId(), c.metadata());
                                    stats.incrementChunksWritten();
                                    stats.addLuceneTime(System.currentTimeMillis() - luceneStart);
                                }
                            }
                        } catch (PrivacyIndexingSkip ex) {
                            stats.incrementFilesSkipped();
                            stats.incrementFilesSkippedByPrivacy();
                            LOG.info("Skip {} : {}", SafeLogFormatter.value(p), SafeLogFormatter.throwableMessage(ex));
                        } catch (Exception ex) {
                            LOG.warn("Skip {} : {}", SafeLogFormatter.value(p), SafeLogFormatter.throwableMessage(ex));
                        } finally {
                            listener.onFileComplete(filesCompleted.incrementAndGet(), totalFiles, rel);
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
                        catch (ExecutionException ee) {
                            LOG.warn("task failed: {}", SafeLogFormatter.throwableMessage(ee.getCause()));
                        }
                    }
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    LOG.warn("Indexing interrupted");
                }

                long commitStart = System.currentTimeMillis();
                store.commit();
                writePolicyMetadata(rootPath);
                stats.addCommitTime(System.currentTimeMillis() - commitStart);

                stats.setTotalTime(System.currentTimeMillis() - startTime);
                this.lastRunStats = stats;

                // Log cache metrics if using CachingEmbeddings
                if (embForTasks != null) {
                    LOG.info("Embedding cache: hits={}, misses={}", embForTasks.cacheHits(), embForTasks.cacheMisses());
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

    /**
     * Reindex the given workspace root. Delegates directly to {@link #index(Path)}.
     * Returns a status string for callers that display a summary.
     */
    public Object reindex(Path root) {
        index(root);
        return "Reindexed.";
    }

    /**
     * Reindex with live progress feedback.
     *
     * @see #index(Path, boolean, IndexProgressListener)
     */
    public Object reindex(Path root, IndexProgressListener listener) {
        index(root, false, listener);
        return "Reindexed.";
    }

    public IndexingStats getLastRunStats() {
        return lastRunStats;
    }

    private void writePolicyMetadata(Path root) throws IOException {
        Path metadata = policyMetadataFile(root);
        Files.createDirectories(metadata.getParent());
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("schemaVersion", INDEX_METADATA_SCHEMA_VERSION);
        data.put("privacyPolicyVersion", ProtectedWorkspacePaths.POLICY_VERSION);
        data.put("fileCapabilityPolicyVersion", FileCapabilityPolicy.POLICY_VERSION);
        data.put("documentExtractionPolicyVersion", DocumentExtractionService.EXTRACTION_POLICY_VERSION);
        data.put("ragConfigHash", currentRagConfigHash());
        data.put("documentExtractionConfigHash", currentDocumentExtractionConfigHash());
        data.put("privacyConfigHash", currentPrivacyConfigHash());
        data.put("workspaceRootHash", Hash.sha1Hex(root.toAbsolutePath().normalize().toString()));
        data.put("createdAt", Instant.now().toString());
        data.put("talosVersion", BuildInfo.version());
        JSON.writerWithDefaultPrettyPrinter().writeValue(metadata.toFile(), data);
    }

    private String currentRagConfigHash() {
        try {
            return Hash.sha1Hex(JSON.writeValueAsString(CfgUtil.map(cfg.data.get("rag"))));
        } catch (Exception e) {
            return Hash.sha1Hex(String.valueOf(CfgUtil.map(cfg.data.get("rag"))));
        }
    }

    private String currentDocumentExtractionConfigHash() {
        try {
            return Hash.sha1Hex(JSON.writeValueAsString(CfgUtil.map(cfg.data.get("document_extraction"))));
        } catch (Exception e) {
            return Hash.sha1Hex(String.valueOf(CfgUtil.map(cfg.data.get("document_extraction"))));
        }
    }

    private String currentPrivacyConfigHash() {
        try {
            return Hash.sha1Hex(JSON.writeValueAsString(CfgUtil.map(cfg.data.get("privacy"))));
        } catch (Exception e) {
            return Hash.sha1Hex(String.valueOf(CfgUtil.map(cfg.data.get("privacy"))));
        }
    }

    private static int intValue(Object value) {
        if (value instanceof Number number) return number.intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Creates a file filter predicate that is case-insensitive on Windows, case-sensitive elsewhere.
     */
    private Predicate<Path> createFileFilter(Path rootPath, List<String> includeGlobs, List<String> excludeGlobs) {
        if (IS_WINDOWS) {
            return createWindowsCaseInsensitiveFilter(rootPath, includeGlobs, excludeGlobs);
        } else {
            return createCaseSensitiveFilter(rootPath, includeGlobs, excludeGlobs);
        }
    }

    /**
     * Case-sensitive filter for non-Windows systems (original behavior).
     */
    private Predicate<Path> createCaseSensitiveFilter(Path rootPath, List<String> includeGlobs, List<String> excludeGlobs) {
        final FileSystem fs = rootPath.getFileSystem();
        final List<PathMatcher> includeMatchers = new ArrayList<>();
        for (String g : includeGlobs) includeMatchers.add(fs.getPathMatcher("glob:" + g));
        final List<PathMatcher> excludeMatchers = new ArrayList<>();
        for (String g : excludeGlobs) excludeMatchers.add(fs.getPathMatcher("glob:" + g));

        return p -> {
            if (ProtectedWorkspacePaths.isProtectedPath(rootPath, p)
                    || unsupportedAndNotExtractionEnabled(p)) {
                return false;
            }
            Path rel = rootPath.relativize(p);
            boolean inc = includeMatchers.isEmpty() || includeMatchers.stream().anyMatch(m -> m.matches(rel));
            boolean exc = excludeMatchers.stream().anyMatch(m -> m.matches(rel));
            return inc && !exc;
        };
    }

    /**
     * Case-insensitive filter for Windows systems.
     */
    private Predicate<Path> createWindowsCaseInsensitiveFilter(Path rootPath, List<String> includeGlobs, List<String> excludeGlobs) {
        // Convert globs to regex patterns (case-insensitive)
        final List<Pattern> includePatterns = new ArrayList<>();
        for (String glob : includeGlobs) {
            includePatterns.add(globToRegexPattern(glob));
        }
        final List<Pattern> excludePatterns = new ArrayList<>();
        for (String glob : excludeGlobs) {
            excludePatterns.add(globToRegexPattern(glob));
        }

        return p -> {
            if (ProtectedWorkspacePaths.isProtectedPath(rootPath, p)
                    || unsupportedAndNotExtractionEnabled(p)) {
                return false;
            }
            Path rel = rootPath.relativize(p);
            String relStr = rel.toString().replace('\\', '/').toLowerCase(Locale.ROOT);

            boolean inc = includePatterns.isEmpty() || includePatterns.stream().anyMatch(pattern -> pattern.matcher(relStr).matches());
            boolean exc = excludePatterns.stream().anyMatch(pattern -> pattern.matcher(relStr).matches());
            return inc && !exc;
        };
    }

    /**
     * Converts a glob pattern to a case-insensitive regex pattern.
     * Properly handles ** for recursive directory matching.
     */
    private Pattern globToRegexPattern(String glob) {
        String regex = glob.toLowerCase(Locale.ROOT)
            .replace(".", "\\.")
            // Use placeholders to prevent interference from subsequent replacements
            .replace("**/", "__DOUBLESTAR_SLASH__")
            .replace("**", "__DOUBLESTAR__")
            // Now replace single * (won't affect placeholders)
            .replace("*", "[^/]*")
            // Replace ? (single character, not separator)
            .replace("?", "[^/]")
            // Finally replace placeholders with actual regex patterns
            .replace("__DOUBLESTAR_SLASH__", "(?:.*/)?")  // Matches zero or more directory levels
            .replace("__DOUBLESTAR__", ".*");              // Matches anything

        return Pattern.compile("^" + regex + "$", Pattern.CASE_INSENSITIVE);
    }

    private String parseIndexableText(Path rootPath, Path path) throws IOException {
        FileCapabilityPolicy.FormatInfo capability = FileCapabilityPolicy
                .describe(path, cfg)
                .orElse(null);
        if (capability != null && capability.enabled()) {
            DocumentExtractionRequest request = DocumentExtractionRequest.index(path, rootPath);
            DocumentExtractionResult result = new DocumentExtractionService(cfg).extract(request);
            if (result.status() == DocumentExtractionStatus.SUCCESS
                    || result.status() == DocumentExtractionStatus.PARTIAL) {
                if (!PrivateDocumentIndexingPolicy.mayIndexExtractedDocument(cfg, request, capability)) {
                    throw new PrivacyIndexingSkip("Document extraction blocked by private document RAG policy: "
                            + PrivateDocumentIndexingPolicy.decisionReason(cfg, request, capability));
                }
                return result.safeText();
            }
            throw new IOException("Document extraction unavailable for index status=" + result.status());
        }
        return ParserUtil.smartParse(path);
    }

    private boolean unsupportedAndNotExtractionEnabled(Path path) {
        FileCapabilityPolicy.FormatInfo capability = FileCapabilityPolicy
                .describe(path, cfg)
                .orElse(null);
        if (capability != null && capability.enabled()) {
            return false;
        }
        return UnsupportedDocumentFormats.isUnsupported(path);
    }
}
