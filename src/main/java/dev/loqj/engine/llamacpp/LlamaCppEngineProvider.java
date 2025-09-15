package dev.loqj.engine.llamacpp;

import dev.loqj.core.Config;
import dev.loqj.spi.*;

public final class LlamaCppEngineProvider implements ModelEngineProvider {
    @Override public String id() { return "llamacpp"; }
    @Override public ModelEngine create(Config cfg) { return new LlamaCppEngine(); }
    @Override public ModelCatalog catalog(Config cfg) { return new LlamaCppCatalog(); }
}
