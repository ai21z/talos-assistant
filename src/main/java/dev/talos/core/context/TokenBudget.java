package dev.talos.core.context;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.util.Map;

/**
 * Encapsulates token estimation and budget allocation for context packing.
 * Uses a lightweight chars/4 heuristic — dependency-free, conservative, and
 * good enough until a model-specific tokenizer is warranted.
 *
 * <p>Budget layout for a typical call:
 * <pre>
 *   ┌──────────────────────────────────────────────┐
 *   │ contextMaxTokens                             │
 *   │  ┌─────────┬─────┬──────────┬────┬─────────┐ │
 *   │  │ system  │query│ snippets │ovhd│response │ │
 *   │  └─────────┴─────┴──────────┴────┴─────────┘ │
 *   └──────────────────────────────────────────────┘
 * </pre>
 */
public final class TokenBudget {

    /** Default context window size if none is configured. */
    public static final int DEFAULT_CONTEXT_MAX_TOKENS = 8192;

    /** Fraction of the context window reserved for model output. */
    public static final double DEFAULT_RESPONSE_RESERVE = 0.30;

    /** Fixed overhead for JSON structure, formatting, safety margin. */
    public static final int DEFAULT_OVERHEAD_TOKENS = 100;

    /** Per-snippet structural overhead (JSON keys, commas, braces). */
    public static final int PER_SNIPPET_OVERHEAD = 20;

    private final int contextMaxTokens;
    private final double responseReserveFraction;
    private final int overheadTokens;

    public TokenBudget(int contextMaxTokens, double responseReserveFraction, int overheadTokens) {
        this.contextMaxTokens = Math.max(256, contextMaxTokens);
        this.responseReserveFraction = Math.max(0.0, Math.min(0.9, responseReserveFraction));
        this.overheadTokens = Math.max(0, overheadTokens);
    }

    public TokenBudget(int contextMaxTokens) {
        this(contextMaxTokens, DEFAULT_RESPONSE_RESERVE, DEFAULT_OVERHEAD_TOKENS);
    }

    public TokenBudget() {
        this(DEFAULT_CONTEXT_MAX_TOKENS);
    }

    /**
     * Construct a TokenBudget from application config.
     * Reads {@code limits.llm_context_max_tokens}, falling back to {@link #DEFAULT_CONTEXT_MAX_TOKENS}.
     * This is the single source of truth for budget construction across all paths.
     */
    public static TokenBudget fromConfig(Config cfg) {
        Map<String, Object> limits = CfgUtil.map(cfg.data.get("limits"));
        int contextMax = CfgUtil.intAt(limits, "llm_context_max_tokens", DEFAULT_CONTEXT_MAX_TOKENS);
        return new TokenBudget(contextMax);
    }

    // ───── token estimation ─────

    /** Estimate token count using chars/4 heuristic. */
    public int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() / 4;
    }

    /** Estimate tokens for a single snippet (path + text + structural overhead). */
    public int estimateSnippetTokens(String path, String text) {
        return estimateTokens(path) + estimateTokens(text) + PER_SNIPPET_OVERHEAD;
    }

    // ───── budget calculation ─────

    /**
     * Compute how many tokens are available for snippet context,
     * given the system prompt and user query that must also fit.
     *
     * @return available tokens for snippets, or 0 if already over budget
     */
    public int availableForSnippets(String systemPrompt, String userQuery) {
        int systemTokens = estimateTokens(systemPrompt);
        int queryTokens = estimateTokens(userQuery);
        int responseReserve = (int) (contextMaxTokens * responseReserveFraction);
        int available = contextMaxTokens - systemTokens - queryTokens - responseReserve - overheadTokens;
        return Math.max(0, available);
    }

    /**
     * Convert a token budget to an approximate character budget.
     * Inverse of the chars/4 heuristic.
     */
    public int tokensToChars(int tokens) {
        return tokens * 4;
    }

    // ───── accessors ─────

    public int contextMaxTokens() { return contextMaxTokens; }
    public double responseReserveFraction() { return responseReserveFraction; }
    public int overheadTokens() { return overheadTokens; }

    @Override
    public String toString() {
        return "TokenBudget{max=" + contextMaxTokens
                + ", responseReserve=" + String.format("%.0f%%", responseReserveFraction * 100)
                + ", overhead=" + overheadTokens + '}';
    }
}

