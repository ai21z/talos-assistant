package dev.talos.spi.types;

import java.util.List;

public record EmbeddingResult(List<float[]> vectors, int dim) { }
