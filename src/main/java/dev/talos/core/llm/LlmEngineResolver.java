package dev.talos.core.llm;

import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;

import java.util.stream.Stream;

interface LlmEngineResolver extends AutoCloseable {

    void select(String backend, String model);

    Stream<TokenChunk> chatStream(ChatRequest request) throws Exception;

    @Override
    void close();
}
