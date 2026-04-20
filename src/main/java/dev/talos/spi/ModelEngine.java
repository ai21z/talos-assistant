package dev.talos.spi;

import dev.talos.spi.types.*;

/**
 * Backward-compatible composed engine SPI.
 *
 * <p>During the migration period, callers that still want the combined chat +
 * embedding surface can continue to depend on {@code ModelEngine}, while newer
 * code can depend on {@link ChatModelEngine} or {@link EmbeddingEngine}
 * directly.
 */
public interface ModelEngine extends ChatModelEngine, EmbeddingEngine, AutoCloseable {
    String id();
    Capabilities caps();
    Health health();

    @Override default void close() {}
}
