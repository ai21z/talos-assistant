package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.Config;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;

import java.net.http.HttpClient;

public final class LlamaCppEngineProvider implements ModelEngineProvider {
    @Override public String id() { return LlamaCppEngine.BACKEND; }

    @Override
    public ModelEngine create(Config cfg) {
        LlamaCppConfig config = LlamaCppConfig.from(cfg);
        return new LlamaCppEngine(config);
    }

    @Override
    public ModelCatalog catalog(Config cfg) {
        return new LlamaCppCatalog(LlamaCppConfig.from(cfg), HttpClient.newHttpClient(), new ObjectMapper());
    }
}
