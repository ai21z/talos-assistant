package dev.talos.core.embed;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.core.spi.Embeddings;
import java.util.Map;
import java.util.Objects;
/**
 * Constructs embedding clients based on the active {@link EmbeddingProfile}.
 * <p>
 * Provides separate factory methods for query and document embedding to
 * make the query/document distinction explicit in the API. For models
 * that are not instruction-aware (e.g. bge-m3) both methods return
 * equivalent clients. For instruction-aware models (e.g. Qwen3-Embedding-8B)
 * the query client wraps the raw transport with the appropriate instruction
 * prefix.
 * <p>
 * <strong>PR1 scope:</strong> Only the Ollama transport is implemented.
 * The factory always constructs {@link EmbeddingsClient} as the raw
 * transport. Future PRs will add OpenAI-compatible transport selection
 * based on {@code embed.provider} in config.
 */
public final class EmbeddingsFactory {
    private EmbeddingsFactory() {}
    /**
     * Resolve the active embedding profile from configuration.
     * <p>
     * Reads {@code embed.model} first (new canonical key), falling back to
     * {@code ollama.embed} (legacy key), then to the bge-m3 built-in default.
     * Provider is read from {@code embed.provider}, defaulting to {@code "ollama"}.
     */
    public static EmbeddingProfile profileFrom(Config cfg) {
        Objects.requireNonNull(cfg, "cfg must not be null");
        Map<String, Object> embedCfg = CfgUtil.map(cfg.data.get("embed"));
        Map<String, Object> ollamaCfg = CfgUtil.map(cfg.data.get("ollama"));
        // Model: embed.model > ollama.embed > "bge-m3"
        String model = stringOr(embedCfg.get("model"), null);
        if (model == null) {
            model = stringOr(ollamaCfg.get("embed"), "bge-m3");
        }
        // Provider: embed.provider > "ollama"
        String provider = stringOr(embedCfg.get("provider"), "ollama");
        // Check for a known built-in profile match
        if (EmbeddingProfile.BGE_M3.model().equals(model)
                && EmbeddingProfile.BGE_M3.provider().equals(provider)) {
            return EmbeddingProfile.BGE_M3;
        }
        if (EmbeddingProfile.QWEN3_EMBED_8B.model().equals(model)) {
            return EmbeddingProfile.QWEN3_EMBED_8B;
        }
        // Construct a custom profile from config values
        int dims = CfgUtil.intAt(embedCfg, "dimensions", 0);
        // Instruction prefixes may intentionally have trailing whitespace — do NOT trim.
        String qInstr = rawStringOr(embedCfg.get("query_instruction"), null);
        String dInstr = rawStringOr(embedCfg.get("document_instruction"), null);
        boolean instrAware = qInstr != null || dInstr != null;
        int maxInput = CfgUtil.intAt(embedCfg, "max_input_tokens", 8192);
        boolean normalize = CfgUtil.boolAt(embedCfg, "normalize", true);
        return new EmbeddingProfile(
                provider, model, dims, instrAware,
                qInstr, dInstr, maxInput, normalize);
    }
    /**
     * Create an {@link Embeddings} client configured for <em>query</em> embedding.
     * <p>
     * If the active profile is instruction-aware and has a query instruction,
     * the returned client automatically prepends the instruction prefix.
     * Otherwise returns the raw transport client.
     */
    public static Embeddings forQuery(Config cfg) {
        EmbeddingProfile profile = profileFrom(cfg);
        Embeddings raw = createRawClient(cfg);
        if (profile.instructionAware() && hasContent(profile.queryInstruction())) {
            return new InstructionEmbeddings(raw, profile.queryInstruction());
        }
        return raw;
    }
    /**
     * Create an {@link Embeddings} client configured for <em>document</em> embedding.
     * <p>
     * If the active profile is instruction-aware and has a document instruction,
     * the returned client automatically prepends the instruction prefix.
     * Otherwise returns the raw transport client.
     */
    public static Embeddings forDocument(Config cfg) {
        EmbeddingProfile profile = profileFrom(cfg);
        Embeddings raw = createRawClient(cfg);
        if (profile.instructionAware() && hasContent(profile.documentInstruction())) {
            return new InstructionEmbeddings(raw, profile.documentInstruction());
        }
        return raw;
    }
    // ── Internal ─────────────────────────────────────────────────────────
    /**
     * Construct the raw transport-level embeddings client.
     * <p>
     * PR1: always returns {@link EmbeddingsClient} (Ollama transport).
     * Future PRs will switch on {@code embed.provider} to select
     * OpenAI-compatible or other transports.
     */
    private static Embeddings createRawClient(Config cfg) {
        return new EmbeddingsClient(cfg);
    }
    private static String stringOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? fallback : s;
    }
    /** Like {@link #stringOr} but preserves whitespace — required for instruction prefixes. */
    private static String rawStringOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o);
        return s.isEmpty() ? fallback : s;
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isEmpty();
    }
}
