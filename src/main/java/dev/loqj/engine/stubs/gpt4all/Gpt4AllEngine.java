package dev.loqj.engine.gpt4all;

import dev.loqj.spi.ModelEngine;
import dev.loqj.spi.types.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

final class Gpt4AllEngine implements ModelEngine {
    @Override public String id() { return "gpt4all"; }
    @Override public Capabilities caps() { return Capabilities.of(true, true, false, 8192); }
    @Override public Health health() { return Health.down("gpt4all stub engine (not wired)"); }

    @Override public String chat(ChatRequest req) { return "[gpt4all stub] " + req.userPrompt; }

    @Override public Stream<TokenChunk> chatStream(ChatRequest req) {
        return Stream.of(TokenChunk.of("[gpt4all stub] "), TokenChunk.of(req.userPrompt), TokenChunk.eos());
    }

    @Override public EmbeddingResult embed(List<String> texts) { return new EmbeddingResult(Collections.emptyList(), 0); }
}
