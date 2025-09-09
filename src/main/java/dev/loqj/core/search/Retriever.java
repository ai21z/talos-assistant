package dev.loqj.core.search;

import dev.loqj.core.index.LuceneStore;

import java.util.*;
import java.util.stream.Collectors;

/** Reciprocal Rank Fusion + simple MMR-style dedup for paths. */
public class Retriever {
    public static class Cand {
        public final String path;
        public final float score;
        public final String from;
        public Cand(String path, float score, String from) { this.path = path; this.score = score; this.from = from; }
    }

    public static List<Cand> fuseRrf(List<LuceneStore.Hit> bm25, List<LuceneStore.Hit> knn, int rrfK, int topK) {
        Map<String, Double> score = new HashMap<>();
        for (int i = 0; i < bm25.size(); i++) {
            score.merge(bm25.get(i).path, 1.0 / (rrfK + i + 1), Double::sum);
        }
        for (int i = 0; i < knn.size(); i++) {
            score.merge(knn.get(i).path, 1.0 / (rrfK + i + 1), Double::sum);
        }
        return score.entrySet().stream()
                .sorted((a,b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(topK)
                .map(e -> new Cand(e.getKey(), e.getValue().floatValue(), "rrf"))
                .collect(Collectors.toList());
    }

    public static List<Cand> mmr(List<Cand> cands, double lambda, int finalK) {
        // Simple dedup by path then take top finalK. (lambda reserved for future reranking)
        LinkedHashMap<String, Cand> uniq = new LinkedHashMap<>();
        for (Cand c : cands) uniq.putIfAbsent(c.path, c);
        return new ArrayList<>(uniq.values()).subList(0, Math.min(finalK, uniq.size()));
    }
}
