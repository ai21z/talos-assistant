package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.Result;
import org.junit.jupiter.api.Test;

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
