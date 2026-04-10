package dev.talos.core.embed;

import java.util.Objects;

/**
 * First-class identity for an embedding model configuration.
 * <p>
 * Captures all parameters that affect the embedding vector space: provider,
 * model, dimensions, instruction mode, and normalization. Two profiles that
 * differ in any of these fields produce <em>incompatible</em> vector spaces —
 * their embeddings must not be mixed in the same index or cache namespace.
 * <p>
 * Use {@link #fingerprint()} for index compatibility checks and
 * {@link #cacheNamespace()} for embedding cache key isolation.
 *
 * @param provider             backend id: "ollama", "vllm", "openai_compat"
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
     * This is the current Talos default.
     */
    public static final EmbeddingProfile BGE_M3 = new EmbeddingProfile(
            "ollama", "bge-m3", 1024,
            false, null, null,
            8192, true
    );

    /**
     * Qwen/Qwen3-Embedding-8B: instruction-aware, 4096 native dims
     * (recommended at 1024 via Matryoshka for index compat with bge-m3).
     * Requires vLLM or OpenAI-compatible backend.
     */
    public static final EmbeddingProfile QWEN3_EMBED_8B = new EmbeddingProfile(
            "vllm", "Qwen/Qwen3-Embedding-8B", 1024,
            true,
            "Instruct: Given a web search query, retrieve relevant passages that answer the query\nQuery: ",
            null,
            32768, true
    );

    // ── Identity operations ──────────────────────────────────────────────

    /**
     * Deterministic fingerprint encoding every parameter that affects the
     * vector space. Two profiles with different fingerprints produce
     * incompatible embeddings — they must not share an index or cache.
     * <p>
     * Format: {@code provider:model:dims:instr|plain:norm|raw}
     */
    public String fingerprint() {
        return provider + ":" + model + ":" + dimensions + ":"
                + (instructionAware ? "instr" : "plain") + ":"
                + (normalize ? "norm" : "raw");
    }

    /**
     * Cache namespace for embedding cache isolation.
     * Shorter than fingerprint — suitable for SQLite cache keys.
     * Format: {@code provider/model}
     */
    public String cacheNamespace() {
        return provider + "/" + model;
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

