package dev.talos.engine.ollama;

import dev.talos.core.CfgUtil;
import dev.talos.core.Config;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;

import java.util.Map;

public final class OllamaEngineProvider implements ModelEngineProvider {

    private static final String BACKEND = "ollama";

    private static String hostFrom(Config cfg) {
        // env first
        String env = System.getenv("TALOS_OLLAMA_HOST");
        if (env != null && !env.isBlank()) return env.trim();

        // then config
        Map<String,Object> ollama = CfgUtil.map(cfg == null ? null : cfg.data.get("ollama"));
        Object v = ollama.get("host");
        if (v != null) return String.valueOf(v);

        // fallback
        return "http://127.0.0.1:11434";
    }

    private static String defaultModelFrom(Config cfg) {
        String env = System.getenv("TALOS_OLLAMA_MODEL");
        if (env != null && !env.isBlank()) return env.trim();

        Map<String,Object> ollama = CfgUtil.map(cfg == null ? null : cfg.data.get("ollama"));
        Object v = ollama.get("model");
        if (v != null) return String.valueOf(v);

        return "qwen3:8b";
    }

    @Override public String id() { return BACKEND; }

    @Override public ModelEngine create(Config cfg) {
        // Engine is not model-bound; ChatRequest carries the model.
        return new OllamaEngine(hostFrom(cfg), defaultModelFrom(cfg));
    }

    @Override public ModelCatalog catalog(Config cfg) {
        return new OllamaCatalog(hostFrom(cfg));
    }
}
