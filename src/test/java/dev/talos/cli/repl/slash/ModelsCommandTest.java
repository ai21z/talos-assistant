package dev.talos.cli.repl.slash;

import dev.talos.spi.types.ModelRef;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelsCommandTest {

    @Test
    void renderInstalledModelsGroupsManagedLlamaCppBeforeLegacyOllama() {
        String text = ModelsCommand.renderInstalledModels(List.of(
                ModelRef.of("ollama", "gpt-oss:20b"),
                ModelRef.of("llama_cpp", "qwen2.5-coder-14b"),
                ModelRef.of("compat", "custom-chat")), List.of());

        int recommended = text.indexOf("Recommended managed llama.cpp");
        int llama = text.indexOf("llama_cpp/qwen2.5-coder-14b");
        int legacy = text.indexOf("Legacy/optional Ollama");
        int ollama = text.indexOf("ollama/gpt-oss:20b");
        int other = text.indexOf("Other configured backends");
        int compat = text.indexOf("compat/custom-chat");

        assertTrue(text.contains("Configured/selectable models"), text);
        assertFalse(text.contains("Installed models"), text);
        assertFalse(text.contains("ready now"), text);
        assertTrue(text.contains("verify with talos doctor --start"), text);
        assertTrue(recommended >= 0, text);
        assertTrue(llama > recommended, text);
        assertTrue(legacy > llama, text);
        assertTrue(ollama > legacy, text);
        assertTrue(other > ollama, text);
        assertTrue(compat > other, text);
        assertTrue(text.contains("/set model <backend/model>"), text);      // how to switch them
        assertTrue(text.contains("not selectable yet"), text);              // tier 2: downloaded-not-configured
        assertTrue(text.contains("talos setup models --profile <name> --write --force"), text);
        assertTrue(text.contains("no hot-swap"), text);                     // managed GGUF caveat
    }

    @Test
    void renderInstalledModelsDisambiguatesGgufProfilesFromTheProfilesCommand() {
        String text = ModelsCommand.renderInstalledModels(List.of(
                ModelRef.of("llama_cpp", "qwen2.5-coder-14b")), List.of());
        // the "GGUF model profile" tip must not be confused with the /profiles command
        assertTrue(text.contains("/profiles"), text);
        assertTrue(text.contains("verification profiles"), text);
    }

    @Test
    void renderShowsDownloadedNotConfiguredSectionByBareName() {
        String text = ModelsCommand.renderInstalledModels(
                List.of(ModelRef.of("llama_cpp", "qwen3.6-35b-a3b-q4km")),
                List.of(ModelRef.of("llama_cpp", "qwen2.5-coder-14b"),
                        ModelRef.of("llama_cpp", "gpt-oss-20b-mxfp4")));

        assertTrue(text.contains("Downloaded GGUFs (not configured):"), text);
        // downloaded GGUFs render by bare name (not selectable until configured)
        assertTrue(text.contains("  qwen2.5-coder-14b"), text);
        assertTrue(text.contains("  gpt-oss-20b-mxfp4"), text);
        // the configured/running model still renders in the managed section as backend/name
        assertTrue(text.contains("llama_cpp/qwen3.6-35b-a3b-q4km"), text);
    }

    @Test
    void renderShowsConfiguredModelSourceNoteWhenPresent() {
        String text = ModelsCommand.renderInstalledModels(
                List.of(new ModelRef(
                        "llama_cpp",
                        "custom-agent",
                        null,
                        "GGUF file: qwen2.5-coder-7b-instruct-q4_k_m.gguf")),
                List.of());

        assertTrue(text.contains("llama_cpp/custom-agent"), text);
        assertTrue(text.contains("GGUF file: qwen2.5-coder-7b-instruct-q4_k_m.gguf"), text);
    }
}
