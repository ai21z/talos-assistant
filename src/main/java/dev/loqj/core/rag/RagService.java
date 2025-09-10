package dev.loqj.core.rag;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.embed.EmbeddingsClient;
import dev.loqj.core.index.Indexer;
import dev.loqj.core.index.LuceneStore;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.spi.CorpusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.*;

/** Prepares retrieval (BM25 + optional KNN), performs RRF→MMR fusion, and returns snippet maps + citations. */
public class RagService {
    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    private final Config cfg;

    public RagService(Config cfg) { this.cfg = cfg; }

    /** Small DTO to carry prepared snippets and citation strings. */
    public record Prepared(List<Map<String,String>> snippetMaps, List<String> citations) {}

    /** High-level answer DTO used by CLI commands like RagAskCmd. */
    public record Answer(String text, List<String> citations) {}

    /** Build snippets & citations for a question. */
    public Prepared prepare(Path workspace, String question, Integer kOverride) {
        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        int cfgTopK = CfgUtil.intAt(rag, "top_k", 6);
        int reqTopK = (kOverride == null || kOverride <= 0) ? cfgTopK : kOverride;

        Map<String,Object> fuse = CfgUtil.map(rag.get("fuse"));
        int rrfK         = CfgUtil.intAt(fuse, "rrf_k", 60);
        double mmrLambda = clamp01(CfgUtil.doubleAt(fuse, "mmr_lambda", 0.70));
        int finalK       = CfgUtil.intAt(fuse, "final_k", Math.max(10, reqTopK));

        int snippetMax   = CfgUtil.intAt(rag, "snippet_max_chars", 2000);
        Map<String,Object> vec = CfgUtil.map(rag.get("vectors"));
        boolean vectorsEnabled = Boolean.TRUE.equals(vec.getOrDefault("enabled", Boolean.TRUE));

        // Index location
        var indexDir = new Indexer(cfg).indexDirFor(workspace);

        List<CorpusStore.Hit> bm25Hits;
        List<CorpusStore.Hit> knnHits = Collections.emptyList();

        try (var store = new LuceneStore(indexDir, 0)) { // vectorDim not required for reading/searching
            int pool = Math.max(2 * reqTopK, 40);

            // BM25 candidates
            bm25Hits = store.bm25(question, pool);
            LOG.debug("RAG/BM25: {} hits (pool={})", bm25Hits.size(), pool);

            // Optional KNN candidates
            if (vectorsEnabled) {
                try {
                    var emb = new EmbeddingsClient(cfg);
                    float[] qvec = emb.embed(question);
                    if (qvec != null && qvec.length > 0) {
                        knnHits = store.knn(qvec, pool);
                        LOG.debug("RAG/KNN: {} hits (vectors enabled)", knnHits.size());
                    } else {
                        LOG.debug("RAG/KNN: embedding vector empty; skipping KNN.");
                    }
                } catch (Exception e) {
                    LOG.debug("RAG/KNN: embedding failed; proceeding BM25-only: {}", e.toString());
                }
            } else {
                LOG.debug("RAG/KNN: vectors disabled via config; proceeding BM25-only.");
            }

            // RRF → MMR fusion
            var fused = fuseRrfThenMmr(bm25Hits, knnHits, rrfK, finalK, mmrLambda);

            // Materialize snippets (defensive trim; SnippetBuilder will pack later)
            List<Map<String,String>> snippets = new ArrayList<>(fused.size());
            List<String> citations = new ArrayList<>(fused.size());

            for (var h : fused) {
                String path = h.path();
                String text = store.getTextByPath(path);
                if (text == null || text.isBlank()) {
                    LOG.debug("RAG: no text for path {}", path);
                    continue;
                }
                if (text.length() > snippetMax) text = text.substring(0, Math.max(0, snippetMax));
                snippets.add(Map.of("path", path, "text", text));
                citations.add(path);
            }

            LOG.debug("RAG: prepared {} snippets (finalK={}, snippetMax={})", snippets.size(), finalK, snippetMax);
            return new Prepared(snippets, citations);

        } catch (Exception e) {
            LOG.warn("RAG prepare failed: {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            return new Prepared(List.of(), List.of());
        }
    }

    /** Back-compat API used by RagAskCmd: run retrieval, call LLM with rag-system prompt, return text + citations. */
    public Answer ask(Path workspace, String question, Integer kOverride) {
        Prepared p = prepare(workspace, question, kOverride);
        String system = readRagSystemPromptOrDefault();
        String text;
        try {
            text = new LlmClient(cfg).chat(system, question, p.snippetMaps());
            if (text == null || text.isBlank()) {
                text = "I'm not sure based on the provided context.";
            }
        } catch (Exception e) {
            LOG.warn("RAG ask failed: {}: {}", e.getClass().getSimpleName(), e.getMessage(), e);
            text = "Ask failed: " + e.getMessage();
        }
        return new Answer(text, p.citations());
    }

    /** RRF scoring followed by simple MMR selection (diversity by base-file). */
    private static List<CorpusStore.Hit> fuseRrfThenMmr(List<CorpusStore.Hit> bm25,
                                                        List<CorpusStore.Hit> knn,
                                                        int rrfK,
                                                        int finalK,
                                                        double mmrLambda) {
        // 1) RRF scores (equal weight for now; extendable later)
        Map<String, Double> rrfScore = new LinkedHashMap<>();
        Map<String, Integer> firstRank = new HashMap<>();

        addRrf(rrfScore, firstRank, bm25, rrfK, 1.0);
        addRrf(rrfScore, firstRank, knn,  rrfK, 1.0);

        if (rrfScore.isEmpty()) return List.of();

        // Sort by RRF (desc), deterministic by path if tie
        List<Map.Entry<String, Double>> cands = new ArrayList<>(rrfScore.entrySet());
        cands.sort((a, b) -> {
            int cmp = Double.compare(b.getValue(), a.getValue());
            return (cmp != 0) ? cmp : a.getKey().compareTo(b.getKey());
        });

        // 2) MMR selection
        double maxRrf = cands.get(0).getValue();
        List<CorpusStore.Hit> out = new ArrayList<>();
        Set<String> seenBases = new HashSet<>();

        while (out.size() < finalK && !cands.isEmpty()) {
            String bestKey = null;
            double bestVal = Double.NEGATIVE_INFINITY;

            for (var e : cands) {
                String path = e.getKey();
                double rel = (maxRrf > 0) ? (e.getValue() / maxRrf) : e.getValue(); // ~[0,1]
                String base = basePath(path);
                double diversityPenalty = seenBases.contains(base) ? 1.0 : 0.0; // 1 if same file already selected

                double val = mmrLambda * rel - (1.0 - mmrLambda) * diversityPenalty;
                if (val > bestVal) {
                    bestVal = val;
                    bestKey = path;
                }
            }

            if (bestKey == null) break;

            out.add(new CorpusStore.Hit(bestKey, rrfScore.get(bestKey).floatValue()));
            seenBases.add(basePath(bestKey));

            // remove chosen key from candidates
            final String chosen = bestKey;
            cands.removeIf(en -> en.getKey().equals(chosen));
        }

        return out;
    }

    private static void addRrf(Map<String, Double> score,
                               Map<String, Integer> firstRank,
                               List<CorpusStore.Hit> hits,
                               int rrfK,
                               double weight) {
        if (hits == null || hits.isEmpty()) return;
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            double s = weight * (1.0 / (rrfK + i + 1.0));
            score.merge(h.path(), s, Double::sum);
            firstRank.putIfAbsent(h.path(), i);
        }
    }

    private static String basePath(String path) {
        int hash = (path == null) ? -1 : path.indexOf('#');
        return (hash >= 0) ? path.substring(0, hash) : path;
    }

    private static double clamp01(double x) {
        if (Double.isNaN(x)) return 0.0;
        return (x < 0.0) ? 0.0 : (x > 1.0 ? 1.0 : x);
    }

    /** Fallback system prompt if classpath resource is missing (CLI-style). */
    public String readCliSystemPromptOrDefault() {
        try (var in = RagService.class.getClassLoader().getResourceAsStream("prompts/cli-system.txt")) {
            if (in != null) return new String(in.readAllBytes());
        } catch (Exception ignore) {}
        return """
            You are LOQ-J’s local CLI assistant. Behave like a developer shell with RAG.
            Rules:
            - When the user asks to "open", "show", or "view" a file, summarize or quote from the provided file snippets.
            - Answer in plain text. No JSON, no code fences unless the content itself is code.
            - Prefer information from provided context snippets. If unsure, say so concisely.
            - Do not invent files or directories.
        """;
    }

    /** Fallback system prompt for pure RAG Q&A (used by rag-ask). */
    public String readRagSystemPromptOrDefault() {
        try (var in = RagService.class.getClassLoader().getResourceAsStream("prompts/rag-system.txt")) {
            if (in != null) return new String(in.readAllBytes());
        } catch (Exception ignore) {}
        return """
            You are a repository-aware assistant. Use the provided snippets as primary source of truth.
            Answer in plain text. No JSON unless explicitly requested. Cite file names when asked.
        """;
    }
}
