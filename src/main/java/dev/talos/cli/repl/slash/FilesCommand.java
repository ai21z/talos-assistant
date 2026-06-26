package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.core.index.LuceneStore;

import java.nio.file.Path;
import java.util.*;

/**
 * `/files` — List all indexed files in the workspace.
 * Provides deterministic file inventory without LLM hallucinations.
 */
public class FilesCommand implements Command {

    private final Path workspace;

    public FilesCommand(Path workspace) {
        this.workspace = workspace;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("files",
                List.of(),
                "/files",
                "List indexed files.",
                CommandGroup.KNOWLEDGE);
    }

    @Override
    public Result execute(String args, Context ctx) throws Exception {
        try {
            Path indexDir = ctx.rag().getIndexer().indexDirFor(workspace);

            // Open index and use proper MatchAllDocsQuery instead of bm25("*")
            Map<String, Integer> fileChunkCounts = new LinkedHashMap<>();
            Set<String> directories = new LinkedHashSet<>();

            try (LuceneStore store = new LuceneStore(indexDir, 0)) {
                // Use matchAll() which properly retrieves all documents
                var allHits = store.matchAll(100000);

                for (var hit : allHits) {
                    String path = hit.path();
                    if (path != null) {
                        // Strip chunk ID (e.g., "README.md#0" -> "README.md")
                        int hashIdx = path.indexOf('#');
                        String basePath = (hashIdx < 0) ? path : path.substring(0, hashIdx);
                        fileChunkCounts.merge(basePath, 1, Integer::sum);

                        // Extract parent directories
                        String normalizedPath = basePath.replace('\\', '/');
                        int lastSlash = normalizedPath.lastIndexOf('/');
                        if (lastSlash > 0) {
                            String parentDir = normalizedPath.substring(0, lastSlash);
                            // Add all parent directories (for nested paths like a/b/c/file.txt)
                            String[] parts = parentDir.split("/");
                            StringBuilder dirPath = new StringBuilder();
                            for (String part : parts) {
                                if (!part.isEmpty()) {
                                    if (!dirPath.isEmpty()) dirPath.append('/');
                                    dirPath.append(part);
                                    directories.add(dirPath.toString());
                                }
                            }
                        }
                    }
                }

                // Better diagnostics if empty
                if (fileChunkCounts.isEmpty()) {
                    int docCount = store.numDocs();
                    if (docCount == 0) {
                        return new Result.Info("No files indexed. Run /reindex to build the index.");
                    }
                    return new Result.Info("Index has " + docCount + " chunks but no file paths found. Try /reindex --full.");
                }
            }

            // Sort files and directories alphabetically
            List<Map.Entry<String, Integer>> sortedFiles = new ArrayList<>(fileChunkCounts.entrySet());
            sortedFiles.sort(Map.Entry.comparingByKey(String.CASE_INSENSITIVE_ORDER));
            List<String> sortedDirs = new ArrayList<>(directories);
            sortedDirs.sort(String.CASE_INSENSITIVE_ORDER);

            StringBuilder out = new StringBuilder();

            // Show directories first (if any)
            if (!sortedDirs.isEmpty()) {
                out.append("Directories (").append(sortedDirs.size()).append("):\n\n");
                for (String dir : sortedDirs) {
                    out.append("  ").append(dir).append("/\n");
                }
                out.append("\n");
            }

            // Then show files
            out.append("Indexed files (").append(sortedFiles.size()).append("):\n\n");
            for (Map.Entry<String, Integer> entry : sortedFiles) {
                out.append("  ").append(entry.getKey());
                if (entry.getValue() > 1) {
                    out.append("  (").append(entry.getValue()).append(" chunks)");
                }
                out.append("\n");
            }

            return new Result.TrustedInfo(out.toString());

        } catch (Exception e) {
            return new Result.Error("Failed to list files: " + e.getMessage(), 1);
        }
    }
}
