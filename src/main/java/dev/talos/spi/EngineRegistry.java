package dev.talos.spi;

import dev.talos.core.Config;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.spi.types.ModelRef;

import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers model engines via ServiceLoader and exposes:
 *  - installed(): union of all catalogs
 *  - resolve(): resolve "backend/model" or bare "model"
 *  - select(backend, model): set active pair (engine is (re)created lazily)
 *  - engine(): get/create the active engine (created via Provider.create(cfg))
 *
 * Note: Engine instances are not model-bound; the active model is carried in ChatRequest.
 */
public final class EngineRegistry implements AutoCloseable {

    private final Config cfg;
    private final Map<String, ModelEngineProvider> providers = new LinkedHashMap<>();
    private final Map<String, ModelCatalog> catalogs = new LinkedHashMap<>();

    private String activeBackend;
    private String activeModel;
    private ModelEngine activeEngine;

    public EngineRegistry(Config cfg) {
        this.cfg = (cfg == null ? new Config() : cfg);

        // Discover providers and their catalogs
        ServiceLoader<ModelEngineProvider> sl = ServiceLoader.load(ModelEngineProvider.class);
        for (ModelEngineProvider p : sl) {
            providers.put(p.id(), p);
            catalogs.put(p.id(), p.catalog(this.cfg)); // <- SPI requires catalog(Config)
        }

        // Defaults from config (mirrors how LlmClient seeds values)
        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(this.cfg);
        this.activeBackend = runtime.backend();
        this.activeModel = runtime.model();
    }

    /** Switch backend and/or model. Engine will be recreated lazily on next engine() call if backend changed. */
    public synchronized void select(String backend, String model) {
        boolean backendChanged = backend != null && !backend.isBlank() && !Objects.equals(activeBackend, backend);
        boolean modelChanged   = model   != null && !model.isBlank()   && !Objects.equals(activeModel,   model);

        if (backendChanged) {
            activeBackend = backend;
            closeEngine(); // ensure new provider.create(cfg) on next engine()
        }
        if (modelChanged) {
            activeModel = model;
            // engine stays; model is carried in ChatRequest
        }
    }

    /** Active engine for the selected backend. Lazily creates via Provider.create(cfg). */
    public synchronized ModelEngine engine() {
        ensureDefaults();
        if (activeEngine == null) {
            ModelEngineProvider p = providers.get(activeBackend);
            if (p == null) throw new IllegalStateException("No ModelEngineProvider for backend: " + activeBackend);
            activeEngine = p.create(this.cfg); // <- SPI requires create(Config)
        }
        return activeEngine;
    }

    /** Catalog for a specific backend (may be null if none). */
    public synchronized ModelCatalog catalog(String backend) {
        return catalogs.get(backend);
    }

    /** Composite catalog (union). */
    public ModelCatalog compositeCatalog() {
        return new ModelCatalog() {
            @Override public List<ModelRef> installed() { return EngineRegistry.this.installed(); }
            @Override public Optional<ModelRef> find(String name) { return EngineRegistry.this.resolve(name); }
        };
    }

    /** All installed models across backends, backend/name sorted. */
    public List<ModelRef> installed() {
        return providers.entrySet().stream()
                .flatMap(e -> {
                    String backend = e.getKey();
                    ModelCatalog c = catalogs.get(backend);
                    if (c == null) return Stream.<ModelRef>empty();
                    return c.installed().stream()
                            .map(m -> m.backend() == null
                                    ? new ModelRef(backend, m.name(), m.dims(), m.note())
                                    : m);
                })
                .sorted(Comparator.comparing(ModelRef::backend).thenComparing(ModelRef::name))
                .collect(Collectors.toList());
    }

    /** Resolve "backend/model" or bare "model" by scanning catalogs. */
    public Optional<ModelRef> resolve(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String needle = s.trim();

        // Qualified form: backend/model
        if (needle.contains("/")) {
            String[] parts = needle.split("/", 2);
            if (parts.length != 2) return Optional.empty();
            ModelCatalog c = catalogs.get(parts[0]);
            if (c == null) return Optional.empty();
            return c.find(parts[1]).map(m -> m.backend() == null
                    ? new ModelRef(parts[0], m.name(), m.dims(), m.note())
                    : m);
        }

        // Bare model: first backend that has it
        return providers.entrySet().stream()
                .map(e -> {
                    ModelCatalog c = catalogs.get(e.getKey());
                    return (c == null) ? Optional.<ModelRef>empty()
                            : c.find(needle).map(m -> m.backend() == null
                            ? new ModelRef(e.getKey(), m.name(), m.dims(), m.note())
                            : m);
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private static Map<String, Object> map(Object o) {
        if (o instanceof Map<?, ?> m) {
            @SuppressWarnings("unchecked")
            Map<String, Object> x = (Map<String, Object>) (Map<?, ?>) m;
            return x;
        }
        return Map.of();
    }

    private void ensureDefaults() {
        if (activeBackend == null || activeBackend.isBlank()) activeBackend = "llama_cpp";
        if (activeModel == null || activeModel.isBlank()) {
            activeModel = EngineRuntimeConfig.from(cfg).model();
        }
    }

    private synchronized void closeEngine() {
        if (activeEngine instanceof AutoCloseable ac) {
            try { ac.close(); } catch (Exception ignore) {}
        }
        activeEngine = null;
    }

    @Override public synchronized void close() { closeEngine(); }
}
