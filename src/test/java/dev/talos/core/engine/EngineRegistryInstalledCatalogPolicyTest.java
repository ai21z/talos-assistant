package dev.talos.core.engine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EngineRegistryInstalledCatalogPolicyTest {

    @Test
    void defaultInstalledListingSkipsOllamaWhenActiveBackendIsManagedLlamaCpp() {
        assertFalse(EngineRegistry.includeCatalogInDefaultInstalled("ollama", "llama_cpp"),
                "default /models must not probe or spawn Ollama while managed llama.cpp is active");
    }

    @Test
    void explicitOllamaBackendAllowsOllamaCatalog() {
        assertTrue(EngineRegistry.includeCatalogInDefaultInstalled("ollama", "ollama"));
    }

    @Test
    void managedAndOtherBackendsRemainVisibleByDefault() {
        assertTrue(EngineRegistry.includeCatalogInDefaultInstalled("llama_cpp", "llama_cpp"));
        assertTrue(EngineRegistry.includeCatalogInDefaultInstalled("compat", "llama_cpp"));
    }
}
