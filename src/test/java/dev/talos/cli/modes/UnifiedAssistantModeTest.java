package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UnifiedAssistantModeTest {

    @Test
    void smallTalkTurnRecordsNoToolPromptSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "hello",
                Path.of(".").toAbsolutePath().normalize(),
                context("Hi. How can I help?"));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertTrue(render.tools().isEmpty());
        assertFalse(render.systemPrompt().contains("Available Tools"));
        assertTrue(render.messages().stream()
                .anyMatch(message -> message.content() != null
                        && message.content().contains("type: SMALL_TALK")
                        && message.content().contains("Do not call tools")));
    }

    private static Context context(String response) {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        return Context.builder(new Config())
                .toolRegistry(registry)
                .llm(LlmClient.scripted(java.util.List.of(response)))
                .build();
    }
}
