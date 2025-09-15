package dev.loqj.spi.types;

import java.util.List;

public record EmbeddingResult(List<float[]> vectors, int dim) { }
