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

    @Override
    void close();
}
