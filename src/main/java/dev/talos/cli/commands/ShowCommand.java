package dev.talos.cli.commands;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.index.LuceneStore;

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
                ":show <rel>#<chunk>",
                "Display specific snippet by file path and chunk ID.");
    }

    @Override public Result execute(String args, Context ctx) {
        if (args == null || args.trim().isEmpty()) {
            return new Result.Error("Usage: :show <rel>#<chunk>  (e.g., :show src/main/Main.java#0)", 400);
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

            // Fallback: try to read the file directly
            Path fullPath = workspace.resolve(filePath);
            if (Files.exists(fullPath) && Files.isReadable(fullPath)) {
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
}
