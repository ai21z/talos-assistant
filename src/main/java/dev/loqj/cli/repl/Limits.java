package dev.loqj.cli.repl;

import dev.loqj.core.CfgUtil;
import dev.loqj.core.Config;

import java.util.Map;

/** Typed view over cfg.limits for consistent, safe defaults. */
public record Limits(
        int topKMax,
        long responseMaxChars,
        int dirDepthMax,
        int fileBytesMax,
        int fileLinesMax,
        int dirEntriesMax,
        long llmTimeoutMs,
        long fileTimeoutMs,
        int ratePerSec
) {
    public static Limits fromConfig(Config cfg) {
        Map<String,Object> m = CfgUtil.map(cfg.data.get("limits"));
        return new Limits(
                CfgUtil.intAt(m,  "top_k_max",          100),
                CfgUtil.longAt(m, "response_max_chars", 10 * 1024 * 1024L),
                CfgUtil.intAt(m,  "dir_depth_max",      10),
                CfgUtil.intAt(m,  "file_bytes_max",     20_000),
                CfgUtil.intAt(m,  "file_lines_max",     500),
                CfgUtil.intAt(m,  "dir_entries_max",    1000),
                CfgUtil.longAt(m, "llm_timeout_ms",     300_000L),
                CfgUtil.longAt(m, "file_timeout_ms",    10_000L),
                CfgUtil.intAt(m,  "rate_per_sec",       10)
        );
    }
}
