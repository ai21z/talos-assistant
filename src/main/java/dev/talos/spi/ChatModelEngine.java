package dev.talos.spi;

import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;

import java.util.stream.Stream;

/**
 * SPI for chat-capable model engines.
 *
 * <p>Separates conversational generation from embedding generation so callers
 * can depend on the narrower capability they actually need.
 */
public interface ChatModelEngine {
    String chat(ChatRequest req) throws Exception;
    Stream<TokenChunk> chatStream(ChatRequest req) throws Exception;

    default Stream<TokenChunk> chatStreamNonStreaming(ChatRequest req) throws Exception {
        return Stream.of(TokenChunk.of(chat(req)), TokenChunk.eos());
    }
}
