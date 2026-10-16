package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.core.engine.EngineRegistry;
import dev.talos.spi.EngineConfig;
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
        return registry.prepareCapabilities();
    }

    @Override
    public Capabilities switchManagedModel(EngineConfig target, String alias) throws Exception {
        return registry.switchManagedModel(target, alias);
    }

    @Override public boolean ownsManagedModelLifecycle() { return true; }

    @Override
    public boolean selectionUnavailable() { return registry.selectionUnavailable(); }

    @Override
    public ModelIdentitySnapshot modelIdentity() {
        return registry.engine().modelIdentity();
    }

    @Override
    public Stream<TokenChunk> chatStream(ChatRequest request) throws Exception {
        return registry.chatStream(request, ProviderTransportHandoff.none(), false);
    }

    @Override
    public Stream<TokenChunk> chatStream(
            ChatRequest request,
            ProviderTransportHandoff handoff) throws Exception {
        return registry.chatStream(request, handoff, false);
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) throws Exception {
        return registry.chatStream(request, ProviderTransportHandoff.none(), true);
    }

    @Override
    public Stream<TokenChunk> chatStreamNonStreaming(
            ChatRequest request,
            ProviderTransportHandoff handoff) throws Exception {
        return registry.chatStream(request, handoff, true);
    }

    @Override
    public void close() {
        registry.close();
    }
}
