package dev.loqj.engine.stubs.llamacpp;

import dev.loqj.core.Config;
import dev.loqj.spi.ModelCatalog;
import dev.loqj.spi.ModelEngine;
import dev.loqj.spi.ModelEngineProvider;

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
