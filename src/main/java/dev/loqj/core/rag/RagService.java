package dev.loqj.core.rag;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.embed.CachingEmbeddings;
import dev.loqj.core.embed.EmbeddingsClient;
import dev.loqj.core.index.Indexer;
import dev.loqj.core.index.LuceneStore;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.spi.CorpusStore;
import dev.loqj.core.search.Retriever;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RagService {
    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    private final Config cfg;
    private final Indexer indexer;

    // Guard against re-entrant lazy indexing
    private final AtomicBoolean indexingNow = new AtomicBoolean(false);

    // very small session-memory field used by RAG+MEMORY mode (optional)
    private String sessionMemory;

    /** Small data holder returned by prepare(). */
    public static final class Prepared {
        private final List<Map<String, String>> snippetMaps;
        private final List<String> citations;

        public Prepared(List<Map<String, String>> snippetMaps, List<String> citations) {
            this.snippetMaps = (snippetMaps == null ? List.of() : List.copyOf(snippetMaps));
            this.citations   = (citations == null ? List.of()     : List.copyOf(citations));
        }
        public List<Map<String, String>> snippetMaps() { return snippetMaps; }
        public List<String> citations()                 { return citations;  }
    }

    /** Answer type expected by RagAskCmd (has text() and citations()). */
    public record Answer(String text, List<String> citations) {}

    public RagService(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg);
        this.indexer = new Indexer(cfg);
    }

    public Indexer getIndexer() { return indexer; }

    public Object reindex(Path root) throws Exception { return indexer.reindex(root); }

    public Prepared prepare(Path ws, String query, Integer topKOverride) {
        // Ensure index exists before retrieval (lazy indexing on first query)
        ensureIndexExists(ws);

        int defaultTopK = 6;
        try {
            Map<String, Object> rag = CfgUtil.map(cfg.data.get("rag"));
            Object v = (rag == null ? null : rag.get("top_k"));
            if (v instanceof Number n) defaultTopK = n.intValue();
            else if (v != null) defaultTopK = Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {}

        final int k = (topKOverride == null ? defaultTopK : Math.max(1, topKOverride));

        // Read vector toggle; if off, we’ll skip KNN
        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        boolean vecEnabled = true;
        Object vectorsObj = rag.get("vectors");
        if (vectorsObj instanceof Map<?,?> vm) {
            Object en = ((Map<?,?>) vm).get("enabled");
            if (en instanceof Boolean b) vecEnabled = b;
        }

        Path indexDir = indexer.indexDirFor(ws);
        List<Map<String,String>> snippets = new ArrayList<>();
        List<String> citations = new ArrayList<>();

        // Open store for read (vectorDim==0 is fine for reading BM25; writer creation is the only user of vectorDim)
        try (LuceneStore store = new LuceneStore(indexDir, 0)) {
            // BM25 first
            List<CorpusStore.Hit> bm25 = store.bm25(query, Math.max(k * 3, k));
            List<CorpusStore.Hit> knn = List.of();

            // Add KNN when available
            if (vecEnabled) {
                try (CacheDb cache = new CacheDb();
                     CachingEmbeddings emb = new CachingEmbeddings(new EmbeddingsClient(cfg), cache, "query/ollama")) {
                    float[] qvec = emb.embed(query);
                    if (qvec != null && qvec.length > 0) {
                        knn = store.knn(qvec, Math.max(k * 3, k));
                    }
                } catch (Exception ignore) {
                    // If embeddings fail, just proceed with BM25
                }
            }

            // Fuse + dedupe by path
            var fused = Retriever.fuseRrf(asLuceneHits(bm25), asLuceneHits(knn), 60, Math.max(k * 2, k));
            var finalCands = Retriever.mmr(fused, 0.7, k);

            // Build snippet maps + citations (deduplicate citations by file path)
            var citationSet = new LinkedHashSet<String>(finalCands.size());
            for (var c : finalCands) {
                String text = store.getTextByPath(c.path);
                if (text == null || text.isBlank()) continue;
                snippets.add(Map.of("path", c.path, "text", text));
                citationSet.add(stripChunkId(c.path)); // Dedupe: same file won't appear multiple times
            }
            citations.addAll(citationSet);
        } catch (Exception e) {
            // On any failure, return empty (don't explode CLI)
        }

        return new Prepared(snippets, citations);
    }

    private static List<LuceneStore.Hit> asLuceneHits(List<CorpusStore.Hit> xs) {
        var out = new ArrayList<LuceneStore.Hit>(xs.size());
        for (var h : xs) out.add(new LuceneStore.Hit(h.path(), h.score()));
        return out;
    }

    private static String stripChunkId(String path) {
        int i = path.indexOf('#');
        return (i < 0) ? path : path.substring(0, i);
    }

    public String readCliSystemPromptOrDefault() throws Exception {
        try (InputStream in = RagService.class.getClassLoader().getResourceAsStream("prompts/cli-system.txt")) {
            if (in != null) return new String(in.readAllBytes());
        }
        return "You are LOQ-J (CLI). Answer briefly, cite local files when available. If context is insufficient, say so.";
    }

    public Answer ask(Path ws, String question, Integer kOverride) {
        try {
            Prepared prepared = prepare(ws, question, kOverride);

            // Check if network is disabled to short-circuit for fast tests
            Map<String,Object> net = CfgUtil.map(cfg.data.get("net"));
            boolean netEnabled = !(net.get("enabled") instanceof Boolean b) || b;

            if (!netEnabled) {
                String stub = "(net disabled) " + question;
                return new Answer(stub, prepared.citations());
            }

            String sys = readCliSystemPromptOrDefault();

            // Validate and trim snippets to fit token budget
            PromptValidator validator = new PromptValidator(cfg);
            PromptValidator.ValidationResult validation = validator.validateAndTrim(
                sys, question, prepared.snippetMaps()
            );

            // Warn if trimming occurred
            if (validation.wasTrimmed) {
                LOG.warn("RAG_CONTEXT_TRIMMED: Reduced snippets from {} to {} to fit {} token budget (estimated {} tokens). Consider reducing :k or enabling vectors.",
                    validation.originalCount, validation.finalCount, validation.budgetTokens, validation.estimatedTokens);
            }

            LlmClient llm = new LlmClient(cfg);
            String text = llm.chat(sys, question, validation.snippets);
            if (text == null) text = "";

            // Warn if we have retrieval but answer is empty
            if (!validation.snippets.isEmpty() && text.trim().isEmpty()) {
                LOG.warn("RAG_GEN_EMPTY: Retrieved {} snippets but answer body is empty (promptTokens≈{}, budget={}). Check model capacity or reduce :k.",
                    validation.snippets.size(), validation.estimatedTokens, validation.budgetTokens);
            }

            return new Answer(text, prepared.citations());
        } catch (Exception e) {
            String msg = "Error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
            return new Answer(msg, List.of());
        }
    }

    /* ====== Minimal session memory for RAG+MEMORY mode ====== */
    public String getMemory() { return sessionMemory; }
    public void clearMemory() { sessionMemory = null; }
    public void updateMemory(String userInput, String answer, int maxItems, int maxNames) {
        String s = (sessionMemory == null ? "" : sessionMemory + "\n") + userInput + "\n" + answer;
        sessionMemory = (s.length() > 4000 ? s.substring(s.length() - 4000) : s);
    }

    /**
     * Ensures index exists for the given workspace. If missing or unreadable, performs lazy indexing.
     * Guard with AtomicBoolean to prevent re-entrancy. Falls back to full rebuild on corruption.
     */
    private void ensureIndexExists(Path workspace) {
        Path indexDir = indexer.indexDirFor(workspace);

        // Check if index exists and is readable
        if (Files.exists(indexDir) && Files.isDirectory(indexDir)) {
            // Try to verify it's a valid Lucene index by attempting to open it
            try (LuceneStore store = new LuceneStore(indexDir, 0)) {
                // If we can open it, assume it's valid
                return;
            } catch (Exception e) {
                // Index exists but is corrupted - log and proceed to rebuild
                LOG.warn("Index directory exists but appears corrupted, will rebuild: {}", e.getMessage());
            }
        }

        // Index missing or corrupted - attempt lazy indexing
        if (!indexingNow.compareAndSet(false, true)) {
            // Already indexing in another thread/call, skip
            return;
        }

        try {
            System.out.print("\rIndexing workspace (first RAG query)... ");
            System.out.flush();

            // Perform indexing with current config (respects vectors setting)
            indexer.index(workspace, false);

            // Print final summary (Indexer already prints this, but ensure newline)
            System.out.println();

        } catch (Exception e) {
            LOG.error("Lazy indexing failed: {}", e.getMessage(), e);
            System.err.println("\rIndexing failed: " + e.getMessage());
        } finally {
            indexingNow.set(false);
        }
    }
}
