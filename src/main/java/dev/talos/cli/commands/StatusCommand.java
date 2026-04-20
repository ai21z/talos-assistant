package dev.talos.cli.commands;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.ui.AnsiColor;
import dev.talos.core.CfgUtil;
import dev.talos.core.IndexPathResolver;
import dev.talos.runtime.XmlCompatTelemetry;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

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
                "/status [--verbose]",
                "Show configuration.",
                CommandGroup.SESSION);
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

        Path absWorkspace = workspace.toAbsolutePath().normalize();
        Path indexDir = IndexPathResolver.getIndexDirectory(absWorkspace);
        boolean indexExists = java.nio.file.Files.exists(indexDir);

        sb.append(AnsiColor.bold("Talos Status")).append("\n\n");
        sb.append(AnsiColor.grey("  Workspace ")).append(absWorkspace).append("\n");
        sb.append(AnsiColor.grey("  Index     ")).append(indexDir).append("\n\n");

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
        String host = Objects.toString(oll.getOrDefault("host", "http://127.0.0.1:11434"));
        String activeModel = ctx.llm().getModel();
        String embedModel = Objects.toString(oll.getOrDefault("embed", "bge-m3"));

        sb.append(AnsiColor.grey("  Mode      ")).append(AnsiColor.blue(modes.getActiveName())).append("\n");
        sb.append(AnsiColor.grey("  Model     ")).append(activeModel).append("\n");
        sb.append(AnsiColor.grey("  Scope     ")).append(workspace.getFileName()).append("\n");
        sb.append(AnsiColor.grey("  Vectors   ")).append(vectors ? AnsiColor.green("ON") : AnsiColor.yellow("OFF")).append("\n");

        if (verbose) {
            sb.append(AnsiColor.grey("  Host      ")).append(host).append("\n");
            sb.append(AnsiColor.grey("  Embed     ")).append(embedModel).append("\n");
            sb.append(AnsiColor.grey("  Concurr.  ")).append(CfgUtil.intAt(rag, "embed_concurrency", 4)).append("\n");
        }

        sb.append("\n").append(AnsiColor.grey("  Limits")).append("\n");
        sb.append(AnsiColor.dim(String.format("    top_k_max=%d  response_max=%d\n", topKMax, responseMax)));
        sb.append(AnsiColor.dim(String.format("    dir_depth=%d  dir_entries=%d\n", dirDepthMax, dirEntriesMax)));
        sb.append(AnsiColor.dim(String.format("    file_bytes=%d  file_lines=%d\n", fileBytesMax, fileLinesMax)));
        sb.append(AnsiColor.dim(String.format("    llm_timeout=%ds  file_timeout=%ds  rate=%d/s\n",
                Duration.ofMillis(llmTimeoutMs).toSeconds(),
                Duration.ofMillis(fileTimeoutMs).toSeconds(),
                ratePerSec)));

        sb.append("\n").append(AnsiColor.grey("  Config")).append("\n");
        sb.append(AnsiColor.dim("    from=")).append(AnsiColor.dim(String.valueOf(cfg.getReport().loadedFrom)));
        sb.append(AnsiColor.dim("  strict=")).append(AnsiColor.dim(String.valueOf(cfg.getReport().strictMode)));
        sb.append(AnsiColor.dim("  defaults=")).append(AnsiColor.dim(String.valueOf(cfg.getReport().defaultedKeys.size())));
        if (!verbose) sb.append(AnsiColor.grey("  (/status --verbose)"));
        sb.append("\n");

        if (verbose) {
            try {
                var indexer = ctx.rag().getIndexer();
                var stats = indexer.getLastRunStats();
                if (stats != null) {
                    sb.append("\n").append(AnsiColor.grey("  Last Index Run")).append("\n");
                    sb.append(AnsiColor.dim("    " + stats.getSummary())).append("\n");
                    sb.append(AnsiColor.dim("    " + stats.getDetailedTimings())).append("\n");
                }
            } catch (Exception ignore) {}

            try (var cache = new dev.talos.core.cache.CacheDb()) {
                var cacheStats = cache.getStats();
                sb.append("\n").append(AnsiColor.grey("  Cache")).append("\n");
                sb.append(AnsiColor.dim("    " + cacheStats.summary())).append("\n");
            } catch (Exception ignore) {
                sb.append(AnsiColor.dim("  Cache: unavailable")).append("\n");
            }

            if (!cfg.getReport().defaultedKeys.isEmpty()) {
                sb.append(AnsiColor.dim("  Defaulted: " + String.join(", ", cfg.getReport().defaultedKeys))).append("\n");
            }

            var xmlCompat = XmlCompatTelemetry.snapshot();
            sb.append("\n").append(AnsiColor.grey("  XML Compat")).append("\n");
            sb.append(AnsiColor.dim("    parser_activations=" + xmlCompat.parserFallbackActivations()
                    + "  parser_calls=" + xmlCompat.parserFallbackCalls()
                    + "  stream_suppressed=" + xmlCompat.streamSuppressedBlocks())).append("\n");
            if (xmlCompat.lastParserFallbackAt() != null) {
                sb.append(AnsiColor.dim("    last_parser_at=" + xmlCompat.lastParserFallbackAt())).append("\n");
            }
            if (xmlCompat.lastStreamSuppressedAt() != null) {
                sb.append(AnsiColor.dim("    last_stream_at=" + xmlCompat.lastStreamSuppressedAt())).append("\n");
            }
            if (xmlCompat.lastParserToolNames() != null && !xmlCompat.lastParserToolNames().isBlank()) {
                sb.append(AnsiColor.dim("    last_tools=" + xmlCompat.lastParserToolNames())).append("\n");
            }
            if (!xmlCompat.hasAnySignal()) {
                sb.append(AnsiColor.dim("    no XML compatibility usage observed in this process")).append("\n");
            }
        }

        sb.append("\n");
        return new Result.TrustedInfo(sb.toString());
    }
}
