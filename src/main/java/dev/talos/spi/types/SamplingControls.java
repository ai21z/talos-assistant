package dev.talos.spi.types;

/**
 * Provider-neutral sampling controls for a chat request.
 *
 * <p>Null fields mean "unset" — the backend's own defaults apply. Set fields
 * are emitted on the wire (llama.cpp accepts temperature/top_p/top_k/seed on
 * /v1/chat/completions). Tool-obligation turns use {@link #NEAR_GREEDY}
 * because quantized 14B-class models emit tool-call JSON far more reliably
 * near-greedy than at server-default temperature (T740: byte-identical
 * requests produced both valid calls and protocol debris under default
 * sampling with a random seed).
 */
public record SamplingControls(Double temperature, Double topP, Integer topK, Long seed) {

    private static final SamplingControls NONE = new SamplingControls(null, null, null, null);

    /** Near-greedy defaults for tool-obligation turns (qwen guidance: top_p 0.8, top_k 20). */
    public static final SamplingControls NEAR_GREEDY = new SamplingControls(0.2, 0.8, 20, null);

    public static SamplingControls none() {
        return NONE;
    }

    public boolean anySet() {
        return temperature != null || topP != null || topK != null || seed != null;
    }

    /**
     * Returns these controls with unset fields filled from {@code fallback}.
     * Set fields always win; used to layer config-level overrides (e.g. a
     * fixed harness seed) under turn-level decisions.
     */
    public SamplingControls mergedWithFallback(SamplingControls fallback) {
        if (fallback == null || !fallback.anySet()) return this;
        return new SamplingControls(
                temperature != null ? temperature : fallback.temperature(),
                topP != null ? topP : fallback.topP(),
                topK != null ? topK : fallback.topK(),
                seed != null ? seed : fallback.seed());
    }
}
