package dev.loqj.spi;

import dev.loqj.core.Config; // matches EngineRegistry usage

public interface ModelEngineProvider {
    String id();                         // e.g., "ollama"
    ModelEngine create(Config cfg);      // EngineRegistry calls this
    ModelCatalog catalog(Config cfg);    // EngineRegistry calls this
}
