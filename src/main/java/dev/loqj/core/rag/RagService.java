package dev.loqj.core.rag;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.stream.Collectors;

/** Prepares retrieval (BM25 + optional KNN), performs light fusion, and returns snippet maps + citations. */
public class RagService {
    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    private final Config cfg;
    private final ObjectMapper mapper = new ObjectMapper();

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

        int rrfK = CfgUtil.intAt(CfgUtil.map(rag.get("fuse")), "rrf_k", 60);
        double mmrLambda = CfgUtil.doubleAt(CfgUtil.map(rag.get("fuse")), "mmr_lambda", 0.7);
        int finalK = CfgUtil.intAt(CfgUtil.map(rag.get("fuse")), "final_k", Math.max(10, reqTopK));

        // Index location
        var indexDir = new Indexer(cfg).indexDirFor(workspace);

        // Retrieve candidates
        List<CorpusStore.Hit> bm25Hits;
        List<CorpusStore.Hit> knnHits = List.of();
        boolean vectorsEnabled = Boolean.TRUE.equals(CfgUtil.map(rag.get("vectors")).getOrDefault("enabled", true));

        try (var store = new LuceneStore(indexDir, 0)) { // vectorDim not required for reading/searching
            bm25Hits = store.bm25(question, Math.max(2 * reqTopK, 20));

            if (vectorsEnabled) {
                try {
                    var emb = new EmbeddingsClient(cfg);
                    float[] qvec = emb.embed(question);
                    if (qvec != null && qvec.length > 0) {
                        knnHits = store.knn(qvec, Math.max(2 * reqTopK, 20));
                    }
                } catch (Exception e) {
                    LOG.debug("Embedding failed; continuing with BM25 only: {}", e.toString());
                }
            }

            // Fuse
            var fused = rrfFuse(bm25Hits, knnHits, rrfK, finalK, mmrLambda);

            // Materialize snippets
            List<Map<String,String>> snippets = new ArrayList<>(fused.size());
            List<String> citations = new ArrayList<>(fused.size());
            for (var h : fused) {
                String text = store.getTextByPath(h.path());
                if (text == null) continue;
                // Trim very long chunks defensively; SnippetBuilder will pack further.
                if (text.length() > 2000) text = text.substring(0, 2000);
                snippets.add(Map.of("path", h.path(), "text", text));
                citations.add(h.path());
            }
            return new Prepared(snippets, citations);
        } catch (Exception e) {
            LOG.warn("prepare failed", e);
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
            LOG.warn("ask failed", e);
            text = "Ask failed: " + e.getMessage();
        }
        return new Answer(text, p.citations());
    }

    /** Reciprocal Rank Fusion + simple path-based diversification. */
    private static List<CorpusStore.Hit> rrfFuse(List<CorpusStore.Hit> bm25,
                                                 List<CorpusStore.Hit> knn,
                                                 int rrfK,
                                                 int finalK,
                                                 double mmrLambda) {
        Map<String, Double> score = new HashMap<>();
        Map<String, Integer> firstRank = new HashMap<>();

        // Helper to update scores
        java.util.function.BiConsumer<List<CorpusStore.Hit>, Double> addList = (hits, weight) -> {
            for (int i = 0; i < hits.size(); i++) {
                var h = hits.get(i);
                double s = 1.0 / (rrfK + i + 1.0);
                score.merge(h.path(), weight * s, Double::sum);
                firstRank.putIfAbsent(h.path(), i);
            }
        };

        addList.accept(bm25, 1.0);
        addList.accept(knn, 1.0);

        // Diversity: if multiple chunks from the same file appear, penalize later ones a bit
        Map<String, Integer> seenPerFile = new HashMap<>();
        List<String> keys = new ArrayList<>(score.keySet());
        keys.sort((a, b) -> Double.compare(score.getOrDefault(b, 0.0), score.getOrDefault(a, 0.0)));

        List<CorpusStore.Hit> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        for (String path : keys) {
            if (out.size() >= finalK) break;
            // base file key (strip #chunkId)
            String base = path;
            int hash = base.indexOf('#');
            if (hash >= 0) base = base.substring(0, hash);

            int nSeen = seenPerFile.getOrDefault(base, 0);
            double penalty = (nSeen == 0) ? 1.0 : Math.max(0.5, 1.0 - 0.15 * nSeen);

            double s = score.get(path) * penalty;

            if (seen.add(path)) {
                out.add(new CorpusStore.Hit(path, (float) s));
                seenPerFile.put(base, nSeen + 1);
            }
        }
        return out;
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
