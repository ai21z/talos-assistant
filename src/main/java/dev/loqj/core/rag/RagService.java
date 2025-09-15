package dev.loqj.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.embed.CachingEmbeddings;
import dev.loqj.core.embed.EmbeddingsClient;
import dev.loqj.core.index.Indexer;
import dev.loqj.core.index.LuceneStore;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.spi.CorpusStore;
import dev.loqj.core.util.Hash;
import dev.loqj.core.search.Retriever;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

public class RagService {

    private final Config cfg;
    private final Indexer indexer;

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
        int defaultTopK = 6;
        try {
            Map<String, Object> rag = CfgUtil.map(cfg.data.get("rag"));
            Object v = (rag == null ? null : rag.get("top_k"));
            if (v instanceof Number) defaultTopK = ((Number) v).intValue();
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

            // Build snippet maps + citations
            for (var c : finalCands) {
                String text = store.getTextByPath(c.path);
                if (text == null || text.isBlank()) continue;
                snippets.add(Map.of("path", c.path, "text", text));
                citations.add(stripChunkId(c.path));
            }
        } catch (Exception e) {
            // On any failure, return empty (don’t explode CLI)
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

            // If network is disabled we can short-circuit to keep tests fast
            Map<String,Object> net = CfgUtil.map(cfg.data.get("net"));
            boolean netEnabled = !(net.get("enabled") instanceof Boolean b) || b;

            if (!netEnabled) {
                String stub = "(net disabled) " + question;
                return new Answer(stub, prepared.citations());
            }

            LlmClient llm = new LlmClient(cfg);
            String sys = readCliSystemPromptOrDefault();
            String text = llm.chat(sys, question, prepared.snippetMaps());
            if (text == null) text = "";
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
}
