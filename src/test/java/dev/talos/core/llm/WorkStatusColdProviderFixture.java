package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

/** Lifecycle and generation calls are counted without a real engine or process. */
public record WorkStatusColdProviderFixture(LlmClient client, AtomicInteger preparations,
                                           AtomicInteger generations) {
    public static WorkStatusColdProviderFixture create() {
        Config config = new Config(null);
        config.data.put("llm", Map.of("transport", "engine", "default_backend", "llama_cpp"));
        var preparations = new AtomicInteger();
        var generations = new AtomicInteger();
        var resolver = new LlmEngineResolver() {
            @Override public void select(String backend, String model) {}
            @Override public boolean capabilitiesAuthoritative() { return true; }
            @Override public Capabilities capabilities() { return Capabilities.of(true, false, false, 16384, true); }
            @Override public Capabilities prepareCapabilities() {
                preparations.incrementAndGet();
                throw new AssertionError("Retained status must not prepare or start a provider");
            }
            @Override public Stream<TokenChunk> chatStream(ChatRequest request) {
                generations.incrementAndGet();
                throw new AssertionError("Retained status must not generate");
            }
            @Override public void close() {}
        };
        return new WorkStatusColdProviderFixture(new LlmClient(config, resolver), preparations, generations);
    }
}
