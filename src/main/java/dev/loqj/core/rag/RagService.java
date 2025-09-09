package dev.loqj.core.rag;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.embed.EmbeddingsClient;
import dev.loqj.core.index.Indexer;
import dev.loqj.core.index.LuceneStore;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.retriever.Bm25KnnRetriever;
import dev.loqj.core.search.SnippetBuilder;
import dev.loqj.core.spi.CorpusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

public class RagService {
    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    public record Answer(String text, List<String> citations) {}
    public static record Prepared(List<Map<String,String>> snippetMaps, List<String> citations) {}

    private final Config cfg;
    public RagService(Config cfg) { this.cfg = cfg; }

    /* ---------- REPL helpers ---------- */
    public String readSystemPrompt() throws Exception { return readResource("prompts/system.txt"); }

    public String readCliSystemPromptOrDefault() throws Exception {
        try (InputStream in = RagService.class.getClassLoader().getResourceAsStream("prompts/cli-system.txt")) {
            if (in != null) return new String(in.readAllBytes());
        }
        return readSystemPrompt();
    }

    public Prepared prepare(Path root, String question, Integer kOverride) throws Exception {
        Path abs = root.toAbsolutePath().normalize();
        Path indexDir = new Indexer(cfg).indexDirFor(abs);

        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        boolean vecEnabled = Boolean.TRUE.equals(CfgUtil.map(rag.get("vectors")).getOrDefault("enabled", true));

        float[] qvec = null;
        if (vecEnabled) {
            try { qvec = new EmbeddingsClient(cfg).embed(question); }
            catch (Exception e) { LOG.debug("embed disabled/fail: {}", e.toString()); }
        }

        try (CorpusStore store = new LuceneStore(indexDir, (qvec == null ? 0 : qvec.length))) {
            int topk = (kOverride != null) ? kOverride : CfgUtil.intAt(rag, "top_k", 8);
            var hits = new Bm25KnnRetriever().retrieve(question, qvec, topk, store);
            var snippets = dev.loqj.core.search.SnippetBuilder.pack(store, hits, 3000);

            var cites = new ArrayList<String>(snippets.size());
            for (var s : snippets) cites.add(s.path());
            return new Prepared(toSnippetMaps(snippets), cites);
        }
    }

    /* ---------- Simple ask (RAG, no memory) ---------- */
    public Answer ask(Path root, String question, Integer kOverride) {
        try {
            Path abs = root.toAbsolutePath().normalize();
            Path indexDir = new Indexer(cfg).indexDirFor(abs);

            Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
            boolean vecEnabled = Boolean.TRUE.equals(CfgUtil.map(rag.get("vectors")).getOrDefault("enabled", true));

            float[] qvec = null;
            if (vecEnabled) {
                try { qvec = new EmbeddingsClient(cfg).embed(question); }
                catch (Exception e) { LOG.debug("embed disabled/fail: {}", e.toString()); }
            }

            try (CorpusStore store = new LuceneStore(indexDir, (qvec == null ? 0 : qvec.length))) {
                int topk = (kOverride != null) ? kOverride : CfgUtil.intAt(rag, "top_k", 8);

                var hits = new Bm25KnnRetriever().retrieve(question, qvec, topk, store);
                var snippets = SnippetBuilder.pack(store, hits, 3000);

                String system = readSystemPrompt();
                String answer = new LlmClient(cfg).chat(system, question, toSnippetMaps(snippets));

                List<String> cites = new ArrayList<>(snippets.size());
                for (var s : snippets) cites.add(s.path());
                return new Answer(answer, cites);
            }
        } catch (Exception e) {
            LOG.warn("rag ask failed", e);
            return new Answer("RAG ask failed: " + e.getMessage(), List.of());
        }
    }

    /* ---------- RAG + Memory (file-backed; CAG v1) ---------- */
    public Answer askWithCag(Path root, String question, Integer kOverride) {
        Path abs = root.toAbsolutePath().normalize();
        try {
            LlmClient llm = new LlmClient(cfg);

            // Load memory
            MemoryManager memMgr = new MemoryManager(abs);
            MemoryManager.Memory mem = memMgr.load();

            // Build query variants from memory
            List<String> queries = new ArrayList<>();
            queries.add(question);
            if (!mem.sketch().isBlank()) queries.add(mem.sketch() + " " + question);
            for (String e : mem.entitiesOrEmpty()) {
                if (queries.size() >= 6) break;
                queries.add(e + " " + question);
            }

            Path indexDir = new Indexer(cfg).indexDirFor(abs);
            Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
            boolean vecEnabled = Boolean.TRUE.equals(CfgUtil.map(rag.get("vectors")).getOrDefault("enabled", true));
            int topk = (kOverride != null) ? kOverride : CfgUtil.intAt(rag, "top_k", 8);

            // RRF fuse variants
            Map<String, Double> acc = new HashMap<>();
            var retr = new Bm25KnnRetriever();
            for (String q : queries) {
                float[] qv = null;
                if (vecEnabled) {
                    try { qv = new EmbeddingsClient(cfg).embed(q); }
                    catch (Exception e) { LOG.debug("embed disabled/fail: {}", e.toString()); }
                }
                try (CorpusStore store = new LuceneStore(indexDir, (qv == null ? 0 : qv.length))) {
                    var hits = retr.retrieve(q, qv, topk, store);
                    rrf(acc, hits, 60.0);
                }
            }

            var fused = acc.entrySet().stream()
                    .sorted((a,b)->Double.compare(b.getValue(),a.getValue()))
                    .limit(topk)
                    .map(e -> new CorpusStore.Hit(e.getKey(), e.getValue().floatValue()))
                    .toList();

            try (CorpusStore store = new LuceneStore(indexDir, 0)) {
                var snippets = SnippetBuilder.pack(store, fused, 3000);
                String system = readCliSystemPromptOrDefault();
                String answer = llm.chat(system, question, toSnippetMaps(snippets));

                List<String> cites = new ArrayList<>(snippets.size());
                for (var s : snippets) cites.add(s.path());

                // Update memory via plain-text call
                MemoryManager.Memory updated = MemoryPrompts.refresh(mem, question, answer, cites, llm);
                memMgr.save(updated);

                return new Answer(answer, cites);
            }
        } catch (Exception e) {
            LOG.warn("rag cag ask failed", e);
            return new Answer("RAG+CAG ask failed: " + e.getMessage(), List.of());
        }
    }

    private static void rrf(Map<String, Double> acc, List<CorpusStore.Hit> hits, double k) {
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            double add = 1.0 / (k + (i+1));
            acc.merge(h.path(), add, Double::sum);
        }
    }

    private static List<Map<String,String>> toSnippetMaps(List<SnippetBuilder.Snippet> ss) {
        var out = new ArrayList<Map<String,String>>(ss.size());
        for (var s : ss) out.add(Map.of("path", s.path(), "text", s.text()));
        return out;
    }

    private static String readResource(String name) throws Exception {
        try (InputStream in = RagService.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) throw new IllegalStateException("Missing resource: " + name);
            return new String(in.readAllBytes());
        }
    }
}
