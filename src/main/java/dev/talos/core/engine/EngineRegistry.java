package dev.talos.core.engine;

import dev.talos.core.Config;
import dev.talos.core.CfgUtil;
import dev.talos.core.EngineRuntimeConfig;
import dev.talos.spi.EngineConfig;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;
import dev.talos.spi.types.ModelRef;
import dev.talos.spi.types.Capabilities;
import dev.talos.spi.types.ChatRequest;
import dev.talos.spi.types.ModelIdentitySnapshot;
import dev.talos.spi.types.ProviderTransportHandoff;
import dev.talos.spi.types.TokenChunk;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.ServiceLoader;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Discovers model engines via ServiceLoader and owns active engine selection.
 *
 * <p>This is core orchestration over SPI providers, not an SPI contract.
 */
public final class EngineRegistry implements AutoCloseable {

    private final Config cfg;
    private final Map<String, ModelEngineProvider> providers = new LinkedHashMap<>();
    private final Map<String, ModelCatalog> catalogs = new LinkedHashMap<>();

    private String activeBackend;
    private String activeModel;
    private ModelEngine activeEngine;
    private EngineConfig engineConfig;
    private boolean selectionUnavailable;
    private int activeRequests;

    public EngineRegistry(Config cfg) {
        this.cfg = (cfg == null ? new Config() : cfg);
        this.engineConfig = this.cfg;

        ServiceLoader<ModelEngineProvider> sl = ServiceLoader.load(ModelEngineProvider.class);
        for (ModelEngineProvider p : sl) {
            providers.put(p.id(), p);
            catalogs.put(p.id(), p.catalog(this.cfg));
        }

        EngineRuntimeConfig runtime = EngineRuntimeConfig.from(this.cfg);
        this.activeBackend = runtime.backend();
        this.activeModel = runtime.model();
    }

    /** Switch backend and/or model. Engine will be recreated lazily on next engine() call if identity changed. */
    public synchronized void select(String backend, String model) {
        boolean backendChanged = backend != null && !backend.isBlank() && !Objects.equals(activeBackend, backend);
        boolean modelChanged   = model   != null && !model.isBlank()   && !Objects.equals(activeModel,   model);

        if (backendChanged || modelChanged) {
            requireIdle();
            if (selectionUnavailable) throw unavailable();
            selectionUnavailable = true;
            closeEngine();
        }
        if (backendChanged) activeBackend = backend;
        if (modelChanged) activeModel = model;
        if (backendChanged || modelChanged) selectionUnavailable = false;
    }

    /** Active engine for the selected backend. Lazily creates via Provider.create(cfg). */
    public synchronized ModelEngine engine() {
        if (selectionUnavailable) throw unavailable();
        ensureDefaults();
        if (activeEngine == null) {
            ModelEngineProvider p = providers.get(activeBackend);
            if (p == null) throw new IllegalStateException("No ModelEngineProvider for backend: " + activeBackend);
            activeEngine = p.create(engineConfig);
        }
        return activeEngine;
    }

    /** The selected identity is committed only after owned shutdown and target preparation. */
    public synchronized Capabilities switchManagedModel(EngineConfig target, String alias) throws Exception {
        requireIdle();
        Objects.requireNonNull(target, "target configuration");
        Map<String, Object> targetBlock = CfgUtil.map(CfgUtil.map(target.data().get("engines")).get("llama_cpp"));
        Map<String, Object> previousBlock = CfgUtil.map(CfgUtil.map(engineConfig.data().get("engines")).get("llama_cpp"));
        if (!"managed".equals(previousBlock.getOrDefault("mode", "managed"))
                || !"managed".equals(targetBlock.get("mode"))
                || alias == null || !alias.equals(targetBlock.get("model"))) {
            throw new IllegalArgumentException("Managed model switching requires an owned managed llama.cpp configuration.");
        }
        ModelEngineProvider provider = providers.get("llama_cpp");
        if (provider == null) throw new IllegalStateException("The managed llama.cpp provider is unavailable.");
        ModelEngine candidate = provider.create(target);
        selectionUnavailable = true;
        try {
            closeEngine();
        } catch (RuntimeException | Error stopFailure) {
            try { candidate.close(); } catch (RuntimeException cleanupFailure) { stopFailure.addSuppressed(cleanupFailure); }
            throw stopFailure;
        }
        activeEngine = candidate;
        try {
            Capabilities capabilities = candidate.prepareCapabilities();
            requireSelectedIdentity(candidate.modelIdentity(), alias);
            if (capabilities == null || !capabilities.chat()) {
                throw new IllegalStateException("The selected model is not available for chat.");
            }
            engineConfig = target;
            activeBackend = "llama_cpp";
            activeModel = alias;
            selectionUnavailable = false;
            return capabilities;
        } catch (Exception | Error failure) {
            try { closeEngine(); } catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static void requireSelectedIdentity(ModelIdentitySnapshot identity, String alias) {
        if (identity == null || !identity.chatAvailable()
                || !"llama_cpp".equals(identity.backend())
                || !alias.equals(identity.configuredModel())
                || !"AVAILABLE".equals(identity.propsState())
                || !"MATCHED".equals(identity.servedIdentityState())
                || "REFUSED".equals(identity.resolution())) {
            throw new IllegalStateException("The selected model identity was not verified. No model is available until an explicit successful selection.");
        }
    }

    public synchronized boolean selectionUnavailable() { return selectionUnavailable; }

    public synchronized Capabilities prepareCapabilities() throws Exception {
        return engine().prepareCapabilities();
    }

    /** A live transport retains the selected engine until its stream is closed. */
    public Stream<TokenChunk> chatStream(ChatRequest request, ProviderTransportHandoff handoff,
                                        boolean buffered) throws Exception {
        ModelEngine selected;
        synchronized (this) {
            selected = engine();
            activeRequests++;
        }
        try {
            Stream<TokenChunk> stream = buffered
                    ? selected.chatStreamNonStreaming(request, handoff)
                    : selected.chatStream(request, handoff);
            return Objects.requireNonNull(stream, "provider stream").onClose(this::releaseRequest);
        } catch (Exception | Error failure) {
            releaseRequest();
            throw failure;
        }
    }

    private synchronized void releaseRequest() { activeRequests--; }

    private void requireIdle() {
        if (activeRequests != 0) throw new IllegalStateException("A model request is still active. Model switching was not started.");
    }

    private IllegalStateException unavailable() {
        return new IllegalStateException("Model switching did not complete. No model is available until an explicit successful selection.");
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
                .filter(e -> includeCatalogInDefaultInstalled(e.getKey(), activeBackend))
                .flatMap(e -> {
                    String backend = e.getKey();
                    ModelCatalog c = catalogs.get(backend);
                    if (c == null) return Stream.empty();
                    return c.installed().stream()
                            .map(m -> m.backend() == null
                                    ? new ModelRef(backend, m.name(), m.dims(), m.note())
                                    : m);
                })
                .sorted(Comparator.comparing(ModelRef::backend).thenComparing(ModelRef::name))
                .collect(Collectors.toList());
    }

    static boolean includeCatalogInDefaultInstalled(String backend, String activeBackend) {
        if (backend == null || backend.isBlank()) {
            return false;
        }
        if ("ollama".equals(backend)) {
            return "ollama".equals(activeBackend);
        }
        return true;
    }

    /** Resolve "backend/model" or bare "model" by scanning catalogs. */
    public Optional<ModelRef> resolve(String s) {
        if (s == null || s.isBlank()) return Optional.empty();
        String needle = s.trim();

        if (needle.contains("/")) {
            String[] parts = needle.split("/", 2);
            if (parts.length != 2) return Optional.empty();
            ModelCatalog c = catalogs.get(parts[0]);
            if (c == null) return Optional.empty();
            return c.find(parts[1]).map(m -> m.backend() == null
                    ? new ModelRef(parts[0], m.name(), m.dims(), m.note())
                    : m);
        }

        return providers.keySet().stream()
                .filter(backend -> includeCatalogInDefaultInstalled(backend, activeBackend))
                .map(backend -> {
                    ModelCatalog c = catalogs.get(backend);
                    return (c == null) ? Optional.<ModelRef>empty()
                            : c.find(needle).map(m -> m.backend() == null
                            ? new ModelRef(backend, m.name(), m.dims(), m.note())
                            : m);
                })
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    private void ensureDefaults() {
        if (activeBackend == null || activeBackend.isBlank()) activeBackend = "llama_cpp";
        if (activeModel == null || activeModel.isBlank()) {
            activeModel = EngineRuntimeConfig.from(cfg).model();
        }
    }

    private synchronized void closeEngine() {
        if (activeEngine != null) activeEngine.close();
        activeEngine = null;
    }

    @Override public synchronized void close() { closeEngine(); }
}
