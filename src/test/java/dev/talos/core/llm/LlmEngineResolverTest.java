package dev.talos.core.llm;

import dev.talos.core.Config;
import dev.talos.spi.types.ChatMessage;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.TokenChunk;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Direct unit coverage for the {@link LlmEngineResolver} seam and its
 * production {@link RegistryLlmEngineResolver} implementation (CCR-017).
 *
 * <p>The end-to-end contract through {@code LlmClient} is already exercised
 * by {@code LlmClientResolverSeamTest}. This test focuses on the resolver
 * in isolation:
 * <ul>
 *   <li>The interface contract can be satisfied by a direct fake without
 *       going through {@code LlmClient}.</li>
 *   <li>{@code RegistryLlmEngineResolver} constructs, selects, and closes
 *       without requiring a live engine backend (all provider work in
 *       {@link dev.talos.core.engine.EngineRegistry} is lazy until
 *       {@code engine()} is called).</li>
 * </ul>
 *
 * <p>Deeper behavior of the registry — provider discovery, backend switch,
 * engine lifecycle — is exercised by engine-level tests
 * (e.g. {@code OllamaEngineProviderTest}). Duplicating that here would be
 * shallow restatement, which CCR-017 explicitly calls out as the risk to
 * avoid.
 */
class LlmEngineResolverTest {

    // -- Interface contract (direct, without LlmClient) ----------------------

    @Test
    void interface_contract_is_implementable_without_llm_client() throws Exception {
        FakeResolver fake = new FakeResolver();

        fake.select("ollama", "qwen2.5-coder:14b");
        assertEquals(1, fake.selectCalls.get());
        assertEquals("ollama", fake.lastBackend);
        assertEquals("qwen2.5-coder:14b", fake.lastModel);

        ChatRequest request = new ChatRequest(
                "ollama", "qwen2.5-coder:14b",
                "be helpful", "ping",
                List.of(), null,
                List.of(new ChatMessage("user", "ping")));
        try (Stream<TokenChunk> stream = fake.chatStream(request)) {
            List<TokenChunk> chunks = stream.toList();
            assertEquals(2, chunks.size());
            assertEquals("pong", chunks.get(0).text());
            assertTrue(Boolean.TRUE.equals(chunks.get(1).done()));
        }
        assertEquals(1, fake.chatCalls.get());
        assertSame(request, fake.lastRequest.get());

        fake.close();
        assertTrue(fake.closed.get());
    }

    @Test
    void auto_closeable_allows_try_with_resources() {
        FakeResolver fake = new FakeResolver();
        try (LlmEngineResolver r = fake) {
            r.select("ollama", "qwen3:8b");
        }
        assertTrue(fake.closed.get(), "try-with-resources must invoke close()");
    }

    // -- RegistryLlmEngineResolver lifecycle --------------------------------

    @Test
    void registry_resolver_constructs_with_config_without_network() {
        RegistryLlmEngineResolver resolver = new RegistryLlmEngineResolver(minimalConfig());
        try {
            // Construction must not require contacting a backend — provider
            // discovery is via ServiceLoader; engine() is lazy.
            assertNotNull(resolver);
        } finally {
            resolver.close();
        }
    }

    @Test
    void registry_resolver_select_does_not_require_live_engine() {
        RegistryLlmEngineResolver resolver = new RegistryLlmEngineResolver(minimalConfig());
        try {
            // Selecting the same backend with a new model should be a no-op
            // on the engine — no backend change means no provider.create(cfg).
            assertDoesNotThrow(() -> resolver.select("ollama", "qwen2.5-coder:14b"));
            assertDoesNotThrow(() -> resolver.select("ollama", "other-model"));
        } finally {
            resolver.close();
        }
    }

    @Test
    void registry_resolver_close_is_idempotent() {
        RegistryLlmEngineResolver resolver = new RegistryLlmEngineResolver(minimalConfig());
        assertDoesNotThrow(resolver::close);
        assertDoesNotThrow(resolver::close, "double-close must be safe");
    }

    @Test
    void registry_resolver_null_config_is_tolerated() {
        // EngineRegistry contract: null Config falls back to the normal default Config.
        RegistryLlmEngineResolver resolver = new RegistryLlmEngineResolver(null);
        try {
            assertDoesNotThrow(() -> resolver.select("ollama", "qwen2.5-coder:14b"));
        } finally {
            resolver.close();
        }
    }

    // -- Helpers ------------------------------------------------------------

    private static Config minimalConfig() {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("default_backend", "ollama");
        cfg.data.put("llm", llm);

        Map<String, Object> ollama = new LinkedHashMap<>();
        ollama.put("model", "qwen2.5-coder:14b");
        cfg.data.put("ollama", ollama);
        return cfg;
    }

    private static final class FakeResolver implements LlmEngineResolver {
        final AtomicInteger selectCalls = new AtomicInteger();
        final AtomicInteger chatCalls = new AtomicInteger();
        final AtomicReference<ChatRequest> lastRequest = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean closed =
                new java.util.concurrent.atomic.AtomicBoolean();
        volatile String lastBackend;
        volatile String lastModel;

        @Override
        public void select(String backend, String model) {
            selectCalls.incrementAndGet();
            lastBackend = backend;
            lastModel = model;
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest request) {
            chatCalls.incrementAndGet();
            lastRequest.set(request);
            return Stream.of(TokenChunk.of("pong"), TokenChunk.eos());
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}


