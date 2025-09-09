package dev.loqj.core.spi;

import java.util.List;

public interface RetrieverEngine {
    /**
     * Retrieve candidates combining lexical and vector signals when available.
     * @param queryText user query
     * @param qvec optional vector (maybe null)
     * @param k desired candidates
     * @param store open CorpusStore
     */
    List<CorpusStore.Hit> retrieve(String queryText, float[] qvec, int k, CorpusStore store);
}
