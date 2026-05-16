package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.ui.AnsiColor;
import dev.talos.core.cache.CacheDb;
import dev.talos.core.index.IndexProgressListener;
import dev.talos.core.index.IndexingStats;

import java.nio.file.Path;
import java.util.List;

public final class ReindexCommand implements Command {
    private final Path workspace;
    private final Runnable postReindexHook;

    public ReindexCommand(Path workspace) { this(workspace, null); }

    /**
     * @param workspace        the workspace root to reindex
     * @param postReindexHook  optional callback invoked after a successful reindex
     *                         (e.g. to invalidate the workspace symbol cache)
     */
    public ReindexCommand(Path workspace, Runnable postReindexHook) {
        this.workspace = workspace;
        this.postReindexHook = postReindexHook;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("reindex", List.of("--stats", "--full", "--prune"),
            "/reindex [--stats|--full|--prune <days>]",
            "Rebuild local index.",
            CommandGroup.KNOWLEDGE);
    }

    @Override
    public Result execute(String args, Context ctx) {
        try {
            var indexer = ctx.rag().getIndexer();

            // Parse command arguments
            args = args.trim();

            // Handle --stats flag
            if (args.equals("--stats")) {
                IndexingStats stats = indexer.getLastRunStats();
                if (stats == null) {
                    return new Result.Info("No indexing statistics available. Run :reindex first.\n");
                }

                StringBuilder sb = new StringBuilder();
                sb.append("Last Indexing Run Statistics:\n");
                sb.append("  ").append(stats.getSummary()).append("\n");
                sb.append("  ").append(stats.getDetailedTimings()).append("\n");

                // Add cache statistics
                try (CacheDb cache = new CacheDb()) {
                    var cacheStats = cache.getStats();
                    sb.append("  Cache: ").append(cacheStats.summary()).append("\n");
                }

                return new Result.Ok(sb.toString());
            }

            // Handle --prune flag
            if (args.startsWith("--prune")) {
                String[] parts = args.split("\\s+");
                int days = 90; // default
                if (parts.length > 1) {
                    try {
                        days = Integer.parseInt(parts[1]);
                    } catch (NumberFormatException e) {
                        return new Result.Error("Invalid days argument for --prune: " + parts[1] + "\n", 400);
                    }
                }

                try (CacheDb cache = new CacheDb()) {
                    int deletedEmbeddings = cache.pruneOldEmbeddings(days);
                    int deletedAnswers = cache.pruneOldAnswers(days);
                    return new Result.Ok(String.format("Cache pruned: %d embeddings, %d answers older than %d days.\n",
                        deletedEmbeddings, deletedAnswers, days));
                }
            }

            // Handle --full flag or regular reindex
            boolean forceFullReindex = args.equals("--full");

            // Build a progress listener for live terminal feedback
            boolean interactive = System.console() != null;
            IndexProgressListener progress = interactive ? (completed, total, file) -> {
                int pct = total > 0 ? (completed * 100) / total : 0;
                String display = file.length() > 40
                        ? "…" + file.substring(file.length() - 39) : file;
                System.out.print("\r  " + AnsiColor.DIM + "Indexing: "
                        + completed + "/" + total + " (" + pct + "%)  " + display
                        + AnsiColor.RESET + "          ");
                System.out.flush();
                if (completed >= total) {
                    System.out.print("\r" + " ".repeat(80) + "\r");
                    System.out.flush();
                }
            } : IndexProgressListener.NOOP;

            var outcome = ctx.rag().reindex(workspace, forceFullReindex, progress);
            if (!outcome.indexed()) {
                return new Result.Info(outcome.message() + "\n");
            }

            // Get and display statistics
            IndexingStats stats = indexer.getLastRunStats();

            // Notify listeners (e.g. invalidate workspace symbol cache)
            if (postReindexHook != null) {
                postReindexHook.run();
            }

            if (stats != null) {
                String msg = String.format("Reindex complete: %s\n", stats.getSummary());
                return new Result.Ok(msg);
            } else {
                return new Result.Ok("Reindexed.\n");
            }

        } catch (Exception ex) {
            String err = ex.getMessage() == null ? "(no details)" : ex.getMessage()
                    .replaceAll("([A-Za-z]:)?[\\\\/][^\\\\/]+(?:[\\\\/][^\\\\/]+)*", "[path]");
            return new Result.Error("Reindex failed: " + err + "\n", 500);
        }
    }
}
