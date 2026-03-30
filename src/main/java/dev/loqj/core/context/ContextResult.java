package dev.loqj.core.context;

import java.util.*;

/**
 * Immutable result of context packing.
 * Carries the packed snippet list ready for LLM consumption,
 * plus provenance metadata (budget utilization, trimming info, citations).
 */
public final class ContextResult {

    /** A single packed snippet — path and sanitized text. */
    public record Snippet(String path, String text) {
        public Snippet {
            path = Objects.requireNonNullElse(path, "");
            text = Objects.requireNonNullElse(text, "");
        }
    }

    private final List<Snippet> snippets;
    private final List<String> citations;
    private final int originalCount;
    private final int finalCount;
    private final boolean wasTrimmed;
    private final int estimatedTokens;
    private final int budgetTokens;

    public ContextResult(List<Snippet> snippets, List<String> citations,
                         int originalCount, int finalCount, boolean wasTrimmed,
                         int estimatedTokens, int budgetTokens) {
        this.snippets = snippets == null ? List.of() : List.copyOf(snippets);
        this.citations = citations == null ? List.of() : List.copyOf(citations);
        this.originalCount = originalCount;
        this.finalCount = finalCount;
        this.wasTrimmed = wasTrimmed;
        this.estimatedTokens = estimatedTokens;
        this.budgetTokens = budgetTokens;
    }

    // ───── accessors ─────

    /** Packed snippets in priority order (pinned first, then regular). */
    public List<Snippet> snippets() { return snippets; }

    /** Deduplicated citation paths (base file paths, no chunk IDs). */
    public List<String> citations() { return citations; }

    /** Number of candidate snippets before budget trimming. */
    public int originalCount() { return originalCount; }

    /** Number of snippets after budget trimming. */
    public int finalCount() { return finalCount; }

    /** Whether packing had to reduce context: snippets dropped or text truncated. */
    public boolean wasTrimmed() { return wasTrimmed; }

    /** Estimated total tokens (system + query + snippets). */
    public int estimatedTokens() { return estimatedTokens; }

    /** Total token budget (context window size). */
    public int budgetTokens() { return budgetTokens; }

    /** Budget utilization as a fraction (0.0–1.0+). */
    public double utilization() {
        return budgetTokens > 0 ? (double) estimatedTokens / budgetTokens : 0.0;
    }

    /** True if no snippets survived packing. */
    public boolean isEmpty() { return snippets.isEmpty(); }

    /** Convert snippets to the Map<String,String> format expected by LlmClient. */
    public List<Map<String, String>> toSnippetMaps() {
        List<Map<String, String>> out = new ArrayList<>(snippets.size());
        for (Snippet s : snippets) {
            out.add(Map.of("path", s.path(), "text", s.text()));
        }
        return Collections.unmodifiableList(out);
    }

    @Override
    public String toString() {
        return "ContextResult{snippets=" + finalCount + "/" + originalCount
                + ", tokens≈" + estimatedTokens + "/" + budgetTokens
                + ", trimmed=" + wasTrimmed + '}';
    }
}

