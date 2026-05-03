package dev.talos.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Backend-neutral view of the active chat and embedding runtime config. */
public record EngineRuntimeConfig(
        String backend,
        String model,
        String displayModel,
        String hostLabel,
        String embeddingProvider,
        String embeddingModel,
        String embeddingLabel,
        String policyLabel
) {
    public static EngineRuntimeConfig from(Config cfg) {
        Config safeCfg = cfg == null ? new Config() : cfg;
        if (!safeCfg.data.containsKey("llm")
                && !safeCfg.data.containsKey("engines")
                && !safeCfg.data.containsKey("ollama")) {
            return new EngineRuntimeConfig(
                    "unknown",
                    "unknown",
                    "unknown",
                    "unknown",
                    "disabled",
                    "unknown",
                    "disabled/unknown",
                    "network on; local engine only (unknown)");
        }
        Map<String, Object> llm = CfgUtil.map(safeCfg.data.get("llm"));
        String backend = firstNonBlank(
                env("TALOS_BACKEND"),
                env("TALOS_LLM_BACKEND"),
                stringAt(llm, "default_backend", "llama_cpp"));

        String model = firstNonBlank(
                env("TALOS_MODEL"),
                env("TALOS_LLM_MODEL"),
                stringAt(llm, "model", ""),
                backendModel(safeCfg, backend),
                "unknown");

        if (model.contains("/") && !model.startsWith("/") && !model.endsWith("/")) {
            String[] parts = model.split("/", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                backend = parts[0];
                model = parts[1];
            }
        }

        Map<String, Object> embed = CfgUtil.map(safeCfg.data.get("embed"));
        String embedProvider = firstNonBlank(
                stringAt(embed, "provider", ""),
                "ollama".equals(backend) ? "ollama" : "compat");
        String embedModel = firstNonBlank(
                stringAt(embed, "model", ""),
                "ollama".equals(embedProvider)
                        ? stringAt(CfgUtil.map(safeCfg.data.get("ollama")), "embed", "bge-m3")
                        : "talos-embed");

        String network = networkEnabled(safeCfg) ? "network on" : "network off";
        String policy = "ollama".equals(backend)
                ? network + "; " + ollamaPolicy(safeCfg)
                : network + "; local engine only (" + backend + ")";

        return new EngineRuntimeConfig(
                backend,
                model,
                "unknown".equals(model) ? "unknown" : backend + "/" + model,
                hostForBackend(safeCfg, backend),
                embedProvider,
                embedModel,
                embedProvider + "/" + embedModel,
                policy);
    }

    private static String backendModel(Config cfg, String backend) {
        if ("ollama".equals(backend)) {
            return firstNonBlank(
                    env("TALOS_OLLAMA_MODEL"),
                    stringAt(CfgUtil.map(cfg.data.get("ollama")), "model", "qwen2.5-coder:14b"));
        }
        if ("llama_cpp".equals(backend)) {
            Map<String, Object> engines = CfgUtil.map(cfg.data.get("engines"));
            Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
            String model = stringAt(llama, "model", "");
            if (!model.isBlank()) return model;
            String modelPath = stringAt(llama, "model_path", "");
            if (!modelPath.isBlank()) {
                try {
                    Path filename = Path.of(modelPath).getFileName();
                    if (filename != null) return filename.toString();
                } catch (Exception ignored) {
                    return modelPath;
                }
            }
            return "talos-agent";
        }
        return "";
    }

    private static String hostForBackend(Config cfg, String backend) {
        if ("ollama".equals(backend)) {
            return firstNonBlank(
                    env("TALOS_ENGINE_HOST"),
                    env("TALOS_OLLAMA_HOST"),
                    stringAt(CfgUtil.map(cfg.data.get("ollama")), "host", "http://127.0.0.1:11434"));
        }
        if ("llama_cpp".equals(backend)) {
            Map<String, Object> engines = CfgUtil.map(cfg.data.get("engines"));
            Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
            String host = stringAt(llama, "host", "http://127.0.0.1");
            int port = CfgUtil.intAt(llama, "port", 8080);
            return withPort(host, port);
        }
        return "unknown";
    }

    private static String withPort(String host, int port) {
        String h = Objects.toString(host, "").trim();
        if (h.isBlank()) h = "http://127.0.0.1";
        if (h.matches("^https?://[^/]+:\\d+/?$")) return trimTrailingSlash(h);
        return trimTrailingSlash(h) + ":" + port;
    }

    private static boolean networkEnabled(Config cfg) {
        Map<String, Object> net = CfgUtil.map(cfg.data.get("net"));
        return !(net.get("enabled") instanceof Boolean b) || b;
    }

    private static String ollamaPolicy(Config cfg) {
        Map<String, Object> ollama = CfgUtil.map(cfg.data.get("ollama"));
        boolean remoteAllowed = ollama.get("allow_remote") instanceof Boolean b && b;
        return remoteAllowed ? "remote Ollama allowed" : "local Ollama only";
    }

    private static String stringAt(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) return value.trim();
        }
        return "";
    }

    private static String env(String name) {
        String value = System.getenv(name);
        return value == null ? "" : value.trim();
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
