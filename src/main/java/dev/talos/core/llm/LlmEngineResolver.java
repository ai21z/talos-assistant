package dev.talos.core.llm;

import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;
import dev.talos.spi.EngineConfig;

import java.util.stream.Stream;

interface LlmEngineResolver extends AutoCloseable {

    void select(String backend, String model);

    default boolean ownsManagedModelLifecycle() { return false; }

    default Capabilities switchManagedModel(EngineConfig target, String alias) throws Exception {
        throw new UnsupportedOperationException("Managed model replacement is unavailable.");
    }

    default boolean selectionUnavailable() { return false; }

    default Capabilities capabilities() {
        return Capabilities.of(false, false, false, 0);
    }

    /** True when the resolver's capability result is a runtime enforcement contract. */
    default boolean capabilitiesAuthoritative() {
        return false;
    }

    /** Performs any bounded lifecycle work required before capabilities can be trusted. */
    default Capabilities prepareCapabilities() throws Exception {
        return capabilities();
    }

    default ModelIdentitySnapshot modelIdentity() {
        return ModelIdentitySnapshot.empty();
    }

    Stream<TokenChunk> chatStream(ChatRequest request) throws Exception;

    /**
     * Dispatch-aware seam used by production. The legacy default deliberately
     * emits no handoff fact; {@link RegistryLlmEngineResolver} overrides it and
     * delegates to the engine's physical transport boundary.
     */
    default Stream<TokenChunk> chatStream(
            ChatRequest request,
            ProviderTransportHandoff handoff) throws Exception {
        ProviderTransportHandoff.markUnobserved(handoff);
        return chatStream(request);
    }

    default Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) throws Exception {
        return chatStream(request);
    }

    /** See {@link #chatStream(ChatRequest, ProviderTransportHandoff)}. */
    default Stream<TokenChunk> chatStreamNonStreaming(
            ChatRequest request,
            ProviderTransportHandoff handoff) throws Exception {
        ProviderTransportHandoff.markUnobserved(handoff);
        return chatStreamNonStreaming(request);
    }

    @Override
    void close();
}
