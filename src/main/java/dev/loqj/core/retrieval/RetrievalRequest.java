package dev.loqj.core.retrieval;

import java.util.Objects;

/**
 * Immutable request to the retrieval pipeline.
 * Carries the user query, optional query vector, and desired result count.
 */
public final class RetrievalRequest {

    private final String query;
    private final float[] queryVector; // nullable — absent when vectors are disabled
    private final int topK;

    public RetrievalRequest(String query, float[] queryVector, int topK) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.queryVector = queryVector; // null is valid (BM25-only mode)
        this.topK = Math.max(1, topK);
    }

    public String query()          { return query; }
    public float[] queryVector()   { return queryVector; }
    public int topK()              { return topK; }
    public boolean hasVector()     { return queryVector != null && queryVector.length > 0; }

    @Override
    public String toString() {
        return "RetrievalRequest{query='" + query + "', topK=" + topK
                + ", hasVector=" + hasVector() + '}';
    }
}

