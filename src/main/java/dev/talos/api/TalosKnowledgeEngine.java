package dev.talos.api;

import dev.talos.core.Config;
import dev.talos.core.rag.RagService;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Programmatic entry point for Talos as a knowledge engine.
 * Provides a clean consumer-facing API for retrieval and question answering
 * without requiring CLI or REPL infrastructure.
 * <p>
 * This is the seam through which future consumers (Talos Core, MCP server,
 * library users) should interact with Talos' capabilities.
 */
public final class TalosKnowledgeEngine {

    private final Config cfg;
    private final RagService ragService;

    public TalosKnowledgeEngine(Config cfg) {
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
        return QueryResponse.fromSnippets(null, prepared.snippets(), prepared.citations());
    }

    /**
     * Retrieve context and generate an answer using the configured LLM.
     * Retrieval is performed once; the returned snippets and citations
     * correspond to the <em>packed</em> context actually sent to the model,
     * not the broader pre-packed retrieval set.
     * <p>
     * <strong>Net-disabled fallback:</strong> When {@code net.enabled} is false,
     * {@link RagService#ask} returns {@code packedContext == null} because context
     * packing is skipped (no model will consume the packed prompt). In that case
     * this method falls back to the pre-packed retrieval snippets from
     * {@link RagService.Prepared} so callers still receive the retrieved evidence.
     */
    public QueryResponse ask(QueryRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        RagService.Answer answer = ragService.ask(
                request.workspace(), request.query(), request.topK());
        // Prefer packed context (actual input to model) over raw retrieved set.
        // packedContext is null on the net-disabled stub path — fall back to Prepared.
        var snippets = answer.packedContext() != null
                ? answer.packedContext().snippets()
                : (answer.prepared() != null ? answer.prepared().snippets()
                        : List.<dev.talos.core.context.ContextResult.Snippet>of());
        return QueryResponse.fromSnippets(answer.text(), snippets, answer.citations());
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
     * Carries typed snippets with structured metadata for richer provenance.
     * <p>
     * <strong>API compatibility note (v0.9.0):</strong>
     * {@link #snippets()} now returns {@code List<ContextResult.Snippet>} instead
     * of the previous {@code List<Map<String, String>>}. This is a source-level
     * breaking change for any external consumer that compiled against the old
     * signature. The legacy {@link #snippetMaps()} accessor is retained as a
     * compatibility bridge and produces the same {@code Map<"path","text">} view
     * that the old {@code snippets()} returned. Repo-internal callers have been
     * migrated; external consumers should migrate to typed snippets or use
     * {@code snippetMaps()} as a short-term bridge.
     */
    public static final class QueryResponse {
        private final String answer;
        private final List<dev.talos.core.context.ContextResult.Snippet> snippets;
        private final List<String> citations;

        /** Primary constructor from typed snippets. */
        public QueryResponse(String answer,
                             List<dev.talos.core.context.ContextResult.Snippet> snippets,
                             List<String> citations) {
            this.answer = answer;
            this.snippets = snippets == null ? List.of() : List.copyOf(snippets);
            this.citations = citations == null ? List.of() : List.copyOf(citations);
        }

        /** Factory from typed snippets (convenience name). */
        static QueryResponse fromSnippets(String answer,
                                          List<dev.talos.core.context.ContextResult.Snippet> snippets,
                                          List<String> citations) {
            return new QueryResponse(answer, snippets, citations);
        }

        /** The generated answer text, or null if only retrieval was performed. */
        public String answer()                              { return answer; }
        /** Typed snippets with metadata. */
        public List<dev.talos.core.context.ContextResult.Snippet> snippets() { return snippets; }
        /** Legacy accessor: converts typed snippets to Map&lt;String,String&gt; for compatibility. */
        public List<java.util.Map<String, String>> snippetMaps() {
            List<java.util.Map<String, String>> out = new java.util.ArrayList<>(snippets.size());
            for (var s : snippets) {
                out.add(java.util.Map.of("path", s.path(), "text", s.text()));
            }
            return java.util.Collections.unmodifiableList(out);
        }
        /** Deduplicated source file citations (rich format when metadata is available). */
        public List<String> citations()                     { return citations; }
        /** Whether an answer was generated (vs retrieval-only). */
        public boolean hasAnswer()                          { return answer != null && !answer.isBlank(); }
    }
}

