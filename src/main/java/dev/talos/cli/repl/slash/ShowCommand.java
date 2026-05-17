package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.extract.DocumentExtractionRequest;
import dev.talos.core.extract.DocumentExtractionResult;
import dev.talos.core.extract.DocumentExtractionService;
import dev.talos.core.extract.DocumentExtractionStatus;
import dev.talos.core.index.LuceneStore;
import dev.talos.core.ingest.FileCapabilityPolicy;
import dev.talos.runtime.policy.PrivateDocumentPolicy;
import dev.talos.runtime.policy.ProtectedReadScopePolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ShowCommand implements Command {
    private final Path workspace;

    public ShowCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("show",
                List.of(),
                "/show <rel>#<chunk>",
                "Display a snippet.",
                CommandGroup.KNOWLEDGE);
    }

    @Override public Result execute(String args, Context ctx) {
        if (args == null || args.trim().isEmpty()) {
            return new Result.Error("Usage: /show <rel>#<chunk>  (e.g., /show src/main/Main.java#0)", 400);
        }

        String input = args.trim();

        // Parse input format: path#chunk
        String filePath;
        int chunkId = 0;

        if (input.contains("#")) {
            String[] parts = input.split("#", 2);
            filePath = parts[0];
            try {
                chunkId = Integer.parseInt(parts[1]);
            } catch (NumberFormatException e) {
                return new Result.Error("Invalid chunk ID: " + parts[1] + " (must be integer)", 400);
            }
        } else {
            filePath = input;
        }

        try {
            // Try to find the snippet via Lucene store
            boolean canUseIndex = !ProtectedReadScopePolicy.privateMode(ctx.cfg())
                    || ProtectedReadScopePolicy.ragEnabledInPrivateMode(ctx.cfg());
            if (canUseIndex) {
                Path indexDir = ctx.rag().getIndexer().indexDirFor(workspace);
                try (var store = new LuceneStore(indexDir, 0)) {
                    String snippetId = filePath + "#" + chunkId;
                    String text = store.getTextByPath(snippetId);

                    if (text != null && !text.trim().isEmpty()) {
                        var sb = new StringBuilder();
                        sb.append("Snippet: ").append(snippetId).append("\n");
                        sb.append("─".repeat(60)).append("\n");
                        sb.append(text);
                        if (!text.endsWith("\n")) sb.append("\n");
                        sb.append("─".repeat(60));
                        return new Result.Ok(sb.toString());
                    }
                }
            }

            // Fallback: try to read the file directly
            Path workspaceRoot = workspace.toAbsolutePath().normalize();
            Path fullPath = workspaceRoot.resolve(filePath).toAbsolutePath().normalize();
            if (!fullPath.startsWith(workspaceRoot)) {
                return new Result.Error("Path is outside the workspace: " + filePath, 403);
            }
            if (Files.exists(fullPath) && Files.isReadable(fullPath)) {
                var format = FileCapabilityPolicy.describe(fullPath, ctx.cfg()).orElse(null);
                if (format != null && format.extractable() && format.enabled()) {
                    DocumentExtractionRequest request = DocumentExtractionRequest.read(fullPath, workspaceRoot);
                    DocumentExtractionResult extraction = new DocumentExtractionService(ctx.cfg()).extract(request);
                    if (extraction.status() == DocumentExtractionStatus.SUCCESS
                            || extraction.status() == DocumentExtractionStatus.PARTIAL) {
                        return new Result.Ok(formatExtractedDocument(filePath, extraction, request, format, ctx));
                    }
                    return new Result.Error("Document extraction unavailable for "
                            + filePath + ": " + extraction.status(), 400);
                }

                if (Files.size(fullPath) > 50_000) {
                    return new Result.Error("File too large for direct display: " + filePath, 400);
                }

                String content = Files.readString(fullPath);
                var sb = new StringBuilder();
                sb.append("File: ").append(filePath).append("\n");
                sb.append("─".repeat(60)).append("\n");
                sb.append(content);
                if (!content.endsWith("\n")) sb.append("\n");
                sb.append("─".repeat(60));
                return new Result.Ok(sb.toString());
            }

            return new Result.Error("Snippet not found: " + input, 404);

        } catch (Exception e) {
            return new Result.Error("Show failed: " + e.getMessage(), 500);
        }
    }

    private static String formatExtractedDocument(
            String filePath,
            DocumentExtractionResult extraction,
            DocumentExtractionRequest request,
            FileCapabilityPolicy.FormatInfo format,
            Context ctx) {
        var sb = new StringBuilder();
        sb.append("Document: ").append(filePath).append("\n");
        sb.append("Model context: not used (/show local display)\n");
        sb.append("Privacy: ").append(PrivateDocumentPolicy.decisionReason(ctx.cfg(), request, format))
                .append("\n");
        if (!extraction.warnings().isEmpty()) {
            sb.append("Warnings:\n");
            extraction.warnings().forEach(w -> sb.append("  - ").append(w.message()).append("\n"));
        }
        sb.append("─".repeat(60)).append("\n");
        sb.append(extraction.safeText());
        if (!extraction.safeText().endsWith("\n")) sb.append("\n");
        sb.append("─".repeat(60));
        return sb.toString();
    }
}
