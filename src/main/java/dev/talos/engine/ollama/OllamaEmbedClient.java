package dev.talos.engine.ollama;

import dev.talos.spi.types.EmbeddingResult;

import java.util.Collections;
import java.util.List;

final class OllamaEmbedClient {
    EmbeddingResult embed(List<String> texts) {
        return new EmbeddingResult(Collections.emptyList(), 0);
    }
}
