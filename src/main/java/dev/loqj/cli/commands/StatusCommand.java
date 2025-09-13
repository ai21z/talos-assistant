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
        Map<String,Object> rag = CfgUtil.map(cfg.data.get("rag"));
        if (rag != null) {
            Map<String,Object> vec = CfgUtil.map(rag.get("vectors"));
            vectors = vec == null || Boolean.TRUE.equals(vec.getOrDefault("enabled", true));
        }

        sb.append("Current configuration:\n");
        sb.append("  Mode:        ").append(modes.getActiveName()).append("\n");
        sb.append("  Model:       ").append(ctx.llm().getModel()).append("\n");
        sb.append("  Scope:       ").append(shortenPath(workspace)).append("\n");
        sb.append("  Vectors:     ").append(vectors ? "ON" : "OFF").append("\n");
        sb.append("  Limits:\n");
        sb.append("    top_k_max=").append(topKMax)
                .append(", response_max_chars=").append(responseMax).append("\n");
        sb.append("    dir_depth_max=").append(dirDepthMax)
                .append(", dir_entries_max=").append(dirEntriesMax).append("\n");
        sb.append("    file_bytes_max=").append(fileBytesMax)
                .append(", file_lines_max=").append(fileLinesMax).append("\n");
        sb.append("    llm_timeout=").append(Duration.ofMillis(llmTimeoutMs).toSeconds()).append("s")
                .append(", file_timeout=").append(Duration.ofMillis(fileTimeoutMs).toSeconds()).append("s")
                .append(", rate_per_sec=").append(ratePerSec).append("\n");

        var report = cfg.getReport();
        sb.append("  Config:\n");
        sb.append("    loadedFrom=").append(report.loadedFrom)
                .append(", strict=").append(report.strictMode)
                .append(", defaults=").append(report.defaultedKeys.size())
                .append("  (use :status --verbose)\n\n");

        if (verbose) {
            sb.append("Config Report\n");
            sb.append("  loadedFrom : ").append(report.loadedFrom).append("\n");
            sb.append("  strict     : ").append(report.strictMode).append("\n");
            sb.append("  defaults   : ").append(report.defaultedKeys.isEmpty() ? "(none)" : report.defaultedKeys.size()).append("\n");
            if (!report.defaultedKeys.isEmpty()) {
                sb.append("  defaulted keys:\n");
                for (String k : report.defaultedKeys) sb.append("    - ").append(k).append("\n");
            }
            sb.append("\n");
        }

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
