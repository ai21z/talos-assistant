package dev.talos.core.embed;

import java.util.Objects;

/**
 * First-class identity for an embedding model configuration.
 * <p>
 * Captures all parameters that affect the embedding vector space: provider,
 * model, dimensions, instruction mode, and normalization. Two profiles that
 * differ in any of these fields produce <em>incompatible</em> vector spaces -
 * their embeddings must not be mixed in the same index or cache namespace.
 * <p>
 * Use {@link #fingerprint()} for index compatibility checks and
 * {@link #cacheNamespace()} for embedding cache key isolation.
 *
 * @param provider             backend id: "compat", "llama_cpp", "ollama", etc.
 * @param model                model identifier as the backend knows it
 * @param dimensions           expected vector dimensionality (0 = auto-detect at runtime)
 * @param instructionAware     whether query/document embedding requires instruction prefixes
 * @param queryInstruction     prefix prepended to query text before embedding (null/empty = none)
 * @param documentInstruction  prefix prepended to document text before embedding (null/empty = none)
 * @param maxInputTokens       maximum input length the model accepts (tokens)
 * @param normalize            whether the model outputs L2-normalized vectors
 */
public record EmbeddingProfile(
        String provider,
        String model,
        int dimensions,
        boolean instructionAware,
        String queryInstruction,
        String documentInstruction,
        int maxInputTokens,
        boolean normalize
) {
    public EmbeddingProfile {
        Objects.requireNonNull(provider, "provider must not be null");
        Objects.requireNonNull(model, "model must not be null");
    }

    // ── Built-in profiles ────────────────────────────────────────────────

    /**
     * bge-m3: lightweight 1024-dim model, no instruction prefixes, runs on CPU.
     * This remains a supported legacy Ollama embedding profile. The default
     * beta configuration is BM25-only until the user configures embeddings.
     */
    public static final EmbeddingProfile BGE_M3 = new EmbeddingProfile(
            "ollama", "bge-m3", 1024,
            false, null, null,
            8192, true
    );

    /**
     * Qwen/Qwen3-Embedding-8B: instruction-aware, 4096 native dims
     * (recommended at 1024 via Matryoshka for index compat with bge-m3).
     * <p>
     * Default built-in provider is {@code "ollama"} for explicit legacy
     * Ollama embedding configs. OpenAI-compatible local embedding endpoints are
     * available through {@code embed.provider: "compat"}.
     * <p>
     * The query instruction uses a neutral retrieval prompt. Override via
     * {@code embed.query_instruction} in config for domain-specific tuning.
     */
    public static final EmbeddingProfile QWEN3_EMBED_8B = new EmbeddingProfile(
            "ollama", "Qwen/Qwen3-Embedding-8B", 1024,
            true,
            "Instruct: Given a query, retrieve relevant passages that answer the query\nQuery: ",
            null,
            32768, true
    );

    // ── Identity operations ──────────────────────────────────────────────

    /**
     * Deterministic fingerprint encoding every parameter that affects the
     * vector space. Two profiles with different fingerprints produce
     * incompatible embeddings - they must not share an index or cache.
     * <p>
     * Includes a hash of instruction strings so that changing the query or
     * document instruction template invalidates compatibility.
     * <p>
     * Format: {@code provider:model:dims:instr|plain:norm|raw[:ihash]}
     */
    public String fingerprint() {
        String base = provider + ":" + model + ":" + dimensions + ":"
                + (instructionAware ? "instr" : "plain") + ":"
                + (normalize ? "norm" : "raw");
        if (instructionAware) {
            String instrContent = (queryInstruction == null ? "" : queryInstruction)
                    + "|" + (documentInstruction == null ? "" : documentInstruction);
            base += ":" + String.format("%08x", instrContent.hashCode());
        }
        return base;
    }

    /**
     * Cache namespace for embedding cache isolation.
     * <p>
     * Delegates to {@link #fingerprint()} so that any parameter change that
     * affects the vector space also changes the cache key - preventing stale
     * vector reuse across incompatible profiles.
     * <p>
     * <strong>Note:</strong> This intentionally breaks backward compatibility
     * with the legacy {@code "ollama/bge-m3"} cache keys. Existing cached
     * embeddings will become cache misses on first run after upgrade - they
     * will be recomputed and cached under the new key. This is the correct
     * trade-off: cache safety &gt; one-time cold start.
     */
    public String cacheNamespace() {
        return fingerprint();
    }

    /**
     * True when query embeddings need a different instruction prefix than
     * document embeddings (or any prefix at all). When false, query and
     * document embeddings use the same plain-text path.
     */
    public boolean requiresQueryDocumentSplit() {
        return instructionAware
                && (hasContent(queryInstruction) || hasContent(documentInstruction));
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isEmpty();
    }
}

