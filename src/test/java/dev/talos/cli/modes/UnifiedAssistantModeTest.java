package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
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
    void expandedCapabilityPromptUsesDeterministicNoToolAnswer() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "What can you help me with?",
                Path.of(".").toAbsolutePath().normalize(),
                context("This scripted answer should not be used."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();
        Result bodyResult = result.get();
        String body;
        if (bodyResult instanceof Result.Ok ok) {
            body = ok.text;
        } else if (bodyResult instanceof Result.Streamed streamed) {
            body = streamed.fullText + streamed.suffix;
        } else {
            body = bodyResult.toString();
        }

        assertEquals("SMALL_TALK", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().isEmpty(), render.tools().toString());
        assertTrue(body.contains("apply file changes only after approval"), body);
        assertTrue(body.contains("read and search files"), body);
        assertFalse(body.contains("This scripted answer should not be used"), body);
    }

    @Test
    void explicitWorkspacePromptStillRecordsReadOnlyToolSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "What is this project?",
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
    void simpleFolderListingRecordsListDirOnlyToolSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "What files are in this folder?",
                Path.of(".").toAbsolutePath().normalize(),
                context("I will list the folder."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("DIRECTORY_LISTING", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.list_dir"), render.tools().toString());
        assertFalse(render.tools().contains("talos.read_file"), render.tools().toString());
        assertFalse(render.tools().contains("talos.grep"), render.tools().toString());
        assertFalse(render.tools().contains("talos.retrieve"), render.tools().toString());
        assertFalse(render.systemPrompt().contains("talos.read_file"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("talos.grep"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("talos.retrieve"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("File structure:"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("README (excerpt):"), render.systemPrompt());
    }

    @Test
    void overwriteRepairPromptRecordsMutatingToolSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Overwrite these three files to make a working BMI calculator: index.html, styles.css, scripts.js. "
                        + "Use talos.write_file for all three.",
                Path.of(".").toAbsolutePath().normalize(),
                context("I will update the requested files."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertTrue("FILE_EDIT".equals(render.taskType()) || "FILE_CREATE".equals(render.taskType()),
                render.taskType());
        assertTrue(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.write_file"), render.tools().toString());
        assertTrue(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.systemPrompt().contains("You CAN create files"), render.systemPrompt());
        assertTrue(render.messages().stream()
                        .anyMatch(message -> message.content() != null
                                && message.content().contains("[CurrentTurnCapability]")
                                && message.content().contains("obligation: MUTATING_TOOL_REQUIRED")
                                && message.content().contains("talos.write_file")
                                && message.content().contains("talos.edit_file")),
                render.messages().toString());
        assertFalse(render.systemPrompt().contains("This specific user turn is read-only"),
                render.systemPrompt());
    }

    @Test
    void formattingNegationOverwritePromptRecordsMutatingToolSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Use talos.write_file to overwrite index.html. "
                        + "Set the content argument to the exact five letters AFTER. "
                        + "Do not use angle brackets. Do not use placeholders. "
                        + "The entire file should be AFTER.",
                Path.of(".").toAbsolutePath().normalize(),
                context("I will update index.html."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("FILE_EDIT", render.taskType());
        assertTrue(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.write_file"), render.tools().toString());
        assertTrue(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.systemPrompt().contains("You CAN create files"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("This specific user turn is read-only"),
                render.systemPrompt());
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

    @Test
    void staticVerificationRepairFollowUpCarriesVerifierProblemsIntoPrompt() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();
        var memory = new SessionMemory();
        memory.update(
                "Create index.html, styles.css, and scripts.js for a BMI calculator.",
                """
                [Task incomplete: Static verification failed - HTML does not link JavaScript file: `scripts.js`]

                The requested task is not verified complete.
                Remaining static verification problems:
                - styles.css: expected target was not successfully mutated.
                - HTML does not link JavaScript file: `scripts.js`
                - Calculator/form task is missing a submit/calculate button.
                """);

        var result = mode.handle(
                "Fix the remaining static verification problems now.",
                Path.of(".").toAbsolutePath().normalize(),
                context("I will repair the remaining verifier findings.", memory));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("FILE_CREATE", render.taskType());
        assertTrue(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.write_file"), render.tools().toString());
        assertTrue(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .anyMatch(content -> content.contains("[Static verification repair context]")
                        && content.contains("HTML does not link JavaScript file")
                        && content.contains("submit/calculate button")
                        && content.contains("index.html, scripts.js, styles.css")
                        && content.contains("must use talos.write_file")
                        && content.contains("Do not use talos.edit_file for these structural web repair targets")));
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
