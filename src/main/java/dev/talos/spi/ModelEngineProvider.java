package dev.talos.spi;

import dev.talos.core.Config; // matches EngineRegistry usage

public interface ModelEngineProvider {
    String id();                         // e.g., "ollama"
    ModelEngine create(Config cfg);      // EngineRegistry calls this
    ModelCatalog catalog(Config cfg);    // EngineRegistry calls this
}
