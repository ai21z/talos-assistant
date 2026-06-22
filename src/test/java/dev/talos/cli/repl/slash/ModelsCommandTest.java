package dev.talos.cli.repl.slash;

import dev.talos.spi.types.ModelRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelsCommandTest {

    @Test
    void renderInstalledModelsGroupsManagedLlamaCppBeforeLegacyOllama() {
        String text = ModelsCommand.renderInstalledModels(List.of(
                ModelRef.of("ollama", "gpt-oss:20b"),
                ModelRef.of("llama_cpp", "qwen2.5-coder-14b"),
                ModelRef.of("compat", "custom-chat")));

        int recommended = text.indexOf("Recommended managed llama.cpp");
        int llama = text.indexOf("llama_cpp/qwen2.5-coder-14b");
        int legacy = text.indexOf("Legacy/optional Ollama");
        int ollama = text.indexOf("ollama/gpt-oss:20b");
        int other = text.indexOf("Other configured backends");
        int compat = text.indexOf("compat/custom-chat");

        assertTrue(recommended >= 0, text);
        assertTrue(llama > recommended, text);
        assertTrue(legacy > llama, text);
        assertTrue(ollama > legacy, text);
        assertTrue(other > ollama, text);
        assertTrue(compat > other, text);
        assertTrue(text.contains("/set model <backend/model>"), text);
    }
}
