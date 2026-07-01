package dev.talos.core.embed;

@FunctionalInterface
interface ManagedEmbeddingEndpoint extends AutoCloseable {
    ManagedEmbeddingEndpoint NOOP = () -> {};

    void ensureStarted();

    @Override
    default void close() {}
}
