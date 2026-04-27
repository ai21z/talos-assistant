package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.tools.FileUndoStack;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.ReadFileTool;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    @Test
    void repairFollowUpUsesHistoryAwareContractForNativeToolSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();
        var memory = new SessionMemory();
        memory.update(
                "Create index.html, styles.css, and scripts.js for a BMI calculator.",
                """
                [Task incomplete: Static verification failed - Expected targets were not all mutated.]

                The requested task is not verified complete.
                Remaining static verification problems:
                - scripts.js was expected but was not created.
                """);

        var result = mode.handle(
                "nothing changed, try one more time",
                Path.of(".").toAbsolutePath().normalize(),
                context("No changes yet.", memory));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("FILE_CREATE", render.taskType());
        assertTrue(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.write_file"), render.tools().toString());
        assertTrue(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.systemPrompt().contains("You CAN create files"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("This specific user turn is read-only"),
                render.systemPrompt());
    }

    private static Context context(String response) {
        return context(response, new SessionMemory());
    }

    private static Context context(String response, SessionMemory memory) {
        ToolRegistry registry = new ToolRegistry();
        FileUndoStack undoStack = new FileUndoStack();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        return Context.builder(new Config())
                .memory(memory)
                .toolRegistry(registry)
                .llm(LlmClient.scripted(java.util.List.of(response)))
                .build();
    }
}
