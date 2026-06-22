package dev.talos.core.embed;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;

/**
 * Owns Talos-managed embedding server processes across short-lived embedding clients.
 *
 * <p>Query-time retrieval creates transient {@link CompatEmbeddingsClient} instances.
 * The managed llama.cpp process is heavier than those clients, so the registry owns
 * the process lifecycle and hands clients no-op-close leases.
 */
final class ManagedEmbeddingEndpointRegistry implements AutoCloseable {
    private static final ManagedEmbeddingEndpointRegistry GLOBAL =
            new ManagedEmbeddingEndpointRegistry(ManagedLlamaCppEmbeddingServerManager::new, true);

    private final ConcurrentMap<ManagedLlamaCppEmbeddingConfig, ManagedEmbeddingEndpoint> endpoints =
            new ConcurrentHashMap<>();
    private final Function<ManagedLlamaCppEmbeddingConfig, ManagedEmbeddingEndpoint> endpointFactory;

    ManagedEmbeddingEndpointRegistry(Function<ManagedLlamaCppEmbeddingConfig, ManagedEmbeddingEndpoint> endpointFactory) {
        this(endpointFactory, false);
    }

    private ManagedEmbeddingEndpointRegistry(
            Function<ManagedLlamaCppEmbeddingConfig, ManagedEmbeddingEndpoint> endpointFactory,
            boolean installShutdownHook) {
        this.endpointFactory = Objects.requireNonNull(endpointFactory, "endpointFactory");
        if (installShutdownHook) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    closeAll();
                } catch (RuntimeException ignored) {
                    // Shutdown cleanup is best-effort; embedding errors must not mask JVM shutdown.
                }
            }, "talos-managed-embedding-endpoints"));
        }
    }

    static ManagedEmbeddingEndpointRegistry global() {
        return GLOBAL;
    }

    ManagedEmbeddingEndpoint acquire(ManagedLlamaCppEmbeddingConfig config) {
        if (config == null || !config.enabled()) return ManagedEmbeddingEndpoint.NOOP;
        ManagedEmbeddingEndpoint shared = endpoints.computeIfAbsent(config, endpointFactory);
        return new SharedLease(shared);
    }

    void closeAll() {
        List<ManagedEmbeddingEndpoint> owned = new ArrayList<>(endpoints.values());
        endpoints.clear();
        RuntimeException failure = null;
        for (ManagedEmbeddingEndpoint endpoint : owned) {
            try {
                endpoint.close();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = new RuntimeException("Failed to close managed embedding endpoint", e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) throw failure;
    }

    @Override
    public void close() {
        closeAll();
    }

    private record SharedLease(ManagedEmbeddingEndpoint delegate) implements ManagedEmbeddingEndpoint {
        private SharedLease {
            Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void ensureStarted() {
            delegate.ensureStarted();
        }

        @Override
        public void close() {
            // The registry owns the shared process; transient clients only release their lease.
        }
    }
}
