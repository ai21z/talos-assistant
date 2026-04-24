package dev.talos.core;

import java.util.List;
import java.util.Map;

/**
 * Typed read-only view over {@link Config#data}.
 *
 * <p>Provides type-safe accessors like {@code cfg.rag().topK()} instead of
 * raw {@code CfgUtil.intAt(CfgUtil.map(cfg.data.get("rag")), "top_k", 6)}.
 *
 * <p>All accessors are computed on each call (no caching) — this keeps the
 * view consistent with any mutations to the underlying map (e.g., ENV
 * overrides, user config overlays, or runtime changes via commands).
 *
 * <p>Usage:
 * <pre>{@code
 *   ConfigView v = ConfigView.of(cfg);
 *   int topK     = v.rag().topK();
 *   String host  = v.ollama().host();
 *   int timeout  = v.limits().llmTimeoutMs();
 * }</pre>
 */
public final class ConfigView {

    private final Config cfg;

    private ConfigView(Config cfg) {
        this.cfg = cfg;
    }

    /** Create a typed view over the given config. */
    public static ConfigView of(Config cfg) {
        return new ConfigView(cfg == null ? new Config() : cfg);
    }

    /** The underlying Config (for backward compatibility). */
    public Config raw() { return cfg; }

    // ── Section accessors ─────────────────────────────────────────────

    public RagConfig rag()       { return new RagConfig(section("rag")); }
    public OllamaConfig ollama() { return new OllamaConfig(section("ollama")); }
    public LimitsConfig limits() { return new LimitsConfig(section("limits")); }
    public NetConfig net()       { return new NetConfig(section("net")); }
    public UiConfig ui()         { return new UiConfig(section("ui")); }
    public ToolsConfig tools()   { return new ToolsConfig(section("tools")); }
    public SessionConfig session() { return new SessionConfig(section("session")); }

    // ── RAG ───────────────────────────────────────────────────────────

    public record RagConfig(Map<String, Object> m) {
        public int topK()            { return CfgUtil.intAt(m, "top_k", 6); }
        public int chunkChars()      { return CfgUtil.intAt(m, "chunk_chars", 1200); }
        public int chunkOverlap()    { return CfgUtil.intAt(m, "chunk_overlap", 150); }
        public int embedConcurrency(){ return CfgUtil.intAt(m, "embed_concurrency", 4); }
        public boolean forceFullReindex() { return CfgUtil.boolAt(m, "force_full_reindex", false); }
        public List<String> includes() { return CfgUtil.strList(m.get("includes")); }
        public List<String> excludes() { return CfgUtil.strList(m.get("excludes")); }
        public VectorsConfig vectors() { return new VectorsConfig(CfgUtil.map(m.get("vectors"))); }
    }

    public record VectorsConfig(Map<String, Object> m) {
        public boolean enabled() { return CfgUtil.boolAt(m, "enabled", false); }
    }

    // ── Ollama ────────────────────────────────────────────────────────

    public record OllamaConfig(Map<String, Object> m) {
        public String host()  { return strAt(m, "host", "http://127.0.0.1:11434"); }
        public String model() { return strAt(m, "model", "qwen2.5-coder:14b"); }
        public String embed() { return strAt(m, "embed", "bge-m3"); }
        public boolean allowRemote() { return CfgUtil.boolAt(m, "allow_remote", false); }
    }

    // ── Limits ────────────────────────────────────────────────────────

    public record LimitsConfig(Map<String, Object> m) {
        public int topKMax()          { return CfgUtil.intAt(m, "top_k_max", 100); }
        public long responseMaxChars(){ return CfgUtil.longAt(m, "response_max_chars", 10_485_760L); }
        public int dirDepthMax()      { return CfgUtil.intAt(m, "dir_depth_max", 10); }
        public int fileBytesMax()     { return CfgUtil.intAt(m, "file_bytes_max", 200_000); }
        public int fileLinesMax()     { return CfgUtil.intAt(m, "file_lines_max", 8_000); }
        public int dirEntriesMax()    { return CfgUtil.intAt(m, "dir_entries_max", 1000); }
        public long llmTimeoutMs()    { return CfgUtil.longAt(m, "llm_timeout_ms", 300_000L); }
        public long fileTimeoutMs()   { return CfgUtil.longAt(m, "file_timeout_ms", 10_000L); }
        public int ratePerSec()       { return CfgUtil.intAt(m, "rate_per_sec", 10); }
        public int llmContextMaxTokens() { return CfgUtil.intAt(m, "llm_context_max_tokens", 8192); }
    }

    // ── Net ───────────────────────────────────────────────────────────

    public record NetConfig(Map<String, Object> m) {
        public boolean enabled() { return CfgUtil.boolAt(m, "enabled", false); }
    }

    // ── UI ────────────────────────────────────────────────────────────

    public record UiConfig(Map<String, Object> m) {
        public boolean showStatusDuringAnswer() { return CfgUtil.boolAt(m, "show_status_during_answer", true); }
        public boolean showTimingAfterAnswer()  { return CfgUtil.boolAt(m, "show_timing_after_answer", true); }
        public boolean showBreakdown()          { return CfgUtil.boolAt(m, "show_breakdown", false); }
        public String statusLabel()             { return strAt(m, "status_label", "Answering\u2026"); }
    }

    // ── Tools ─────────────────────────────────────────────────────────

    public record ToolsConfig(Map<String, Object> m) {
        public boolean nativeCalling() { return CfgUtil.boolAt(m, "native_calling", true); }
    }

    // ── Session ───────────────────────────────────────────────────────

    public record SessionConfig(Map<String, Object> m) {
        public boolean persistence() { return CfgUtil.boolAt(m, "persistence", true); }
    }

    // ── Internal ──────────────────────────────────────────────────────

    private Map<String, Object> section(String key) {
        return CfgUtil.map(cfg.data.get(key));
    }

    private static String strAt(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        if (v == null) return def;
        String s = String.valueOf(v);
        return s.isBlank() ? def : s;
    }
}

