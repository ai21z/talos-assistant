package dev.talos.core.embed;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Config view for an optional Talos-managed llama.cpp embedding server. */
public record ManagedLlamaCppEmbeddingConfig(
        boolean enabled,
        String serverPath,
        String modelPath,
        String hfRepo,
        String hfFile,
        String hfCacheDir,
        String model,
        String host,
        int port,
        String pooling,
        List<String> serverArgs
) {
    static final int DEFAULT_PORT = 18116;

    public ManagedLlamaCppEmbeddingConfig {
        serverPath = Objects.toString(serverPath, "").trim();
        modelPath = Objects.toString(modelPath, "").trim();
        hfRepo = Objects.toString(hfRepo, "").trim();
        hfFile = Objects.toString(hfFile, "").trim();
        hfCacheDir = Objects.toString(hfCacheDir, "").trim();
        model = Objects.toString(model, "").trim();
        host = Objects.toString(host, "").trim();
        if (host.isBlank()) host = "http://127.0.0.1";
        port = Math.max(1, port);
        pooling = Objects.toString(pooling, "").trim();
        if (pooling.isBlank()) pooling = "mean";
        serverArgs = serverArgs == null ? List.of() : List.copyOf(serverArgs);
    }

    public static ManagedLlamaCppEmbeddingConfig from(Config cfg) {
        Map<String, Object> root = cfg == null ? Map.of() : cfg.data;
        Map<String, Object> embed = CfgUtil.map(root.get("embed"));
        Map<String, Object> managed = CfgUtil.map(embed.get("managed"));
        Map<String, Object> engines = CfgUtil.map(root.get("engines"));
        Map<String, Object> llamaCpp = CfgUtil.map(engines.get("llama_cpp"));

        boolean enabled = CfgUtil.boolAt(managed, "enabled", false);
        String serverPath = stringAt(managed, "server_path", stringAt(llamaCpp, "server_path", ""));
        String modelPath = stringAt(managed, "model_path", "");
        String hfRepo = stringAt(managed, "hf_repo", "");
        String hfFile = stringAt(managed, "hf_file", "");
        String hfCacheDir = stringAt(managed, "hf_cache_dir", stringAt(llamaCpp, "hf_cache_dir", ""));
        String model = stringAt(managed, "model", stringAt(embed, "model", "bge-m3"));
        String host = stringAt(managed, "host", "http://127.0.0.1");
        int port = CfgUtil.intAt(managed, "port", DEFAULT_PORT);
        String pooling = stringAt(managed, "pooling", "mean");
        List<String> serverArgs = CfgUtil.strList(managed.get("server_args"));

        return new ManagedLlamaCppEmbeddingConfig(
                enabled,
                serverPath,
                modelPath,
                hfRepo,
                hfFile,
                hfCacheDir,
                model,
                host,
                port,
                pooling,
                serverArgs);
    }

    public boolean hasHfSource() {
        return !hfRepo.isBlank();
    }

    public String baseUrl() {
        String h = host == null || host.isBlank() ? "http://127.0.0.1" : host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            URI uri = URI.create(h);
            if (uri.getPort() >= 0) return trimTrailingSlash(h);
            return trimTrailingSlash(h) + ":" + port;
        }
        return "http://" + h + ":" + port;
    }

    public String listenHost() {
        String h = host == null || host.isBlank() ? "127.0.0.1" : host.trim();
        if (h.startsWith("http://") || h.startsWith("https://")) {
            URI uri = URI.create(h);
            h = uri.getHost() == null ? h : uri.getHost();
        }
        int colon = h.indexOf(':');
        return colon >= 0 ? h.substring(0, colon) : h;
    }

    public String catalogFallbackModel() {
        if (!model.isBlank()) return model;
        if (!modelPath.isBlank()) {
            try {
                Path filename = Path.of(modelPath).getFileName();
                if (filename != null) return filename.toString();
            } catch (Exception ignored) {
                return modelPath;
            }
        }
        if (!hfRepo.isBlank()) {
            int slash = hfRepo.lastIndexOf('/');
            return slash >= 0 && slash + 1 < hfRepo.length() ? hfRepo.substring(slash + 1) : hfRepo;
        }
        return "local-embedding";
    }

    private static String stringAt(Map<String, Object> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) return fallback;
        String text = String.valueOf(value).trim();
        return text.isBlank() ? fallback : text;
    }

    private static String trimTrailingSlash(String value) {
        String out = value == null ? "" : value.trim();
        while (out.endsWith("/")) out = out.substring(0, out.length() - 1);
        return out;
    }
}
