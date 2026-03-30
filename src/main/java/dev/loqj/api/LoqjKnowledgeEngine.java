package dev.loqj.api;

import dev.loqj.core.Config;
import dev.loqj.core.rag.RagService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Programmatic entry point for LOQ-J as a knowledge engine.
 * Provides a clean consumer-facing API for retrieval and question answering
 * without requiring CLI or REPL infrastructure.
 * <p>
 * This is the seam through which future consumers (Loqs Core, MCP server,
 * library users) should interact with LOQ-J's capabilities.
 */
public final class LoqjKnowledgeEngine {

    private final Config cfg;
    private final RagService ragService;

    public LoqjKnowledgeEngine(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg, "cfg must not be null");
        this.ragService = new RagService(cfg);
    }

    /**
     * Retrieve context snippets for a query without generating an answer.
     * Useful for consumers that want to assemble their own prompts.
     */
    public QueryResponse retrieve(QueryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        RagService.Prepared prepared = ragService.prepare(
                request.workspace(), request.query(), request.topK());
        return new QueryResponse(null, prepared.snippetMaps(), prepared.citations());
    }

    /**
     * Retrieve context and generate an answer using the configured LLM.
     * Retrieval is performed once; snippets are obtained from the same pass.
     */
    public QueryResponse ask(QueryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        RagService.Answer answer = ragService.ask(
                request.workspace(), request.query(), request.topK());
        // Answer now carries Prepared from the single retrieval pass
        var snippets = answer.prepared() != null
                ? answer.prepared().snippetMaps()
                : List.<java.util.Map<String, String>>of();
        return new QueryResponse(answer.text(), snippets, answer.citations());
    }

    /**
     * Trigger (re-)indexing of the given workspace directory.
     */
    public void index(Path workspace) throws Exception {
        ragService.getIndexer().index(workspace, false);
    }

    /**
     * Force a full reindex of the given workspace directory.
     */
    public void reindex(Path workspace) throws Exception {
        ragService.reindex(workspace);
    }

    /** Access the underlying RagService (escape hatch for advanced/internal use). */
    public RagService ragService() {
        return ragService;
    }

    // --- Request / Response value types ---

    /**
     * Immutable query request to the knowledge engine.
     */
    public static final class QueryRequest {
        private final Path workspace;
        private final String query;
        private final Integer topK;

        public QueryRequest(Path workspace, String query, Integer topK) {
            this.workspace = Objects.requireNonNull(workspace, "workspace must not be null");
            this.query = Objects.requireNonNull(query, "query must not be null");
            this.topK = topK;
        }

        public QueryRequest(Path workspace, String query) {
            this(workspace, query, null);
        }

        public Path workspace()  { return workspace; }
        public String query()    { return query; }
        public Integer topK()    { return topK; }
    }

    /**
     * Immutable response from the knowledge engine.
     */
    public static final class QueryResponse {
        private final String answer;
        private final List<java.util.Map<String, String>> snippets;
        private final List<String> citations;

        public QueryResponse(String answer,
                             List<java.util.Map<String, String>> snippets,
                             List<String> citations) {
            this.answer = answer;
            this.snippets = snippets == null ? List.of() : List.copyOf(snippets);
            this.citations = citations == null ? List.of() : List.copyOf(citations);
        }

        /** The generated answer text, or null if only retrieval was performed. */
        public String answer()                              { return answer; }
        /** Retrieved context snippets (each has "path" and "text" keys). */
        public List<java.util.Map<String, String>> snippets() { return snippets; }
        /** Deduplicated source file citations. */
        public List<String> citations()                     { return citations; }
        /** Whether an answer was generated (vs retrieval-only). */
        public boolean hasAnswer()                          { return answer != null && !answer.isBlank(); }
    }
}

