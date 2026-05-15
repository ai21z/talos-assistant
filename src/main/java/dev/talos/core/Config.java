package dev.talos.core;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Loads config with precedence: CLI flags > ENV > user-config > classpath defaults.
 *
 * Config sources (in order):
 *  1. Classpath resource "config/default-config.yaml"
 *  2. User config file: ~/.talos/config.yaml (or %USERPROFILE%\.talos\config.yaml on Windows)
 *  3. Environment variables: TALOS__rag__top_k=8 maps to rag.top_k=8
 *  4. CLI flags (applied by command classes)
 *
 * Improvements:
 *  - Tracks which keys were defaulted (report).
 *  - Warns once if defaults were applied (can be silenced).
 *  - Strict mode via env TALOS_STRICT_CONFIG=true -> fail fast if any default is applied.
 *  - Ships "limits" block with sane defaults including llm_context_max_tokens.
 */
public class Config {

    /** Set TALOS_STRICT_CONFIG=true to fail when defaults are needed. */
    public static final String STRICT_ENV = "TALOS_STRICT_CONFIG";
    /** Set TALOS_NO_WARN_DEFAULTS=true to silence the one-line warning about defaults. */
    public static final String NO_WARN_ENV = "TALOS_NO_WARN_DEFAULTS";

    /** Public config map as before. */
    public final Map<String, Object> data = new LinkedHashMap<>();

    /** Immutable view of load/report info. */
    public static final class Report {
        public final String loadedFrom;            // e.g., "classpath:config/default-config.yaml" or "(none)"
        public final String userConfigPath;        // e.g., "~/.talos/config.yaml" or "(none)"
        public final boolean userConfigPresent;    // true when the user config file exists
        public final boolean userConfigLoaded;     // true only when the user config parsed and merged
        public final String userConfigError;       // parse/load error, blank when none
        public final boolean strictMode;           // env TALOS_STRICT_CONFIG
        public final List<String> defaultedKeys;   // dotted keys that were filled with defaults
        public final int envOverridesApplied;      // count of ENV overrides

        Report(String loadedFrom,
               String userConfigPath,
               boolean userConfigPresent,
               boolean userConfigLoaded,
               String userConfigError,
               boolean strictMode,
               List<String> defaultedKeys,
               int envOverrides) {
            this.loadedFrom = loadedFrom;
            this.userConfigPath = userConfigPath;
            this.userConfigPresent = userConfigPresent;
            this.userConfigLoaded = userConfigLoaded;
            this.userConfigError = userConfigError == null ? "" : userConfigError;
            this.strictMode = strictMode;
            this.defaultedKeys = Collections.unmodifiableList(defaultedKeys);
            this.envOverridesApplied = envOverrides;
        }
    }

    private String loadedFrom = "(none)";
    private String userConfigPath = "(none)";
    private boolean userConfigPresent = false;
    private boolean userConfigLoaded = false;
    private String userConfigError = "";
    private final List<String> defaulted = new ArrayList<>();
    private int envOverridesCount = 0;
    private Report snapshot;

    public Config() {
        this(getUserConfigPath());
    }

    /**
     * Test and setup seam for loading a specific user config path.
     */
    public Config(Path explicitUserConfigPath) {
        boolean strict = envTrue(STRICT_ENV);

        // 1) Load classpath default config
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

        data.putAll(loaded);
        ensureDefaults();

        // 2) Load user config overlay from ~/.talos/config.yaml
        Path userConfig = explicitUserConfigPath;
        if (userConfig != null) {
            userConfigPath = userConfig.toString();
        }
        if (userConfig != null && Files.exists(userConfig) && Files.isRegularFile(userConfig)) {
            userConfigPresent = true;
            try {
                ObjectMapper om = new ObjectMapper(new YAMLFactory());
                @SuppressWarnings("unchecked")
                Map<String, Object> userMap = om.readValue(userConfig.toFile(), Map.class);
                if (userMap != null && !userMap.isEmpty()) {
                    CfgUtil.deepMerge(data, userMap);
                }
                userConfigLoaded = true;
                userConfigError = "";
            } catch (Exception ignored) {
                userConfigLoaded = false;
                userConfigError = summarizeConfigError(ignored);
            }
        }

        // 3) Apply ENV overrides (TALOS__rag__top_k=8 -> rag.top_k=8)
        Map<String, Object> envOverrides = CfgUtil.parseEnvOverrides();
        if (!envOverrides.isEmpty()) {
            CfgUtil.deepMerge(data, envOverrides);
            envOverridesCount = countLeafKeys(envOverrides);
        }

        // 4) Strict mode or warn once
        if (!defaulted.isEmpty()) {
            if (strict) {
                throw new IllegalStateException("Strict config mode: required keys missing -> " + String.join(", ", defaulted));
            }
            if (!envTrue(NO_WARN_ENV)) {
                System.err.println("Config: applied safe defaults for: " + String.join(", ", defaulted) +
                        " (set " + NO_WARN_ENV + "=true to silence, or " + STRICT_ENV + "=true to fail).");
            }
        }

        // 5) Freeze report
        snapshot = new Report(
                loadedFrom,
                userConfigPath,
                userConfigPresent,
                userConfigLoaded,
                userConfigError,
                strict,
                new ArrayList<>(defaulted),
                envOverridesCount);
    }

    public Report getReport() {
        return snapshot;
    }

    /** Typed read-only view over this config's data. */
    public ConfigView view() {
        return ConfigView.of(this);
    }

    /**
     * Resolve user config path: ~/.talos/config.yaml (Unix) or %USERPROFILE%\.talos\config.yaml (Windows)
     */
    private static Path getUserConfigPath() {
        String home = System.getProperty("user.home");
        if (home == null || home.isBlank()) {
            home = System.getenv("USERPROFILE"); // Windows fallback
        }
        if (home == null || home.isBlank()) return null;
        return Paths.get(home, ".talos", "config.yaml");
    }

    private static int countLeafKeys(Map<String, Object> map) {
        int count = 0;
        for (Object v : map.values()) {
            if (v instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nested = (Map<String, Object>) v;
                count += countLeafKeys(nested);
            } else {
                count++;
            }
        }
        return count;
    }

    private static String summarizeConfigError(Exception error) {
        if (error == null) return "unknown error";
        String message = error.getMessage();
        if (message == null || message.isBlank()) {
            message = error.getClass().getSimpleName();
        }
        return message.replace('\r', ' ').replace('\n', ' ').trim();
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
                    "**/*.csv", "**/*.tsv",
                    "**/*.properties",
                    "**/*.html", "**/*.htm"
            )));
            defaulted("rag.includes");
        }

        // excludes
        Object excObj = rag.get("excludes");
        if (!(excObj instanceof List<?> exc) || exc.isEmpty()) {
            rag.put("excludes", new ArrayList<>(List.of(
                    "**/.env", "**/.env.*", "**/*.env",
                    "**/secrets/**", "**/.ssh/**", "**/.aws/**", "**/.azure/**",
                    "**/.gnupg/**", "**/.config/gcloud/**", "**/protected/**",
                    "**/.git/**", "**/.idea/**", "**/.vscode/**", "**/.external assistant/**",
                    "**/.gradle/**", "**/.mvn/**", "**/node_modules/**",
                    "**/build/**", "**/out/**", "**/target/**",
                    "**/dist/**", "**/prompts/**", "**/META-INF/**",
                    "**/*.class", "**/*.jar", "**/*.zip", "**/*.tar", "**/*.gz",
                    "**/*.tgz", "**/*.7z", "**/*.rar",
                    "**/*.png", "**/*.jpg", "**/*.jpeg", "**/*.gif", "**/*.bmp",
                    "**/*.webp", "**/*.tif", "**/*.tiff", "**/*.pdf",
                    "**/*.doc", "**/*.docx", "**/*.xls", "**/*.xlsx",
                    "**/*.ppt", "**/*.pptx",
                    "**/*.exe", "**/*.dll", "**/*.so", "**/*.dylib",
                    "**/*.war", "**/*.ear", "**/*.bin", "**/*.dat"
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
        if (!ollama.containsKey("model")) { ollama.put("model", "qwen2.5-coder:14b");   defaulted("ollama.model"); }

        // ----- llm -----
        Map<String,Object> llm = map(data.get("llm"));
        if (llm == null) { llm = new LinkedHashMap<>(); data.put("llm", llm); defaulted("llm"); }
        putIfAbsent(llm, "transport", "engine", "llm.transport");
        putIfAbsent(llm, "default_backend", "llama_cpp", "llm.default_backend");
        putIfAbsent(llm, "model", "talos-agent", "llm.model");

        // ----- embed -----
        Map<String,Object> embed = map(data.get("embed"));
        if (embed == null) { embed = new LinkedHashMap<>(); data.put("embed", embed); defaulted("embed"); }
        putIfAbsent(embed, "provider", "compat", "embed.provider");
        putIfAbsent(embed, "model", "talos-embed", "embed.model");
        putIfAbsent(embed, "host", "", "embed.host");
        putIfAbsent(embed, "allow_remote", Boolean.FALSE, "embed.allow_remote");

        // ----- net -----
        Map<String,Object> net = map(data.get("net"));
        if (net == null) { net = new LinkedHashMap<>(); data.put("net", net); defaulted("net"); }
        if (!net.containsKey("enabled")) { net.put("enabled", Boolean.FALSE); defaulted("net.enabled"); }

        // ----- privacy -----
        Map<String,Object> privacy = map(data.get("privacy"));
        if (privacy == null) { privacy = new LinkedHashMap<>(); data.put("privacy", privacy); defaulted("privacy"); }
        putIfAbsent(privacy, "mode", "developer", "privacy.mode");
        Map<String,Object> protectedRead = map(privacy.get("protected_read"));
        if (protectedRead == null) {
            protectedRead = new LinkedHashMap<>();
            privacy.put("protected_read", protectedRead);
            defaulted("privacy.protected_read");
        }
        putIfAbsent(protectedRead, "default_scope", "SEND_TO_MODEL_CONTEXT", "privacy.protected_read.default_scope");
        putIfAbsent(protectedRead, "allow_send_to_model", Boolean.FALSE, "privacy.protected_read.allow_send_to_model");
        putIfAbsent(protectedRead, "persist_raw_artifacts", Boolean.FALSE, "privacy.protected_read.persist_raw_artifacts");
        Map<String,Object> privacyRag = map(privacy.get("rag"));
        if (privacyRag == null) {
            privacyRag = new LinkedHashMap<>();
            privacy.put("rag", privacyRag);
            defaulted("privacy.rag");
        }
        putIfAbsent(privacyRag, "enabled_in_private_mode", Boolean.FALSE, "privacy.rag.enabled_in_private_mode");

        // ----- limits -----
        Map<String,Object> limits = map(data.get("limits"));
        if (limits == null) { limits = new LinkedHashMap<>(); data.put("limits", limits); defaulted("limits"); }

        putIfAbsent(limits, "top_k_max",          100, "limits.top_k_max");
        putIfAbsent(limits, "response_max_chars", 10 * 1024 * 1024L, "limits.response_max_chars");
        putIfAbsent(limits, "dir_depth_max",      10, "limits.dir_depth_max");
        putIfAbsent(limits, "file_bytes_max",     200_000, "limits.file_bytes_max");  // Raised to 200 KB for realistic docs
        putIfAbsent(limits, "file_lines_max",     8_000, "limits.file_lines_max");    // Raised to 8000 lines
        putIfAbsent(limits, "dir_entries_max",    1000, "limits.dir_entries_max");
        putIfAbsent(limits, "llm_timeout_ms",     300_000L, "limits.llm_timeout_ms");
        putIfAbsent(limits, "file_timeout_ms",    10_000L, "limits.file_timeout_ms");
        putIfAbsent(limits, "rate_per_sec",       10, "limits.rate_per_sec");
        putIfAbsent(limits, "llm_context_max_tokens", 8192, "limits.llm_context_max_tokens");

        // ----- ui -----
        Map<String,Object> ui = map(data.get("ui"));
        if (ui == null) { ui = new LinkedHashMap<>(); data.put("ui", ui); defaulted("ui"); }

        putIfAbsent(ui, "show_status_during_answer", true, "ui.show_status_during_answer");
        putIfAbsent(ui, "show_timing_after_answer", true, "ui.show_timing_after_answer");
        putIfAbsent(ui, "show_breakdown", false, "ui.show_breakdown");
        putIfAbsent(ui, "status_label", "Answering…", "ui.status_label");

        // ----- tools -----
        Map<String,Object> tools = map(data.get("tools"));
        if (tools == null) { tools = new LinkedHashMap<>(); data.put("tools", tools); defaulted("tools"); }
        putIfAbsent(tools, "native_calling", Boolean.TRUE, "tools.native_calling");

        // ----- session -----
        Map<String,Object> session = map(data.get("session"));
        if (session == null) { session = new LinkedHashMap<>(); data.put("session", session); defaulted("session"); }
        putIfAbsent(session, "persistence", Boolean.TRUE, "session.persistence");
        putIfAbsent(session, "auto_load", Boolean.FALSE, "session.auto_load");
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
