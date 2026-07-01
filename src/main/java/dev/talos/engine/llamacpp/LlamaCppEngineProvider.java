package dev.talos.engine.llamacpp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.talos.core.HostLocalityPolicy;
import dev.talos.safety.SafeLogFormatter;
import dev.talos.spi.EngineConfig;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;

public final class LlamaCppEngineProvider implements ModelEngineProvider {
    private static final Logger LOG = LoggerFactory.getLogger(LlamaCppEngineProvider.class);

    @Override public String id() { return LlamaCppEngine.BACKEND; }

    @Override
    public ModelEngine create(EngineConfig cfg) {
        LlamaCppConfig config = guardedConfigFrom(cfg);
        return new LlamaCppEngine(config);
    }

    @Override
    public ModelCatalog catalog(EngineConfig cfg) {
        return new LlamaCppCatalog(guardedConfigFrom(cfg), HttpClient.newHttpClient(), new ObjectMapper());
    }

    private static LlamaCppConfig guardedConfigFrom(EngineConfig cfg) {
        LlamaCppConfig config = LlamaCppConfig.from(cfg);
        String endpoint = config.baseUrl();
        HostLocalityPolicy.enforceLocalOrAllowed(
                "llama_cpp chat host",
                endpoint,
                config.allowRemote(),
                "engines.llama_cpp.allow_remote");
        if (config.allowRemote() && !HostLocalityPolicy.isLoopback(endpoint)) {
            LOG.warn("SECURITY: Using remote llama_cpp chat host: {}. Full prompts may leave this machine.",
                    SafeLogFormatter.value(endpoint));
        }
        return config;
    }
}
