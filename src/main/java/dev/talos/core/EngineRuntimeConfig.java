package dev.talos.core;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Backend-neutral view of the active chat and embedding runtime config. */
public record EngineRuntimeConfig(
        String backend,
        String model,
        String displayModel,
        String modelSourceLabel,
        String hostLabel,
        String embeddingProvider,
        String embeddingModel,
        String embeddingLabel,
        String policyLabel
) {
    /** The one model backend Talos runs. */
    public static final String MANAGED_BACKEND = "llama_cpp";

    /** User-facing refusal for any other backend, naming every place a backend can come from. */
    public static String unsupportedBackendMessage(String backend) {
        return "The model backend '" + backend + "' is not supported. Talos runs managed llama.cpp only. "
                + "Check llm.default_backend, llm.model, TALOS_BACKEND and TALOS_LLM_BACKEND, "
                + "or run talos setup models.";
    }

    public static EngineRuntimeConfig from(Config cfg) {
        return from(cfg, "");
    }

    public static EngineRuntimeConfig fromActiveModel(Config cfg, String activeModel) {
        return from(cfg, activeModel);
    }

    private static EngineRuntimeConfig from(Config cfg, String activeModelOverride) {
        Config safeCfg = cfg == null ? new Config() : cfg;
        if (!safeCfg.data.containsKey("llm")
                && !safeCfg.data.containsKey("engines")) {
            return new EngineRuntimeConfig(
                    "unknown",
                    "unknown",
                    "unknown",
                    "",
                    "unknown",
                    "disabled",
                    "unknown",
                    "disabled/unknown",
                    "network on; local engine only (unknown)");
        }
        Map<String, Object> llm = CfgUtil.map(safeCfg.data.get("llm"));
        String activeModel = firstNonBlank(activeModelOverride);
        String activeBackend = "";
        if (activeModel.contains("/") && !activeModel.startsWith("/") && !activeModel.endsWith("/")) {
            String[] parts = activeModel.split("/", 2);
            if (parts.length == 2 && !parts[0].isBlank() && !parts[1].isBlank()) {
                activeBackend = parts[0];
                activeModel = parts[1];
            }
        }
        String backend = firstNonBlank(
                activeBackend,
                env("TALOS_BACKEND"),
                env("TALOS_LLM_BACKEND"),
                stringAt(llm, "default_backend", "llama_cpp"));

        String model = firstNonBlank(
                activeModel,
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
        String embedProvider = firstNonBlank(stringAt(embed, "provider", ""), "disabled");
        String embedModel = firstNonBlank(stringAt(embed, "model", ""), "none");

        String network = networkEnabled(safeCfg) ? "network on" : "network off";
        String policy = network + "; local engine only (" + backend + ")";

        return new EngineRuntimeConfig(
                backend,
                model,
                "unknown".equals(model) ? "unknown" : backend + "/" + model,
                modelSourceLabel(safeCfg, backend),
                hostForBackend(safeCfg, backend),
                embedProvider,
                embedModel,
                embedProvider + "/" + embedModel,
                policy);
    }

    private static String backendModel(Config cfg, String backend) {
        if ("llama_cpp".equals(backend)) {
            Map<String, Object> engines = CfgUtil.map(cfg.data.get("engines"));
            Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
            String model = stringAt(llama, "model", "");
            if (!model.isBlank()) return model;
            String hfRepo = stringAt(llama, "hf_repo", "");
            if (!hfRepo.isBlank()) return hfRepoName(hfRepo);
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

    private static String hfRepoName(String repo) {
        String value = Objects.toString(repo, "").trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            return value.substring(slash + 1);
        }
        return value;
    }

    private static String hostForBackend(Config cfg, String backend) {
        if ("llama_cpp".equals(backend)) {
            Map<String, Object> engines = CfgUtil.map(cfg.data.get("engines"));
            Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
            String host = stringAt(llama, "host", "http://127.0.0.1");
            int port = CfgUtil.intAt(llama, "port", 8080);
            return withPort(host, port);
        }
        return "unknown";
    }

    private static String modelSourceLabel(Config cfg, String backend) {
        if (!"llama_cpp".equals(backend)) {
            return "";
        }
        Map<String, Object> engines = CfgUtil.map(cfg.data.get("engines"));
        Map<String, Object> llama = CfgUtil.map(engines.get("llama_cpp"));
        String modelPath = stringAt(llama, "model_path", "");
        if (!modelPath.isBlank()) {
            try {
                Path filename = Path.of(modelPath).getFileName();
                if (filename != null) return filename.toString();
            } catch (Exception ignored) {
                return "";
            }
        }
        return stringAt(llama, "hf_file", "");
    }

    private static String withPort(String host, int port) {
        String h = Objects.toString(host, "").trim();
        if (h.isBlank()) h = "http://127.0.0.1";
        if (h.matches("^https?://[^/]+:\\d+/?$")) return trimTrailingSlash(h);
        return trimTrailingSlash(h) + ":" + port;
    }

    private static boolean networkEnabled(Config cfg) {
        return cfg.view().net().enabled();
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
