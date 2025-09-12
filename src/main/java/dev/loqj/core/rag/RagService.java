package dev.loqj.core.rag;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.index.Indexer;
import dev.loqj.core.llm.LlmClient;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;

/**
 * RAG service facade used by CLI.
 * This version provides the methods your CLI references and avoids
 * depending on SnippetBuilder constructors that aren't present in your codebase.
 */
public class RagService {

    private final Config cfg;
    private final Indexer indexer;

    // very small session-memory field used by RAG+MEMORY mode (optional)
    private String sessionMemory;

    /** Small data holder returned by prepare(). */
    public static final class Prepared {
        private final List<Map<String, String>> snippetMaps;
        private final List<String> citations;

        public Prepared(List<Map<String, String>> snippetMaps, List<String> citations) {
            this.snippetMaps = (snippetMaps == null ? List.of() : List.copyOf(snippetMaps));
            this.citations   = (citations == null ? List.of()     : List.copyOf(citations));
        }
        public List<Map<String, String>> snippetMaps() { return snippetMaps; }
        public List<String> citations()                 { return citations;  }
    }

    /** Answer type expected by RagAskCmd (has text() and citations()). */
    public record Answer(String text, List<String> citations) {}

    public RagService(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg);
        this.indexer = new Indexer(cfg);
    }

    /** Expose indexer to callers that need it. */
    public Indexer getIndexer() {
        return indexer;
    }

    /** Rebuild the index; the actual Indexer implementation handles details. */
    public Object reindex(Path root) throws Exception {
        return indexer.reindex(root);
    }

    /**
     * Prepare snippets/citations for a query.
     * NOTE: This implementation returns empty lists to avoid coupling to a specific SnippetBuilder API.
     * Wire your actual retriever here later (e.g., Bm25KnnRetriever) and fill snippet maps + citations.
     */
    public Prepared prepare(Path ws, String query, Integer topKOverride) {
        // Try to read top_k from config; default to 6
        int defaultTopK = 6;
        try {
            Map<String, Object> rag = CfgUtil.map(cfg.data.get("rag"));
            Object v = (rag == null ? null : rag.get("top_k"));
            if (v instanceof Number) defaultTopK = ((Number) v).intValue();
            else if (v != null) defaultTopK = Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {}

        int k = (topKOverride == null ? defaultTopK : Math.max(1, topKOverride));

        // TODO (Phase 1/2): fetch top-K snippet maps + citations via your retriever.
        // For now, return empty collections to keep CLI stable.
        List<Map<String,String>> snippets = List.of();
        List<String> citations = List.of();

        return new Prepared(snippets, citations);
    }

    /** Load the CLI system prompt or a safe default. */
    public String readCliSystemPromptOrDefault() throws Exception {
        try (InputStream in = RagService.class.getClassLoader().getResourceAsStream("prompts/cli-system.txt")) {
            if (in != null) return new String(in.readAllBytes());
        }
        // Fallback text to keep functionality even if the resource is missing
        return "You are LOQ-J (CLI). Answer briefly, cite local files when available. If context is insufficient, say so.";
    }

    /**
     * Convenience call used by RagAskCmd: builds context and asks the LLM.
     * Returns an Answer with text and citations.
     */
    public Answer ask(Path ws, String question, Integer kOverride) {
        try {
            Prepared prepared = prepare(ws, question, kOverride);
            LlmClient llm = new LlmClient(cfg);
            String sys = readCliSystemPromptOrDefault();
            String text = llm.chat(sys, question, prepared.snippetMaps());
            if (text == null) text = "";
            return new Answer(text, prepared.citations());
        } catch (Exception e) {
            String msg = "Error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
            return new Answer(msg, List.of());
        }
    }

    /* ====== Minimal session memory for RAG+MEMORY mode ====== */
    public String getMemory() { return sessionMemory; }
    public void clearMemory() { sessionMemory = null; }
    public void updateMemory(String userInput, String answer, int maxItems, int maxNames) {
        String s = (sessionMemory == null ? "" : sessionMemory + "\n") + userInput + "\n" + answer;
        sessionMemory = (s.length() > 4000 ? s.substring(s.length() - 4000) : s);
    }
}
