package dev.loqj.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.util.*;

/**
 * Loads config from classpath resource "config/default-config.yaml" (if present)
 * and then ensures core defaults exist so downstream code/tests never see nulls.
 *
 * Improvements:
 *  - Tracks which keys were defaulted (report).
 *  - Warns once if defaults were applied (can be silenced).
 *  - Strict mode via env LOQJ_STRICT_CONFIG=true -> fail fast if any default is applied.
 *  - Ships "limits" block with sane defaults.
 */
public class Config {

    /** Set LOQJ_STRICT_CONFIG=true to fail when defaults are needed. */
    public static final String STRICT_ENV = "LOQJ_STRICT_CONFIG";
    /** Set LOQJ_NO_WARN_DEFAULTS=true to silence the one-line warning about defaults. */
    public static final String NO_WARN_ENV = "LOQJ_NO_WARN_DEFAULTS";

    /** Public config map as before. */
    public final Map<String, Object> data = new LinkedHashMap<>();

    /** Immutable view of load/report info. */
    public static final class Report {
        public final String loadedFrom;            // e.g., "classpath:config/default-config.yaml" or "(none)"
        public final boolean strictMode;           // env LOQJ_STRICT_CONFIG
        public final List<String> defaultedKeys;   // dotted keys that were filled with defaults

        Report(String loadedFrom, boolean strictMode, List<String> defaultedKeys) {
            this.loadedFrom = loadedFrom;
            this.strictMode = strictMode;
            this.defaultedKeys = Collections.unmodifiableList(defaultedKeys);
        }
    }

    private String loadedFrom = "(none)";
    private final List<String> defaulted = new ArrayList<>();
    private Report snapshot;

    public Config() {
        boolean strict = envTrue(STRICT_ENV);

        // 1) Load YAML (if present)
        Map<String, Object> loaded = new LinkedHashMap<>();
        try (InputStream in = Config.class.getClassLoader().getResourceAsStream("config/default-config.yaml")) {
            if (in != null) {
                ObjectMapper om = new ObjectMapper(new YAMLFactory());
                @SuppressWarnings("unchecked")
                Map<String,Object> m = om.readValue(in, Map.class);
                if (m != null) loaded.putAll(m);
                loadedFrom = "classpath:config/default-config.yaml";
            }
        } catch (Exception ignored) {
            // Keep going with empty map — we'll backfill defaults next
        }

        // 2) Copy and normalize defaults
        data.putAll(loaded);
        ensureDefaults();

        // 3) Strict mode or warn once
        if (!defaulted.isEmpty()) {
            if (strict) {
                throw new IllegalStateException("Strict config mode: required keys missing -> " + String.join(", ", defaulted));
            }
            if (!envTrue(NO_WARN_ENV)) {
                System.err.println("Config: applied safe defaults for: " + String.join(", ", defaulted) +
                        " (set " + NO_WARN_ENV + "=true to silence, or " + STRICT_ENV + "=true to fail).");
            }
        }

        // 4) Freeze report
        snapshot = new Report(loadedFrom, strict, new ArrayList<>(defaulted));
    }

    public Report getReport() {
        return snapshot;
    }

    @SuppressWarnings("unchecked")
    private void ensureDefaults() {
        // ----- rag -----
        Map<String,Object> rag = map(data.get("rag"));
        if (rag == null) { rag = new LinkedHashMap<>(); data.put("rag", rag); defaulted("rag"); }

        // includes
        Object incObj = rag.get("includes");
        if (!(incObj instanceof List<?> inc) || inc.isEmpty()) {
            rag.put("includes", new ArrayList<>(List.of(
                    "**/*.md", "**/*.markdown",
                    "**/*.txt",
                    "**/*.java",
                    "**/*.kt", "**/*.kts", "**/*.gradle",
                    "**/*.xml",
                    "**/*.yml", "**/*.yaml",
                    "**/*.json",
                    "**/*.properties",
                    "**/*.html", "**/*.htm"
            )));
            defaulted("rag.includes");
        }

        // excludes
        Object excObj = rag.get("excludes");
        if (!(excObj instanceof List<?> exc) || exc.isEmpty()) {
            rag.put("excludes", new ArrayList<>(List.of(
                    "**/.git/**", "**/.idea/**",
                    "**/build/**", "**/out/**", "**/target/**",
                    "**/*.class", "**/*.jar", "**/*.zip", "**/*.tar", "**/*.gz",
                    "**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.pdf",
                    "**/*.exe", "**/*.dll", "**/*.so"
            )));
            defaulted("rag.excludes");
        }

        // top_k
        if (!rag.containsKey("top_k")) { rag.put("top_k", 6); defaulted("rag.top_k"); }

        // vectors
        Map<String,Object> vectors = map(rag.get("vectors"));
        if (vectors == null) {
            vectors = new LinkedHashMap<>();
            rag.put("vectors", vectors);
            defaulted("rag.vectors");
        }
        if (!vectors.containsKey("enabled")) { vectors.put("enabled", Boolean.FALSE); defaulted("rag.vectors.enabled"); }

        // ----- ollama -----
        Map<String,Object> ollama = map(data.get("ollama"));
        if (ollama == null) { ollama = new LinkedHashMap<>(); data.put("ollama", ollama); defaulted("ollama"); }
        if (!ollama.containsKey("host"))  { ollama.put("host", "http://localhost:11434"); defaulted("ollama.host"); }
        if (!ollama.containsKey("model")) { ollama.put("model", "qwen3:8b");             defaulted("ollama.model"); }

        // ----- net -----
        Map<String,Object> net = map(data.get("net"));
        if (net == null) { net = new LinkedHashMap<>(); data.put("net", net); defaulted("net"); }
        if (!net.containsKey("enabled")) { net.put("enabled", Boolean.FALSE); defaulted("net.enabled"); }

        // ----- limits -----
        Map<String,Object> limits = map(data.get("limits"));
        if (limits == null) { limits = new LinkedHashMap<>(); data.put("limits", limits); defaulted("limits"); }

        putIfAbsent(limits, "top_k_max",          100, "limits.top_k_max");
        putIfAbsent(limits, "response_max_chars", 10 * 1024 * 1024L, "limits.response_max_chars");
        putIfAbsent(limits, "dir_depth_max",      10, "limits.dir_depth_max");
        putIfAbsent(limits, "file_bytes_max",     20_000, "limits.file_bytes_max");
        putIfAbsent(limits, "file_lines_max",     500, "limits.file_lines_max");
        putIfAbsent(limits, "dir_entries_max",    1000, "limits.dir_entries_max");
        putIfAbsent(limits, "llm_timeout_ms",     300_000L, "limits.llm_timeout_ms");
        putIfAbsent(limits, "file_timeout_ms",    10_000L, "limits.file_timeout_ms");
        putIfAbsent(limits, "rate_per_sec",       10, "limits.rate_per_sec");
    }

    @SuppressWarnings("unchecked")
    private static Map<String,Object> map(Object o) {
        if (o instanceof Map<?,?> m) {
            return new LinkedHashMap<>((Map<String,Object>) (Map<?,?>) m);
        }
        return null;
    }

    private void putIfAbsent(Map<String,Object> m, String key, Object def, String dotted) {
        if (!m.containsKey(key)) { m.put(key, def); defaulted(dotted); }
    }

    private void defaulted(String dottedKey) {
        defaulted.add(dottedKey);
    }

    private static boolean envTrue(String name) {
        String v = System.getenv(name);
        if (v == null) return false;
        String s = v.trim().toLowerCase(Locale.ROOT);
        return s.equals("1") || s.equals("true") || s.equals("yes") || s.equals("on");
    }
}
