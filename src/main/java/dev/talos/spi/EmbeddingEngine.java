package dev.talos.spi;

import dev.talos.spi.types.EmbeddingResult;

import java.util.List;

/**
 * SPI for engines that can generate embedding vectors.
 */
public interface EmbeddingEngine {
    EmbeddingResult embed(List<String> texts) throws Exception;
}
