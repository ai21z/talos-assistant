package dev.loqj.core.rag;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates and trims RAG prompts to fit within model context window budget.
 * Uses lightweight token estimation (chars/4 heuristic) to avoid external dependencies.
 */
public final class PromptValidator {

    private final int contextMaxTokens;

    public static class ValidationResult {
        public final List<Map<String, String>> snippets;
        public final boolean wasTrimmed;
        public final int originalCount;
        public final int finalCount;
        public final int estimatedTokens;
        public final int budgetTokens;

        public ValidationResult(List<Map<String, String>> snippets, boolean wasTrimmed,
                              int originalCount, int finalCount, int estimatedTokens, int budgetTokens) {
            this.snippets = snippets;
            this.wasTrimmed = wasTrimmed;
            this.originalCount = originalCount;
            this.finalCount = finalCount;
            this.estimatedTokens = estimatedTokens;
            this.budgetTokens = budgetTokens;
        }
    }

    public PromptValidator(Config cfg) {
        // Get context max tokens from config limits
        Map<String, Object> limits = CfgUtil.map(cfg.data.get("limits"));
        this.contextMaxTokens = CfgUtil.intAt(limits, "llm_context_max_tokens", 8192);
    }

    public PromptValidator(int contextMaxTokens) {
        this.contextMaxTokens = contextMaxTokens;
    }

    /**
     * Validate and trim snippets to fit within token budget.
     * Reserve space for system prompt, user query, and response generation.
     *
     * @param systemPrompt System prompt text
     * @param userQuery User question
     * @param snippets Retrieved snippets (ordered by relevance)
     * @return ValidationResult with potentially trimmed snippets
     */
    public ValidationResult validateAndTrim(String systemPrompt, String userQuery,
                                           List<Map<String, String>> snippets) {
        if (snippets == null || snippets.isEmpty()) {
            return new ValidationResult(List.of(), false, 0, 0, 0, contextMaxTokens);
        }

        int originalCount = snippets.size();

        // Reserve tokens: 25% for system, 10% for query, 30% for response, 35% for context
        int systemTokens = estimateTokens(systemPrompt);
        int queryTokens = estimateTokens(userQuery);
        int responseReserve = (int) (contextMaxTokens * 0.30); // Reserve 30% for model output
        int overhead = 100; // JSON structure, formatting, safety margin

        int availableForSnippets = contextMaxTokens - systemTokens - queryTokens - responseReserve - overhead;

        if (availableForSnippets < 0) {
            // System + query already exceed budget (shouldn't happen with reasonable inputs)
            return new ValidationResult(List.of(), true, originalCount, 0,
                                      systemTokens + queryTokens, contextMaxTokens);
        }

        // Trim snippets from lowest-ranked (end of list) until we fit
        List<Map<String, String>> trimmed = new ArrayList<>(snippets);
        int snippetTokens = estimateSnippetTokens(trimmed);

        while (snippetTokens > availableForSnippets && !trimmed.isEmpty()) {
            // Remove lowest-ranked snippet (last in list)
            trimmed.remove(trimmed.size() - 1);
            snippetTokens = estimateSnippetTokens(trimmed);
        }

        boolean wasTrimmed = trimmed.size() < originalCount;
        int totalEstimated = systemTokens + queryTokens + snippetTokens;

        return new ValidationResult(trimmed, wasTrimmed, originalCount, trimmed.size(),
                                   totalEstimated, contextMaxTokens);
    }

    /**
     * Estimate token count using simple chars/4 heuristic.
     * This is conservative and dependency-free (no external tokenizers).
     */
    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) return 0;
        return text.length() / 4;
    }

    private int estimateSnippetTokens(List<Map<String, String>> snippets) {
        int total = 0;
        for (Map<String, String> snippet : snippets) {
            String path = snippet.getOrDefault("path", "");
            String text = snippet.getOrDefault("text", "");
            // Include path and text in estimation
            total += estimateTokens(path);
            total += estimateTokens(text);
            total += 20; // JSON structure overhead per snippet
        }
        return total;
    }

    public int getContextMaxTokens() {
        return contextMaxTokens;
    }
}

