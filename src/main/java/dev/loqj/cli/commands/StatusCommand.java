package dev.loqj.cli.commands;

import dev.loqj.cli.modes.ModeController;
import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.CfgUtil;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;

public final class StatusCommand implements Command {
    private final ModeController modes;
    private final Path workspace;

    public StatusCommand(ModeController modes, Path workspace) {
        this.modes = modes;
        this.workspace = workspace;
    }

    @Override public CommandSpec spec() {
        return new CommandSpec("status",
                java.util.List.of("--verbose", "-v"),
                ":status [--verbose]",
                "Show current configuration and limits.");
    }

    @Override
    public Result execute(String args, Context ctx) {
        boolean verbose = false;
        if (args != null && !args.isBlank()) {
            String a = args.toLowerCase(Locale.ROOT).trim();
            verbose = a.equals("--verbose") || a.equals("-v") || a.equals("verbose");
        }

        var sb = new StringBuilder();
        var cfg = ctx.cfg();

        var lim = CfgUtil.map(cfg.data.get("limits"));
        int topKMax          = CfgUtil.intAt(lim, "top_k_max", 100);
        long responseMax     = CfgUtil.longAt(lim, "response_max_chars", 10 * 1024 * 1024L);
        int dirDepthMax      = CfgUtil.intAt(lim, "dir_depth_max", 10);
        int dirEntriesMax    = CfgUtil.intAt(lim, "dir_entries_max", 1000);
        int fileBytesMax     = CfgUtil.intAt(lim, "file_bytes_max", 20_000);
        int fileLinesMax     = CfgUtil.intAt(lim, "file_lines_max", 500);
        long llmTimeoutMs    = CfgUtil.longAt(lim, "llm_timeout_ms", 300_000L);
        long fileTimeoutMs   = CfgUtil.longAt(lim, "file_timeout_ms", 10_000L);
        int ratePerSec       = CfgUtil.intAt(lim, "rate_per_sec", 10);

        boolean vectors = true;
        var rag = CfgUtil.map(cfg.data.get("rag"));
        var vectorsObj = rag.get("vectors");
        if (vectorsObj instanceof Map<?,?> vm) {
            Object en = vm.get("enabled");
            if (en instanceof Boolean b) vectors = b;
        }

        var oll = CfgUtil.map(cfg.data.get("ollama"));
        String host = (String) oll.getOrDefault("host", "http://127.0.0.1:11434");
        // Get active model from LlmClient instead of config default
        String activeModel = ctx.llm().getModel();
        String embedModel = (String) oll.getOrDefault("embed", "bge-m3");

        sb.append("Current configuration:\n");
        sb.append("  Mode:        ").append(modes.getActiveName()).append("\n");
        sb.append("  Model:       ").append(activeModel).append("\n");
        sb.append("  Scope:       ").append(workspace.getFileName()).append("\n");
        sb.append("  Vectors:     ").append(vectors ? "ON" : "OFF").append("\n");

        if (verbose) {
            sb.append("  Host:        ").append(host).append("\n");
            sb.append("  Embed Model: ").append(embedModel).append("\n");
            sb.append("  Embed Conc:  ").append(CfgUtil.intAt(rag, "embed_concurrency", 4)).append("\n");
            sb.append("  Force Full:  ").append(CfgUtil.intAt(rag, "force_full_reindex", 0) == 1 ? "ON" : "OFF").append("\n");
        }

        sb.append("  Limits:\n");
        sb.append(String.format("    top_k_max=%d, response_max_chars=%d\n", topKMax, responseMax));
        sb.append(String.format("    dir_depth_max=%d, dir_entries_max=%d\n", dirDepthMax, dirEntriesMax));
        sb.append(String.format("    file_bytes_max=%d, file_lines_max=%d\n", fileBytesMax, fileLinesMax));
        sb.append(String.format("    llm_timeout=%ds, file_timeout=%ds, rate_per_sec=%d\n",
                Duration.ofMillis(llmTimeoutMs).toSeconds(),
                Duration.ofMillis(fileTimeoutMs).toSeconds(),
                ratePerSec));

        sb.append("  Config:\n");
        sb.append("    loadedFrom=").append(cfg.getReport().loadedFrom).append(", ");
        sb.append("strict=").append(cfg.getReport().strictMode).append(", ");
        sb.append("defaults=").append(cfg.getReport().defaultedKeys.size());
        if (!verbose) sb.append("  (use :status --verbose)");
        sb.append("\n");

        if (verbose) {
            // Add detailed indexing stats if available
            try {
                var indexer = ctx.rag().getIndexer();
                var stats = indexer.getLastRunStats();
                if (stats != null) {
                    sb.append("  Last Index Run:\n");
                    sb.append("    ").append(stats.getSummary()).append("\n");
                    sb.append("    ").append(stats.getDetailedTimings()).append("\n");
                }
            } catch (Exception ignore) {
                // Indexer might not be available in all contexts
            }

            // Add cache statistics
            try (var cache = new dev.loqj.core.cache.CacheDb()) {
                var cacheStats = cache.getStats();
                sb.append("  Cache:\n");
                sb.append("    ").append(cacheStats.summary()).append("\n");
            } catch (Exception ignore) {
                sb.append("  Cache: unavailable\n");
            }

            // Show defaulted config keys if any
            if (!cfg.getReport().defaultedKeys.isEmpty()) {
                sb.append("  Defaulted keys: ").append(String.join(", ", cfg.getReport().defaultedKeys)).append("\n");
            }
        }

        sb.append("\n");
        return new Result.Ok(sb.toString());
    }

    private static String shortenPath(Path path) {
        String home = System.getProperty("user.home");
        String pathStr = path.toString();
        if (home != null && !home.isBlank() && pathStr.startsWith(home)) {
            return "~" + pathStr.substring(home.length()).replace('\\', '/');
        }
        return path.getFileName().toString();
    }
}
