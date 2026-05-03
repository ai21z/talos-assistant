package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.EngineRegistry;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;

import java.util.stream.Stream;

final class RegistryLlmEngineResolver implements LlmEngineResolver {

    private final EngineRegistry registry;

    RegistryLlmEngineResolver(Config cfg) {
        this.registry = new EngineRegistry(cfg);
    }

    @Override
    public void select(String backend, String model) {
        registry.select(backend, model);
    }

    @Override
    public Capabilities capabilities() {
        return registry.engine().caps();
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest request) throws Exception {
        return registry.engine().chatStream(request);
    }

    @Override
    public void close() {
        registry.close();
    }
}
