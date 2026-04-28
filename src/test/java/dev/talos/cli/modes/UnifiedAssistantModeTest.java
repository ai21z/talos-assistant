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
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
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
    void chatOnlyGreetingRecordsNoToolPromptSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "hello, answer briefly as Talos",
                Path.of(".").toAbsolutePath().normalize(),
                context("Hi, I am Talos."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("SMALL_TALK", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().isEmpty(), render.tools().toString());
        assertFalse(render.systemPrompt().contains("Available Tools"));
    }

    @Test
    void privacyNegatedChatPromptRecordsNoToolPromptSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "I am only chatting, please don't inspect my files. What can you do for me?",
                Path.of(".").toAbsolutePath().normalize(),
                context("Talos can help with local workspace tasks when you ask it to inspect files."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("SMALL_TALK", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().isEmpty(), render.tools().toString());
        assertFalse(render.systemPrompt().contains("Available Tools"));
    }

    @Test
    void explicitWorkspacePromptStillRecordsReadOnlyToolSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "What files are in this workspace?",
                Path.of(".").toAbsolutePath().normalize(),
                context("I will inspect the workspace."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("WORKSPACE_EXPLAIN", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.list_dir"), render.tools().toString());
        assertTrue(render.tools().contains("talos.read_file"), render.tools().toString());
        assertFalse(render.tools().contains("talos.write_file"), render.tools().toString());
        assertFalse(render.tools().contains("talos.edit_file"), render.tools().toString());
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
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RetrieveTool(null));
        registry.register(new FileWriteTool(undoStack));
        registry.register(new FileEditTool(undoStack));
        return Context.builder(new Config())
                .memory(memory)
                .toolRegistry(registry)
                .llm(LlmClient.scripted(java.util.List.of(response)))
                .build();
    }
}
