package dev.loqj.engine.stubs.gpt4all;

import dev.loqj.core.Config;
import dev.loqj.spi.*;

/**
 * @deprecated This is a stub implementation moved to engine.stubs.
 * Not wired via ServiceLoader. Use OllamaEngineProvider for actual functionality.
 */
@Deprecated(since = "0.1.0", forRemoval = true)
public final class Gpt4AllEngineProvider implements ModelEngineProvider {
    @Override public String id() { return "gpt4all"; }

    @Override
    public ModelEngine create(Config cfg) {
        throw new UnsupportedOperationException("Gpt4All stub - not implemented. Use Ollama.");
    }

    @Override
    public ModelCatalog catalog(Config cfg) {
        throw new UnsupportedOperationException("Gpt4All stub - not implemented. Use Ollama.");
    }
}
