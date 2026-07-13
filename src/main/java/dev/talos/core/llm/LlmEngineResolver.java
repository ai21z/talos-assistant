package dev.talos.core.llm;

import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.TokenChunk;

import java.util.stream.Stream;

interface LlmEngineResolver extends AutoCloseable {

    void select(String backend, String model);

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

    default Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) throws Exception {
        return chatStream(request);
    }

    @Override
    void close();
}
