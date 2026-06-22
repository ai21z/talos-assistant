package dev.talos.core.engine;

import dev.talos.core.Config;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngineProvider;
import dev.talos.spi.types.ModelRef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRegistryInstalledCatalogPolicyTest {

    @Test
    void defaultInstalledListingSkipsOllamaWhenActiveBackendIsManagedLlamaCpp() {
        assertFalse(EngineRegistry.includeCatalogInDefaultInstalled("ollama", "llama_cpp"),
                "default /models must not probe or spawn Ollama while managed llama.cpp is active");
    }

    @Test
    void explicitOllamaBackendAllowsOllamaCatalog() {
        assertTrue(EngineRegistry.includeCatalogInDefaultInstalled("ollama", "ollama"));
    }

    @Test
    void managedAndOtherBackendsRemainVisibleByDefault() {
        assertTrue(EngineRegistry.includeCatalogInDefaultInstalled("llama_cpp", "llama_cpp"));
        assertTrue(EngineRegistry.includeCatalogInDefaultInstalled("compat", "llama_cpp"));
    }

    @Test
    void bareResolveSkipsOllamaCatalogWhenActiveBackendIsManagedLlamaCpp() throws Exception {
        EngineRegistry registry = new EngineRegistry(new Config());
        registry.select("llama_cpp", "talos-agent");

        CountingCatalog llamaCpp = new CountingCatalog();
        CountingCatalog ollama = new CountingCatalog(ModelRef.of("ollama", "qwen2.5-coder:14b"));
        CountingCatalog compat = new CountingCatalog(ModelRef.of("compat", "qwen2.5-coder:14b"));
        installCatalogs(registry, orderedCatalogs(
                "llama_cpp", llamaCpp,
                "ollama", ollama,
                "compat", compat));

        Optional<ModelRef> resolved = registry.resolve("qwen2.5-coder:14b");

        assertTrue(resolved.isPresent());
        assertEquals("compat", resolved.get().backend(),
                "bare resolve should skip the Ollama catalog and continue to non-Ollama catalogs");
        assertEquals(1, llamaCpp.findCalls);
        assertEquals(0, ollama.findCalls,
                "bare resolve must not probe or spawn Ollama while active backend is managed llama.cpp");
        assertEquals(1, compat.findCalls);
    }

    @Test
    void qualifiedOllamaResolveStillUsesOllamaCatalog() throws Exception {
        EngineRegistry registry = new EngineRegistry(new Config());
        registry.select("llama_cpp", "talos-agent");

        CountingCatalog ollama = new CountingCatalog(ModelRef.of("ollama", "qwen2.5-coder:14b"));
        installCatalogs(registry, orderedCatalogs("ollama", ollama));

        Optional<ModelRef> resolved = registry.resolve("ollama/qwen2.5-coder:14b");

        assertTrue(resolved.isPresent());
        assertEquals("ollama", resolved.get().backend());
        assertEquals(1, ollama.findCalls,
                "qualified ollama/model is an explicit opt-in and must remain supported");
    }

    @Test
    void bareResolveIncludesOllamaWhenActiveBackendIsOllama() throws Exception {
        EngineRegistry registry = new EngineRegistry(new Config());
        registry.select("ollama", "qwen2.5-coder:14b");

        CountingCatalog ollama = new CountingCatalog(ModelRef.of("ollama", "qwen2.5-coder:14b"));
        installCatalogs(registry, orderedCatalogs("ollama", ollama));

        Optional<ModelRef> resolved = registry.resolve("qwen2.5-coder:14b");

        assertTrue(resolved.isPresent());
        assertEquals("ollama", resolved.get().backend());
        assertEquals(1, ollama.findCalls,
                "legacy bare-name Ollama resolution remains available when Ollama is the active backend");
    }

    private static void installCatalogs(EngineRegistry registry, Map<String, ModelCatalog> catalogs)
            throws Exception {
        @SuppressWarnings("unchecked")
        Map<String, ModelEngineProvider> providersMap =
                (Map<String, ModelEngineProvider>) field(registry, "providers").get(registry);
        @SuppressWarnings("unchecked")
        Map<String, ModelCatalog> catalogsMap =
                (Map<String, ModelCatalog>) field(registry, "catalogs").get(registry);

        providersMap.clear();
        catalogsMap.clear();

        for (var entry : catalogs.entrySet()) {
            providersMap.put(entry.getKey(), new StubProvider(entry.getKey()));
            catalogsMap.put(entry.getKey(), entry.getValue());
        }
    }

    private static Map<String, ModelCatalog> orderedCatalogs(Object... entries) {
        Map<String, ModelCatalog> catalogs = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            catalogs.put((String) entries[i], (ModelCatalog) entries[i + 1]);
        }
        return catalogs;
    }

    private static Field field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private record StubProvider(String id) implements ModelEngineProvider {
    }

    private static final class CountingCatalog implements ModelCatalog {
        private final Map<String, ModelRef> refs = new LinkedHashMap<>();
        private int findCalls;

        private CountingCatalog(ModelRef... refs) {
            for (ModelRef ref : refs) {
                this.refs.put(ref.name(), ref);
            }
        }

        @Override
        public List<ModelRef> installed() {
            return List.copyOf(refs.values());
        }

        @Override
        public Optional<ModelRef> find(String name) {
            findCalls++;
            return Optional.ofNullable(refs.get(name));
        }
    }
}
