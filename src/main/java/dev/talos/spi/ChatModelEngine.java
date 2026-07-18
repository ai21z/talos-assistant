package dev.talos.spi;

import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ProviderTransportHandoff;
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

    /**
     * Dispatch-aware variant. Engines with a physical transport must override
     * this method and signal from the transport boundary, not from this SPI
     * invocation boundary. The default preserves compatibility with legacy
     * engines while explicitly marking their transport boundary unobserved.
     */
    default String chat(ChatRequest req, ProviderTransportHandoff handoff) throws Exception {
        ProviderTransportHandoff.markUnobserved(handoff);
        return chat(req);
    }

    /** See {@link #chat(ChatRequest, ProviderTransportHandoff)}. */
    default Stream<TokenChunk> chatStream(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        ProviderTransportHandoff.markUnobserved(handoff);
        return chatStream(req);
    }

    default Stream<TokenChunk> chatStreamNonStreaming(ChatRequest req) throws Exception {
        return Stream.of(TokenChunk.of(chat(req)), TokenChunk.eos());
    }

    /** See {@link #chat(ChatRequest, ProviderTransportHandoff)}. */
    default Stream<TokenChunk> chatStreamNonStreaming(
            ChatRequest req,
            ProviderTransportHandoff handoff) throws Exception {
        ProviderTransportHandoff.markUnobserved(handoff);
        return chatStreamNonStreaming(req);
    }
}
