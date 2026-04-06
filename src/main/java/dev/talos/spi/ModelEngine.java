package dev.talos.spi;

import dev.talos.spi.types.*;
import java.util.List;
import java.util.stream.Stream;

public interface ModelEngine extends AutoCloseable {
    String id();
    Capabilities caps();
    Health health();

    String chat(ChatRequest req) throws Exception;
    Stream<TokenChunk> chatStream(ChatRequest req) throws Exception;
    EmbeddingResult embed(List<String> texts) throws Exception;

    @Override default void close() {}
}
