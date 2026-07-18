package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderTransportHandoff;
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
    public boolean capabilitiesAuthoritative() {
        return true;
    }

    @Override
    public Capabilities prepareCapabilities() throws Exception {
        return registry.engine().prepareCapabilities();
    }

    @Override
    public ModelIdentitySnapshot modelIdentity() {
        return registry.engine().modelIdentity();
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest request) throws Exception {
        return registry.engine().chatStream(request);
    }

    @Override
    public Stream<TokenChunk> chatStream(
            ChatRequest request,
            ProviderTransportHandoff handoff) throws Exception {
        return registry.engine().chatStream(request, handoff);
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) throws Exception {
        return registry.engine().chatStreamNonStreaming(request);
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(
            ChatRequest request,
            ProviderTransportHandoff handoff) throws Exception {
        return registry.engine().chatStreamNonStreaming(request, handoff);
    }

    @Override
    public void close() {
        registry.close();
    }
}
