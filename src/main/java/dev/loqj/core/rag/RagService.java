package dev.loqj.core.rag;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;
import dev.loqj.core.embed.CachingEmbeddings;
import dev.loqj.core.embed.EmbeddingsClient;
import dev.loqj.core.index.Indexer;
import dev.loqj.core.index.LuceneStore;
import dev.loqj.core.llm.LlmClient;
import dev.loqj.core.cache.CacheDb;
import dev.loqj.core.context.ContextPacker;
import dev.loqj.core.context.ContextResult;
import dev.loqj.core.context.TokenBudget;
import dev.loqj.core.rerank.NoOpReranker;
import dev.loqj.core.retrieval.*;
import dev.loqj.core.retrieval.stages.*;
import dev.loqj.core.spi.CorpusStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class RagService {
    private static final Logger LOG = LoggerFactory.getLogger(RagService.class);

    private final Config cfg;
    private final Indexer indexer;

    // Guard against re-entrant lazy indexing
    private final AtomicBoolean indexingNow = new AtomicBoolean(false);


    /** Small data holder returned by prepare(). */
    public static final class Prepared {
        private final List<ContextResult.Snippet> snippets;
        private final List<String> citations;
        private final RetrievalTrace trace; // nullable — absent on error path

        public Prepared(List<ContextResult.Snippet> snippets, List<String> citations) {
            this(snippets, citations, null);
        }

        public Prepared(List<ContextResult.Snippet> snippets, List<String> citations, RetrievalTrace trace) {
            this.snippets  = (snippets == null ? List.of() : List.copyOf(snippets));
            this.citations = (citations == null ? List.of() : List.copyOf(citations));
            this.trace     = trace;
        }
        /** Typed snippets with structured metadata. */
        public List<ContextResult.Snippet> snippets() { return snippets; }
        /** Legacy accessor: converts typed snippets to Map&lt;"path","text"&gt; for compatibility. */
        public List<Map<String, String>> snippetMaps() {
            List<Map<String, String>> out = new ArrayList<>(snippets.size());
            for (var s : snippets) {
                out.add(Map.of("path", s.path(), "text", s.text()));
            }
            return Collections.unmodifiableList(out);
        }
        public List<String> citations() { return citations; }
        /** Pipeline trace, or null if retrieval failed before pipeline execution. */
        public RetrievalTrace trace() { return trace; }
    }

    /**
     * Answer returned by {@link #ask(Path, String, Integer)}.
     * <p>
     * {@code packedContext} is the context actually sent to the LLM after packing
     * and possible truncation. It is {@code null} on the net-disabled stub path
     * (no model call occurs, so no packing is performed). Callers that inspect
     * packed context must null-check first.
     *
     * @param text           generated answer text (or stub / error message)
     * @param citations      deduplicated source-file citations
     * @param prepared       full pre-packed retrieval result (nullable on error path)
     * @param packedContext   packed context sent to model (null when net is disabled or on error)
     */
    public record Answer(String text, List<String> citations, Prepared prepared, ContextResult packedContext) {
        /** Backwards-compatible constructor for callers that do not supply Prepared or packed context. */
        public Answer(String text, List<String> citations) {
            this(text, citations, null, null);
        }
    }

    public RagService(Config cfg) {
        this.cfg = Objects.requireNonNull(cfg);
        this.indexer = new Indexer(cfg);
    }

    public Indexer getIndexer() { return indexer; }

    public Object reindex(Path root) throws Exception { return indexer.reindex(root); }

    public Prepared prepare(Path ws, String query, Integer topKOverride) {
        // Ensure index exists before retrieval (lazy indexing on first query)
        ensureIndexExists(ws);

        int defaultTopK = 6;
        try {
            Map<String, Object> ragCfg = CfgUtil.map(cfg.data.get("rag"));
            Object v = (ragCfg == null ? null : ragCfg.get("top_k"));
            if (v instanceof Number n) defaultTopK = n.intValue();
            else if (v != null) defaultTopK = Integer.parseInt(String.valueOf(v));
        } catch (Exception ignore) {}

        final int k = (topKOverride == null ? defaultTopK : Math.max(1, topKOverride));

        // Read vector toggle; if off, KnnStage will gracefully skip (no query vector)
        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        boolean vecEnabled = true;
        Object vectorsObj = rag.get("vectors");
        if (vectorsObj instanceof Map<?,?> vm) {
            Object en = ((Map<?,?>) vm).get("enabled");
            if (en instanceof Boolean b) vecEnabled = b;
        }

        Path indexDir = indexer.indexDirFor(ws);
        List<ContextResult.Snippet> snippets = new ArrayList<>();
        List<String> citations = new ArrayList<>();
        RetrievalTrace trace = null;

        try (LuceneStore store = new LuceneStore(indexDir, 0)) {
            // Compute query vector when vectors are enabled
            float[] qvec = null;
            String embedFailReason = null;
            if (vecEnabled) {
                try (CacheDb cache = new CacheDb();
                     CachingEmbeddings emb = new CachingEmbeddings(new EmbeddingsClient(cfg), cache, "query/ollama")) {
                    qvec = emb.embed(query);
                } catch (Exception e) {
                    // If embeddings fail, proceed BM25-only but record why
                    embedFailReason = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                    LOG.warn("Embedding failed, proceeding BM25-only: {}", embedFailReason);
                }
            }

            // Build and execute the retrieval pipeline
            RetrievalPipeline pipeline = buildDefaultPipeline(store);
            RetrievalRequest request = new RetrievalRequest(query, qvec, k, embedFailReason);
            RetrievalResult result = pipeline.execute(request);

            trace = result.trace();
            LOG.debug("Retrieval pipeline trace:\n{}", trace.summary());

            // Build typed snippets from pipeline results
            for (RetrievalCandidate c : result.candidates()) {
                String text = store.getTextByPath(c.path());
                if (text == null || text.isBlank()) continue;
                snippets.add(new ContextResult.Snippet(c.path(), text, c.metadata()));
            }
            // Build rich citations using the same metadata-aware formatting as ContextPacker
            citations.addAll(ContextPacker.buildCitations(snippets));
        } catch (Exception e) {
            // On any failure, return empty (don't explode CLI)
        }

        return new Prepared(snippets, citations, trace);
    }

    /**
     * Builds the default retrieval pipeline:
     * BM25 → KNN → RRF Fusion → Source Boost → Rerank → Dedup.
     *
     * <p>Source boost applies path-based scoring adjustments after fusion to
     * bias results toward production code when the query is implementation-oriented.
     * The reranker stage uses NoOpReranker by default; swap in a real reranker later.
     * Package-private for testability.
     */
    RetrievalPipeline buildDefaultPipeline(CorpusStore store) {
        return RetrievalPipeline.builder()
                .addStage(new Bm25Stage(store))
                .addStage(new KnnStage(store))
                .addStage(new RrfFusionStage(60))
                .addStage(new SourceBoostStage())
                .addStage(new RerankerStage(new NoOpReranker()))
                .addStage(new DedupStage())
                .build();
    }


    public String readCliSystemPromptOrDefault() throws Exception {
        try (InputStream in = RagService.class.getClassLoader().getResourceAsStream("prompts/cli-system.txt")) {
            if (in != null) return new String(in.readAllBytes());
        }
        return "You are Loqs (CLI). Answer briefly, cite local files when available. If context is insufficient, say so.";
    }

    /**
     * Retrieves context for the given question and generates an LLM answer.
     * <p>
     * <strong>Net-disabled stub path:</strong> When {@code net.enabled} is {@code false}
     * in configuration, the LLM call is skipped entirely. The method returns an
     * {@link Answer} whose text is a synthetic stub ({@code "(net disabled) <question>"}),
     * whose citations come from the pre-packed retrieval set (i.e. {@link Prepared#citations()}),
     * and whose {@link Answer#packedContext()} is {@code null} because context packing
     * never runs (no model will consume it). Callers must therefore treat a null
     * {@code packedContext} as "no packing was performed" — not as "packing produced
     * nothing." The {@link Answer#prepared()} field is still populated, so the full
     * retrieved snippet set is available for inspection.
     * <p>
     * This path exists to allow fast integration tests and air-gapped environments
     * to exercise the retrieval pipeline without requiring a reachable LLM endpoint.
     *
     * @param ws          workspace root directory
     * @param question    user query
     * @param kOverride   optional override for top-K retrieval (null → config default)
     * @return a non-null {@link Answer}; on unrecoverable error the answer text
     *         contains the error message and citations are empty
     */
    public Answer ask(Path ws, String question, Integer kOverride) {
        try {
            Prepared prepared = prepare(ws, question, kOverride);

            // Net-disabled stub path: skip LLM + context packing for fast tests / air-gap.
            // packedContext is null because no packing is performed — no model will consume it.
            // Citations come from the pre-packed retrieval set (Prepared).
            // See Javadoc above for full semantics.
            Map<String,Object> net = CfgUtil.map(cfg.data.get("net"));
            boolean netEnabled = !(net.get("enabled") instanceof Boolean b) || b;

            if (!netEnabled) {
                String stub = "(net disabled) " + question;
                return new Answer(stub, prepared.citations(), prepared, null);
            }

            String sys = readCliSystemPromptOrDefault();

            // Pack retrieved snippets into context using unified ContextPacker
            ContextPacker packer = new ContextPacker(TokenBudget.fromConfig(cfg));
            ContextResult packed = packer.pack(sys, question, List.of(), prepared.snippets());

            // Warn if trimming occurred
            if (packed.wasTrimmed()) {
                LOG.warn("RAG_CONTEXT_TRIMMED: Reduced snippets from {} to {} to fit {} token budget (estimated {} tokens). Consider reducing :k or enabling vectors.",
                    packed.originalCount(), packed.finalCount(), packed.budgetTokens(), packed.estimatedTokens());
            }

            LlmClient llm = new LlmClient(cfg);
            String text = llm.chat(sys, question, packed.toSnippetMaps());
            if (text == null) text = "";

            // Warn if we have retrieval but answer is empty
            if (!packed.isEmpty() && text.trim().isEmpty()) {
                LOG.warn("RAG_GEN_EMPTY: Retrieved {} snippets but answer body is empty (promptTokens={}, budget={}). Check model capacity or reduce :k.",
                    packed.finalCount(), packed.estimatedTokens(), packed.budgetTokens());
            }

            // Return packed citations (what the model actually saw), not pre-packed
            return new Answer(text, packed.citations(), prepared, packed);
        } catch (Exception e) {
            String msg = "Error: " + e.getClass().getSimpleName() + (e.getMessage() == null ? "" : (": " + e.getMessage()));
            return new Answer(msg, List.of());
        }
    }


    /**
     * Ensures index exists for the given workspace. If missing or unreadable, performs lazy indexing.
     * Guard with AtomicBoolean to prevent re-entrancy. Falls back to full rebuild on corruption.
     */
    private void ensureIndexExists(Path workspace) {
        Path indexDir = indexer.indexDirFor(workspace);

        // Check if index exists and is readable
        if (Files.exists(indexDir) && Files.isDirectory(indexDir)) {
            // Try to verify it's a valid Lucene index by attempting to open it
            try (LuceneStore store = new LuceneStore(indexDir, 0)) {
                // If we can open it, assume it's valid
                return;
            } catch (Exception e) {
                // Index exists but is corrupted - log and proceed to rebuild
                LOG.warn("Index directory exists but appears corrupted, will rebuild: {}", e.getMessage());
            }
        }

        // Index missing or corrupted - attempt lazy indexing
        if (!indexingNow.compareAndSet(false, true)) {
            // Already indexing in another thread/call, skip
            return;
        }

        try {
            System.out.print("\rIndexing workspace (first RAG query)... ");
            System.out.flush();

            // Perform indexing with current config (respects vectors setting)
            indexer.index(workspace, false);

            // Print final summary (Indexer already prints this, but ensure newline)
            System.out.println();

        } catch (Exception e) {
            LOG.error("Lazy indexing failed: {}", e.getMessage(), e);
            System.err.println("\rIndexing failed: " + e.getMessage());
        } finally {
            indexingNow.set(false);
        }
    }
}
