package dev.talos.engine.stubs.llamacpp;

import dev.talos.spi.ModelEngine;
import dev.talos.spi.types.*;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

/**
 * @deprecated Stub implementation moved to engine.stubs. Not functional.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
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
