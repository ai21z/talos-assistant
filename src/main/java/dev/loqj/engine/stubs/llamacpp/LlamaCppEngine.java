package dev.loqj.engine.llamacpp;

import dev.loqj.spi.ModelEngine;
import dev.loqj.spi.types.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

final class LlamaCppEngine implements ModelEngine {
    @Override public String id() { return "llamacpp"; }
    @Override public Capabilities caps() { return Capabilities.of(true, true, false, 8192); }
    @Override public Health health() { return Health.down("llama.cpp stub engine (not wired)"); }

    @Override public String chat(ChatRequest req) { return "[llama.cpp stub] " + req.userPrompt; }

    @Override public Stream<TokenChunk> chatStream(ChatRequest req) {
        return Stream.of(TokenChunk.of("[llama.cpp stub] "), TokenChunk.of(req.userPrompt), TokenChunk.eos());
    }

    @Override public EmbeddingResult embed(List<String> texts) { return new EmbeddingResult(Collections.emptyList(), 0); }
}
