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
    private final String embeddingFailureReason; // nullable — set when embedding failed

    public RetrievalRequest(String query, float[] queryVector, int topK) {
        this(query, queryVector, topK, null);
    }

    public RetrievalRequest(String query, float[] queryVector, int topK, String embeddingFailureReason) {
        this.query = Objects.requireNonNull(query, "query must not be null");
        this.queryVector = queryVector; // null is valid (BM25-only mode)
        this.topK = Math.max(1, topK);
        this.embeddingFailureReason = embeddingFailureReason;
    }

    public String query()                    { return query; }
    public float[] queryVector()             { return queryVector; }
    public int topK()                        { return topK; }
    public boolean hasVector()               { return queryVector != null && queryVector.length > 0; }
    /** Nullable reason why embedding failed (when vector is absent due to error). */
    public String embeddingFailureReason()   { return embeddingFailureReason; }

    @Override
    public String toString() {
        String base = "RetrievalRequest{query='" + query + "', topK=" + topK
                + ", hasVector=" + hasVector();
        if (embeddingFailureReason != null) base += ", embeddingFailed=" + embeddingFailureReason;
        return base + '}';
    }
}
