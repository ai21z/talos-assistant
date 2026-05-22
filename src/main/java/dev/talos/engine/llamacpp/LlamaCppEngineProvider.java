package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.spi.EngineConfig;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;

import java.net.http.HttpClient;

public final class LlamaCppEngineProvider implements ModelEngineProvider {
    @Override public String id() { return LlamaCppEngine.BACKEND; }

    @Override
    public ModelEngine create(EngineConfig cfg) {
        LlamaCppConfig config = LlamaCppConfig.from(cfg);
        return new LlamaCppEngine(config);
    }

    @Override
    public ModelCatalog catalog(EngineConfig cfg) {
        return new LlamaCppCatalog(LlamaCppConfig.from(cfg), HttpClient.newHttpClient(), new ObjectMapper());
    }
}
