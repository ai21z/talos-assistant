package dev.talos.cli.launcher;

import dev.talos.core.Config;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnoseCmdTest {

    @Test
    void engineSectionUsesActiveBackendNotHardCodedOllama() {
        String section = DiagnoseCmd.renderEngineSection(new Config(), true);

        assertTrue(section.contains("Engine:"));
        assertTrue(section.contains("Backend: llama_cpp"));
        assertTrue(section.contains("Model:   talos-agent"));
        assertFalse(section.contains("Ollama:"));
    }
}
