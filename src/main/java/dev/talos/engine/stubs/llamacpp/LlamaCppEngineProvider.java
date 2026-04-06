package dev.talos.engine.stubs.llamacpp;

import dev.talos.core.Config;
import dev.talos.spi.ModelCatalog;
import dev.talos.spi.ModelEngine;
import dev.talos.spi.ModelEngineProvider;

/**
 * @deprecated This is a stub implementation moved to engine.stubs.
 * Not wired via ServiceLoader. Use OllamaEngineProvider for actual functionality.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public final class LlamaCppEngineProvider implements ModelEngineProvider {
    @Override public String id() { return "llamacpp"; }
    @Override public ModelEngine create(Config cfg) { return new LlamaCppEngine(); }
    @Override public ModelCatalog catalog(Config cfg) { return new LlamaCppCatalog(); }
}
