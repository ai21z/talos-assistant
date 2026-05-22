package dev.talos.spi;

public interface ModelEngineProvider {
    String id();                         // e.g., "ollama"
    ModelEngine create(EngineConfig cfg);      // EngineRegistry calls this
    ModelCatalog catalog(EngineConfig cfg);    // EngineRegistry calls this
}
