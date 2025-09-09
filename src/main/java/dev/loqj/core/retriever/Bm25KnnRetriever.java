package dev.loqj.core.retriever;

import dev.loqj.core.spi.CorpusStore;
import dev.loqj.core.spi.RetrieverEngine;

import java.util.*;

public class Bm25KnnRetriever implements RetrieverEngine {
    @Override
    public List<CorpusStore.Hit> retrieve(String queryText, float[] qvec, int k, CorpusStore store) {
        var bm25 = store.bm25(queryText, k);
        var knn  = store.knn(qvec, k);

        Map<String, Double> score = new HashMap<>();
        rrf(bm25, score, 60.0);
        rrf(knn,  score, 60.0);

        return score.entrySet().stream()
                .sorted((a,b) -> Double.compare(b.getValue(), a.getValue()))
                .limit(Math.max(1, k))
                .map(e -> new CorpusStore.Hit(e.getKey(), e.getValue().floatValue()))
                .toList();
    }

    private static void rrf(List<CorpusStore.Hit> hits, Map<String, Double> acc, double k) {
        for (int i = 0; i < hits.size(); i++) {
            var h = hits.get(i);
            double add = 1.0 / (k + (i + 1));
            acc.merge(h.path(), add, Double::sum);
        }
    }
}
