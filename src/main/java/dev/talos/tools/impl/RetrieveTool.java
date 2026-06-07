package dev.talos.tools.impl;

import dev.talos.core.rag.RagService;
import dev.talos.core.index.SymbolHit;
import dev.talos.safety.ProtectedContentSanitizer;
import dev.talos.safety.ProtectedWorkspacePaths;
import dev.talos.tools.*;

import java.nio.file.Path;
import java.util.List;

/**
 * Tool that exposes the retrieval pipeline as a callable tool.
 *
 * <p>Wraps {@link RagService#prepare(Path, String, Integer)} so the LLM
 * (or an external MCP caller) can search the indexed knowledge base
 * using the same BM25 + KNN + RRF + rerank pipeline used by RagMode.
 *
 * <p>Parameters:
 * <ul>
 *   <li>{@code query} — the search query (required)</li>
 *   <li>{@code top_k} — number of results to return (optional, default from config)</li>
 * </ul>
 */
public final class RetrieveTool implements TalosTool {

    private static final String NAME = "talos.retrieve";

    private final RagService ragService;

    public RetrieveTool(RagService ragService) {
        this.ragService = ragService;
    }

    @Override public String name() { return NAME; }
    @Override public String description() { return "Search the indexed workspace using symbol signatures, BM25, and vector retrieval."; }

    @Override
    public ToolDescriptor descriptor() {
        return new ToolDescriptor(NAME, description(),
                """
                {"type":"object","properties":{
                  "query":{"type":"string","description":"Search query"},
                  "top_k":{"type":"integer","description":"Number of results (default from config)"}
                },"required":["query"]}""",
                ToolRiskLevel.READ_ONLY,
                ToolOperationMetadata.inspect(NAME, java.util.Map.of(), "WORKSPACE_RETRIEVED"));
    }

    @Override
    public ToolResult execute(ToolCall call, ToolContext ctx) {
        return doRetrieve(call, ctx != null ? ctx.workspace() : null);
    }

    private ToolResult doRetrieve(ToolCall call, Path workspace) {
        String query = call.param("query");
        if (query == null || query.isBlank()) {
            return ToolResult.fail(ToolError.invalidParams("Missing required parameter: query"));
        }

        Integer topK = null;
        String topKStr = call.param("top_k");
        if (topKStr != null && !topKStr.isBlank()) {
            try {
                topK = Integer.parseInt(topKStr.trim());
            } catch (NumberFormatException e) {
                // ignore, use default
            }
        }

        Path ws = workspace != null ? workspace : Path.of(".").toAbsolutePath().normalize();

        try {
            RagService.Prepared prepared = ragService.prepare(ws, query, topK);

            if (prepared.snippets().isEmpty() && prepared.symbolHits().isEmpty()) {
                return ToolResult.ok("No results found for: " + query);
            }

            var sb = new StringBuilder();
            appendSymbolHits(sb, prepared.symbolHits(), ws);
            sb.append("Found ").append(prepared.snippets().size()).append(" snippet result(s):\n\n");
            int protectedSnippets = 0;
            int redactedSnippets = 0;

            for (int i = 0; i < prepared.snippets().size(); i++) {
                var snippet = prepared.snippets().get(i);
                sb.append("--- [").append(i + 1).append("] ");

                // Use citation if available, otherwise just path
                List<String> citations = prepared.citations();
                if (citations != null && i < citations.size()) {
                    sb.append(citations.get(i));
                } else {
                    sb.append(snippet.path());
                }
                sb.append(" ---\n");
                Path snippetPath = ws.resolve(snippet.path()).normalize();
                if (ProtectedWorkspacePaths.isProtectedPath(ws, snippetPath)) {
                    protectedSnippets++;
                    sb.append("[protected content omitted from retrieval result]");
                } else {
                    String rawText = snippet.text() == null ? "" : snippet.text();
                    String safeText = ProtectedContentSanitizer.sanitizeText(rawText);
                    if (!safeText.equals(rawText)) redactedSnippets++;
                    sb.append(truncate(safeText, 1000));
                }
                sb.append("\n\n");
            }
            if (protectedSnippets > 0) {
                sb.append("Some retrieval snippets came from protected content and were omitted.\n");
            }
            if (redactedSnippets > 0) {
                sb.append("Some retrieval snippets contained protected markers or secret-like values and were redacted.\n");
            }

            return ToolResult.ok(sb.toString());
        } catch (Exception e) {
            return ToolResult.fail(ToolError.internal(
                    "Retrieval failed: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
        }
    }

    private static void appendSymbolHits(StringBuilder sb, List<SymbolHit> symbolHits, Path workspace) {
        if (symbolHits == null || symbolHits.isEmpty()) return;
        sb.append("Symbol signature matches (not full file contents):\n");
        for (SymbolHit hit : symbolHits) {
            Path hitPath = workspace.resolve(hit.path()).normalize();
            if (ProtectedWorkspacePaths.isProtectedPath(workspace, hitPath)) {
                sb.append(" - [protected symbol omitted]\n");
                continue;
            }
            sb.append(" - ")
                    .append(hit.kind().name())
                    .append(" ")
                    .append(hit.symbol())
                    .append(" @ ")
                    .append(hit.path());
            if (hit.lineStart() > 0) {
                sb.append(":").append(hit.lineStart());
            }
            if (!hit.signature().isBlank()) {
                String safeSignature = ProtectedContentSanitizer.sanitizeText(hit.signature());
                sb.append(" - ").append(truncate(safeSignature, 180).replace('\n', ' '));
            }
            sb.append("\n");
        }
        sb.append("\n");
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "\n... (truncated)";
    }
}

