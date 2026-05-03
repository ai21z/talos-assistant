package dev.talos.engine.llamacpp;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

record LlamaCppConfig(
        Mode mode,
        String serverPath,
        String modelPath,
        String hfRepo,
        String hfFile,
        String model,
        String host,
        int port,
        int context,
        boolean jinja,
        String chatTemplate,
        String chatTemplateFile,
        List<String> serverArgs
) {
    static final int DEFAULT_CONTEXT = 8192;
    static final int MIN_MANAGED_AGENT_CONTEXT = 8192;

    enum Mode {
        MANAGED,
        CONNECT_ONLY
    }

    static LlamaCppConfig from(Config cfg) {
        Map<String, Object> engines = CfgUtil.map(cfg == null ? null : cfg.data.get("engines"));
        Map<String, Object> block = CfgUtil.map(engines.get("llama_cpp"));

        Mode mode = parseMode(Objects.toString(block.getOrDefault("mode", "managed")));
        String serverPath = stringAt(block, "server_path", "");
        String modelPath = stringAt(block, "model_path", "");
        String hfRepo = stringAt(block, "hf_repo", "");
        String hfFile = stringAt(block, "hf_file", "");
        String model = stringAt(block, "model", "");
        String host = stringAt(block, "host", "http://127.0.0.1");
        int port = CfgUtil.intAt(block, "port", portFromHost(host, 8080));
        int configuredContext = CfgUtil.intAt(block, "context", DEFAULT_CONTEXT);
        int context = mode == Mode.MANAGED
                ? Math.max(configuredContext, MIN_MANAGED_AGENT_CONTEXT)
                : Math.max(256, configuredContext);
        boolean jinja = CfgUtil.boolAt(block, "jinja", true);
        String chatTemplate = stringAt(block, "chat_template", "");
        String chatTemplateFile = stringAt(block, "chat_template_file", "");
        List<String> serverArgs = CfgUtil.strList(block.get("server_args"));

        return new LlamaCppConfig(
                mode,
                serverPath,
                modelPath,
                hfRepo,
                hfFile,
                model,
                host,
                port,
                context,
                jinja,
                chatTemplate,
                chatTemplateFile,
                serverArgs);
    }

    boolean managed() {
        return mode == Mode.MANAGED;
    }

    boolean hasHfSource() {
        return hfRepo != null && !hfRepo.isBlank();
    }

    String baseUrl() {
        String h = host == null || host.isBlank() ? "http://127.0.0.1" : host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            URI uri = URI.create(h);
            if (uri.getPort() >= 0) {
                return trimTrailingSlash(h);
            }
            return trimTrailingSlash(h) + ":" + port;
        }
        return "http://" + h + ":" + port;
    }

    String listenHost() {
        String h = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            URI uri = URI.create(h);
            h = uri.getHost() == null ? h : uri.getHost();
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    String catalogFallbackModel() {
        if (model != null && !model.isBlank()) return model.trim();
        if (modelPath != null && !modelPath.isBlank()) {
            try {
                Path filename = Path.of(modelPath).getFileName();
                if (filename != null) return filename.toString();
            } catch (Exception ignored) {
                return modelPath;
            }
        }
        if (hfRepo != null && !hfRepo.isBlank()) return hfRepoName(hfRepo);
        return "local-llama-cpp";
    }

    private static String hfRepoName(String repo) {
        String value = Objects.toString(repo, "").trim();
        int slash = value.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < value.length()) {
            return value.substring(slash + 1);
        }
        return value;
    }

    private static Mode parseMode(String raw) {
        String normalized = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        return "connect_only".equals(normalized) ? Mode.CONNECT_ONLY : Mode.MANAGED;
    }

    private static String stringAt(Map<String, Object> block, String key, String fallback) {
        Object value = block.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private static int portFromHost(String host, int fallback) {
        if (host == null || host.isBlank()) return fallback;
        try {
            URI uri = URI.create(host);
            return uri.getPort() >= 0 ? uri.getPort() : fallback;
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) {
            out = out.substring(0, out.length() - 1);
        }
        return out;
    }
}
