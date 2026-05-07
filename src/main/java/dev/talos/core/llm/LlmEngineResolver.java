package dev.talos.core.llm;

import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.TokenChunk;

import java.util.stream.Stream;

interface LlmEngineResolver extends AutoCloseable {

    void select(String backend, String model);

    default Capabilities capabilities() {
        return Capabilities.of(false, false, false, 0);
    }

    Stream<TokenChunk> chatStream(ChatRequest request) throws Exception;

    default Stream<TokenChunk> chatStreamNonStreaming(ChatRequest request) throws Exception {
        return chatStream(request);
    }

    @Override
    void close();
}
