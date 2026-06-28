package dev.talos.core.embed;
import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.spi.Embeddings;
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
 * Supports explicit transport selection through {@code embed.provider}. The
 * default is disabled/BM25-only until a user configures a local embedding
 * endpoint. Ollama remains available as a legacy provider, while compat
 * providers use OpenAI-compatible local embedding endpoints.
 */
public final class EmbeddingsFactory {
    private EmbeddingsFactory() {}
    /**
     * Resolve the active embedding profile from configuration.
     * <p>
     * Reads {@code embed.model} first (new canonical key), falling back to
     * {@code ollama.embed} (legacy key for explicit Ollama configs), then to
     * {@code "none"}. Provider is read from {@code embed.provider}, defaulting
     * to {@code "disabled"}.
     * <p>
     * When the resolved model name matches a known built-in profile, the
     * built-in is used as <em>defaults</em> - not as an unconditional
     * replacement. Any config overrides for provider, dimensions,
     * query_instruction, document_instruction, max_input_tokens, or normalize
     * take precedence. If the resolved profile matches the built-in exactly,
     * the singleton instance is returned.
     */
    public static EmbeddingProfile profileFrom(Config cfg) {
        Objects.requireNonNull(cfg, "cfg must not be null");
        Map<String, Object> embedCfg = CfgUtil.map(cfg.data.get("embed"));
        Map<String, Object> ollamaCfg = CfgUtil.map(cfg.data.get("ollama"));

        // Provider: embed.provider > "disabled"
        String provider = stringOr(embedCfg.get("provider"), "disabled");

        // Model: embed.model > provider-specific fallback
        String model = stringOr(embedCfg.get("model"), null);
        if (model == null) {
            model = "ollama".equals(provider)
                    ? stringOr(ollamaCfg.get("embed"), "bge-m3")
                    : "none";
        }

        // Find built-in defaults for this model (may be null for unknown models)
        EmbeddingProfile builtIn = findBuiltIn(model);

        // Use built-in values as defaults; config overrides win
        int defaultDims      = builtIn != null ? builtIn.dimensions()           : 0;
        String defaultQInstr = builtIn != null ? builtIn.queryInstruction()     : null;
        String defaultDInstr = builtIn != null ? builtIn.documentInstruction()  : null;
        int defaultMaxInput  = builtIn != null ? builtIn.maxInputTokens()       : 8192;
        boolean defaultNorm  = builtIn == null || builtIn.normalize();

        int dims         = CfgUtil.intAt(embedCfg, "dimensions", defaultDims);
        // Instruction prefixes may intentionally have trailing whitespace - do NOT trim.
        String qInstr    = rawStringOr(embedCfg.get("query_instruction"), defaultQInstr);
        String dInstr    = rawStringOr(embedCfg.get("document_instruction"), defaultDInstr);
        boolean instrAware = qInstr != null || dInstr != null;
        int maxInput     = CfgUtil.intAt(embedCfg, "max_input_tokens", defaultMaxInput);
        boolean normalize = CfgUtil.boolAt(embedCfg, "normalize", defaultNorm);

        EmbeddingProfile resolved = new EmbeddingProfile(
                provider, model, dims, instrAware,
                qInstr, dInstr, maxInput, normalize);

        // Return the singleton if the resolved profile matches a built-in exactly
        if (builtIn != null && builtIn.equals(resolved)) {
            return builtIn;
        }
        return resolved;
    }

    /**
     * Look up a built-in profile by model name. Returns {@code null} if
     * the model does not match any known built-in.
     */
    private static EmbeddingProfile findBuiltIn(String model) {
        if (EmbeddingProfile.BGE_M3.model().equals(model)) return EmbeddingProfile.BGE_M3;
        if (EmbeddingProfile.QWEN3_EMBED_8B.model().equals(model)) return EmbeddingProfile.QWEN3_EMBED_8B;
        return null;
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
        Embeddings raw = createRawClient(cfg, profile);
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
        Embeddings raw = createRawClient(cfg, profile);
        if (profile.instructionAware() && hasContent(profile.documentInstruction())) {
            return new InstructionEmbeddings(raw, profile.documentInstruction());
        }
        return raw;
    }
    // ── Internal ─────────────────────────────────────────────────────────
    /**
     * Construct the raw transport-level embeddings client.
     * <p>
     * Construct the configured transport. Provider mismatches fail clearly
     * instead of falling back to another backend silently.
     */
    private static Embeddings createRawClient(Config cfg, EmbeddingProfile profile) {
        String provider = profile.provider();
        if ("ollama".equals(provider)) {
            return new EmbeddingsClient(cfg);
        }
        if ("compat".equals(provider)
                || "openai_compat".equals(provider)
                || "llama_cpp".equals(provider)) {
            return new CompatEmbeddingsClient(cfg);
        }
        if ("disabled".equals(provider)) {
            return new DisabledEmbeddings(provider, profile.model());
        }
        throw new UnsupportedOperationException(
                "Embedding provider '" + provider + "' is not supported by this build. "
                + "Supported providers: compat, openai_compat, llama_cpp, ollama, disabled.");
    }
    private static String stringOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o).trim();
        return s.isEmpty() ? fallback : s;
    }
    /** Like {@link #stringOr} but preserves whitespace - required for instruction prefixes. */
    private static String rawStringOr(Object o, String fallback) {
        if (o == null) return fallback;
        String s = String.valueOf(o);
        return s.isEmpty() ? fallback : s;
    }

    private static boolean hasContent(String s) {
        return s != null && !s.isEmpty();
    }
}
