package dev.talos.core.engine;

import dev.talos.core.Config;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.EmbeddingResult;
import dev.talos.spi.types.Health;
import dev.talos.spi.types.ModelRef;
import dev.talos.spi.types.TokenChunk;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

class EngineRegistryLifecycleTest {

    @Test
    void sameBackendModelSwitchClosesCachedEngineBeforeNextMaterialization() throws Exception {
        EngineRegistry registry = new EngineRegistry(config("llama_cpp", "first-model"));
        CountingProvider provider = new CountingProvider("llama_cpp");
        install(registry, provider);

        ModelEngine first = registry.engine();
        registry.select("llama_cpp", "second-model");
        ModelEngine second = registry.engine();

        assertNotSame(first, second, "model changes must not keep returning the stale cached engine");
        assertEquals(2, provider.created.get());
        assertEquals(1, ((CountingEngine) first).closed.get());
        assertEquals(0, ((CountingEngine) second).closed.get());
    }

    @Test
    void sameBackendSameModelKeepsCachedEngine() throws Exception {
        EngineRegistry registry = new EngineRegistry(config("llama_cpp", "first-model"));
        CountingProvider provider = new CountingProvider("llama_cpp");
        install(registry, provider);

        ModelEngine first = registry.engine();
        registry.select("llama_cpp", "first-model");
        ModelEngine second = registry.engine();

        assertSame(first, second);
        assertEquals(1, provider.created.get());
        assertEquals(0, ((CountingEngine) first).closed.get());
    }

    private static Config config(String backend, String model) {
        Config cfg = new Config();
        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("default_backend", backend);
        llm.put("model", model);
        cfg.data.put("llm", llm);
        return cfg;
    }

    private static void install(EngineRegistry registry, ModelEngineProvider provider) throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, ModelEngineProvider> providers =
                (Map<String, ModelEngineProvider>) field(registry, "providers").get(registry);
        @SuppressWarnings("unchecked")
        Map<String, ModelCatalog> catalogs =
                (Map<String, ModelCatalog>) field(registry, "catalogs").get(registry);

        providers.clear();
        catalogs.clear();
        providers.put(provider.id(), provider);
        catalogs.put(provider.id(), new EmptyCatalog());
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static final class CountingProvider implements ModelEngineProvider {
        private final String id;
        private final AtomicInteger created = new AtomicInteger();

        private CountingProvider(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ModelEngine create(dev.talos.spi.EngineConfig cfg) {
            return new CountingEngine(id, created.incrementAndGet());
        }
    }

    private record EmptyCatalog() implements ModelCatalog {
        @Override
        public List<ModelRef> installed() {
            return List.of();
        }

        @Override
        public Optional<ModelRef> find(String name) {
            return Optional.empty();
        }
    }

    private static final class CountingEngine implements ModelEngine {
        private final String id;
        private final int sequence;
        private final AtomicInteger closed = new AtomicInteger();

        private CountingEngine(String id, int sequence) {
            this.id = id + "-" + sequence;
            this.sequence = sequence;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public Capabilities caps() {
            return Capabilities.of(true, true, false, 4096);
        }

        @Override
        public Health health() {
            return Health.ok("test", true);
        }

        @Override
        public String chat(ChatRequest req) {
            return "";
        }

        @Override
        public Stream<TokenChunk> chatStream(ChatRequest req) {
            return Stream.of(TokenChunk.eos());
        }

        @Override
        public EmbeddingResult embed(List<String> texts) {
            throw new UnsupportedOperationException("test engine does not embed");
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }

        @Override
        public String toString() {
            return "CountingEngine{" + sequence + '}';
        }
    }
}
