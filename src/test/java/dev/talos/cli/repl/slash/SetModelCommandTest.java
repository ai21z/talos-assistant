package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.Result;
import dev.talos.spi.types.ModelRef;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SetModelCommandTest {

    @Test
    void llamaCppMissingModelExplainsManagedGgufSwitchingWorkflow() {
        String text = SetModelCommand.modelNotFoundMessage("llama_cpp/qwen36vf-q6k");

        assertTrue(text.contains("Model not found: llama_cpp/qwen36vf-q6k"), text);
        assertTrue(text.contains("Managed llama.cpp can only select the configured/running GGUF"), text);
        assertTrue(text.contains("talos setup models --profile <name> --write --force"), text);
        assertTrue(text.contains("restart Talos"), text);
        assertFalse(text.contains("hot-swap"), text);
    }

    @Test
    void nonLlamaCppMissingModelKeepsGenericModelsHint() {
        String text = SetModelCommand.modelNotFoundMessage("ollama/gpt-oss:20b");

        assertTrue(text.contains("Model not found: ollama/gpt-oss:20b"), text);
        assertTrue(text.contains("Tip: /models"), text);
        assertFalse(text.contains("configured/running GGUF"), text);
    }

    @Test
    void downloadedButUnconfiguredGgufGetsActionableSwitchGuidance() {
        // T899: the user typed a bare downloaded GGUF name; /set model 404'd with a
        // bare "Tip: /models". It must instead explain it is on disk-but-unconfigured
        // and how to actually switch.
        var downloaded = List.of(
                ModelRef.of("llama_cpp", "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K"),
                ModelRef.of("llama_cpp", "qwen2.5-coder-14b-instruct-q4_k_m"));

        String text = SetModelCommand.modelNotFoundMessage(
                "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K", downloaded);

        assertTrue(text.contains("Qwen3.6-14B-A3B-VibeForged-v2-Q6_K"), text);
        assertTrue(text.toLowerCase(Locale.ROOT).contains("downloaded but not configured"), text);
        assertTrue(text.contains("talos setup models"), text);
        assertTrue(text.contains("restart Talos"), text);
        assertFalse(text.contains("Tip: /models"), text);
    }

    @Test
    void downloadedMatchIsCaseAndGgufSuffixInsensitive() {
        var downloaded = List.of(ModelRef.of("llama_cpp", "qwen2.5-coder-14b-instruct-q4_k_m"));

        String text = SetModelCommand.modelNotFoundMessage(
                "QWEN2.5-CODER-14B-INSTRUCT-Q4_K_M.gguf", downloaded);

        assertTrue(text.toLowerCase(Locale.ROOT).contains("downloaded but not configured"), text);
    }

    @Test
    void downloadedGuidanceSubstitutesResolvedProfileAndServerPath() {
        // T902: the guidance must be copy-pasteable, not literal <name>/<llama-server>,
        // when the GGUF maps to a canned profile and the server-path is known.
        var downloaded = List.of(ModelRef.of("llama_cpp", "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K"));

        String text = SetModelCommand.modelNotFoundMessage(
                "Qwen3.6-14B-A3B-VibeForged-v2-Q6_K", downloaded,
                "qwen36vf-q6k", "C:/llama/llama-server.exe");

        assertTrue(text.contains("--profile qwen36vf-q6k"), text);
        assertTrue(text.contains("--server-path C:/llama/llama-server.exe"), text);
        assertFalse(text.contains("<name>"), text);
        assertFalse(text.contains("<llama-server>"), text);
    }

    @Test
    void downloadedGuidanceQuotesServerPathWithSpaces() {
        var downloaded = List.of(ModelRef.of("llama_cpp", "qwen2.5-coder-14b-instruct-q4_k_m"));

        String text = SetModelCommand.modelNotFoundMessage(
                "qwen2.5-coder-14b-instruct-q4_k_m", downloaded,
                "qwen2.5-coder-14b", "C:/Program Files/llama/llama-server.exe");

        assertTrue(text.contains("--server-path \"C:/Program Files/llama/llama-server.exe\""), text);
    }

    @Test
    void downloadedGuidanceFallsBackToPlaceholdersWhenUnresolved() {
        var downloaded = List.of(ModelRef.of("llama_cpp", "mystery-model"));

        String text = SetModelCommand.modelNotFoundMessage(
                "mystery-model", downloaded, null, "");

        assertTrue(text.contains("--profile <name>"), text);
        assertTrue(text.contains("--server-path <llama-server>"), text);
    }

    @Test
    void unknownNameWithDownloadedListFallsBackToGenericHint() {
        var downloaded = List.of(ModelRef.of("llama_cpp", "qwen2.5-coder-14b-instruct-q4_k_m"));

        String text = SetModelCommand.modelNotFoundMessage("totally-unknown-model", downloaded);

        assertTrue(text.contains("Model not found: totally-unknown-model"), text);
        assertTrue(text.contains("Tip: /models"), text);
    }

    @Test
    void usageErrorPointsAtModelsForDiscovery() throws Exception {
        var ctx = Context.builder(new Config()).build();
        SetModelCommand cmd = new SetModelCommand();

        Result noName = cmd.execute("model", ctx);  // /set model  (no name)
        Result noSub = cmd.execute("", ctx);          // /set        (no subcommand)

        assertTrue(noName.toString().contains("/models"),
                "the no-name usage error should point the user at /models: " + noName);
        assertTrue(noSub.toString().contains("/models"),
                "the no-subcommand usage error should point the user at /models: " + noSub);
    }
}
