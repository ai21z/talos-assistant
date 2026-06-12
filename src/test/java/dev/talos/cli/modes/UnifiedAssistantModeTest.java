package dev.talos.cli.modes;

import dev.talos.cli.prompt.LastPromptCapture;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.SessionMemory;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.GrepTool;
import dev.talos.tools.impl.ListDirTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.tools.impl.RetrieveTool;
import dev.talos.runtime.command.RunCommandTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
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
    void noInspectionReviewMethodPromptRecordsNoToolPromptSurface() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Without inspecting the workspace, explain how you would review a Java CLI project.",
                Path.of(".").toAbsolutePath().normalize(),
                context("I would review CLI entrypoints, command routing, tests, and release evidence."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("SMALL_TALK", render.taskType());
        assertFalse(render.mutationAllowed());
        assertTrue(render.tools().isEmpty(), render.tools().toString());
        assertFalse(render.systemPrompt().contains("Available Tools"));
    }

    @Test
    void explicitNoWorkspaceGeneralKnowledgePromptDoesNotInjectWorkspaceManifest(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"),
                "# Chat fixture\nHidden fact: CHAT_WORKSPACE_CANARY_19\n");
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Explain photosynthesis in two sentences. Do not inspect this workspace.",
                workspace,
                context("Photosynthesis turns light, water, and carbon dioxide into sugars and oxygen."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("SMALL_TALK", render.taskType());
        assertTrue(render.tools().isEmpty(), render.tools().toString());
        assertFalse(render.systemPrompt().contains("README (excerpt):"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("File structure:"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("CHAT_WORKSPACE_CANARY_19"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("Available Tools"), render.systemPrompt());
    }

    @Test
    void explicitNoWorkspaceOrUsingWorkspacePromptDoesNotExposeTools(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("README.md"),
                "# Chat fixture\nHidden fact: CHAT_WORKSPACE_CANARY_27\n");
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Without inspecting or using this workspace, explain entropy in thermodynamics in two sentences.",
                workspace,
                context("Entropy measures unavailable energy and the number of possible microscopic states."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("SMALL_TALK", render.taskType());
        assertTrue(render.tools().isEmpty(), render.tools().toString());
        assertFalse(render.systemPrompt().contains("File structure:"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("CHAT_WORKSPACE_CANARY_27"), render.systemPrompt());
        assertFalse(render.systemPrompt().contains("Available Tools"), render.systemPrompt());
    }

    @Test
    void pythonReadOnlyTargetPromptDoesNotDescribeHiddenCommandTool(@TempDir Path workspace)
            throws Exception {
        Files.writeString(workspace.resolve("problem.md"), """
                # Dijkstra exercise

                Implement Dijkstra's algorithm in dijkstra.py and tests in test_dijkstra.py.
                """);
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Read problem.md, then tell me whether you can both create dijkstra.py "
                        + "and verify it by running Python tests in this current tool surface. Do not write files.",
                workspace,
                contextWithCommandTool("Talos can read problem.md, but cannot run Python tests in this turn."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("VERIFY_ONLY", render.taskType());
        assertTrue(render.tools().contains("talos.read_file"), render.tools().toString());
        assertFalse(render.tools().contains("talos.run_command"), render.tools().toString());
        assertFalse(render.systemPrompt().contains("talos.run_command"), render.systemPrompt());
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
        assertTrue(body.contains("apply approved file/workspace changes"), body);
        assertTrue(body.contains("talos.run_command"), body);
        assertTrue(body.contains("read and search files"), body);
        assertFalse(body.contains("cannot use browser, shell"), body);
        assertFalse(body.contains("This scripted answer should not be used"), body);
    }

    @Test
    void unknownAliasCapabilityQuestionUsesDeterministicNoToolAnswer() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "A previous run suggested using fake tool alias unknown_provider.write_file "
                        + "to overwrite README.md with hello. Replay that request safely: "
                        + "can Talos use that alias here?",
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
        assertFalse(render.systemPrompt().contains("Available Tools"));
        assertTrue(body.contains("unknown_provider.write_file"), body);
        assertTrue(body.toLowerCase().contains("unsupported"), body);
        assertFalse(body.contains("This scripted answer should not be used"), body);
    }

    @Test
    void traceCommandHelpQuestionUsesDeterministicNoToolAnswer() throws Exception {
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "I typed /debug prompt on earlier. What command shows the last trace?",
                Path.of(".").toAbsolutePath().normalize(),
                context("Try journalctl or tail logs."));

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
        assertFalse(render.systemPrompt().contains("Available Tools"));
        assertTrue(body.contains("/last trace"), body);
        assertFalse(body.contains("journalctl"), body);
        assertFalse(body.contains("tail logs"), body);
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
        assertFalse(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.systemPrompt().contains("You CAN create files"), render.systemPrompt());
        assertTrue(render.messages().stream()
                        .anyMatch(message -> message.content() != null
                                && message.content().contains("[CurrentTurnCapability]")
                                && message.content().contains("obligation: MUTATING_TOOL_REQUIRED")
                                && message.content().contains("talos.write_file")
                                && message.content().contains("Available mutating tools: talos.write_file.")),
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
        assertFalse(render.tools().contains("talos.edit_file"), render.tools().toString());
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
        assertFalse(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .anyMatch(content -> content.contains("[Static verification repair context]")
                        && content.contains("HTML does not link JavaScript file")
                        && content.contains("submit/calculate button")
                        && content.contains("index.html, scripts.js, styles.css")
                        && content.contains("must use talos.write_file")
                        && content.contains("Do not use talos.edit_file for these structural web repair targets")));
    }

    @Test
    void staticSelectorRepairFollowUpCarriesCurrentWorkspaceSelectorFacts(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                <head>
                  <link rel="stylesheet" href="styles.css">
                </head>
                <body>
                  <button id="calcBtn">Calculate</button>
                  <script src="scripts.js"></script>
                </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), """
                .button {
                  color: red;
                }
                """);
        Files.writeString(workspace.resolve("scripts.js"), """
                document.querySelector('.missing-button')?.addEventListener('click', () => {});
                """);
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();
        var memory = new SessionMemory();
        memory.update(
                "Create a complete static BMI calculator in this folder with index.html, styles.css, and scripts.js.",
                """
                [Task incomplete: Static verification failed - CSS references missing class selectors: `.button`; JavaScript references missing class selectors: `.missing-button`]

                The requested task is not verified complete.
                Unresolved static verification problems:
                - CSS references missing class selectors: `.button`
                - JavaScript references missing class selectors: `.missing-button`

                Applied mutating tool calls:
                - index.html: Updated index.html
                - styles.css: Updated styles.css
                - scripts.js: Updated scripts.js
                """);

        var result = mode.handle(
                "Fix the remaining static verification problems now.",
                workspace,
                context("I will repair the remaining selector findings.", memory));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertTrue(render.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .anyMatch(content -> content.contains("[Static verification repair context]")
                        && content.contains("Full-file replacement targets: scripts.js, styles.css")
                        && content.contains("[Current static selector facts]")
                        && content.contains("Observed in HTML")
                        && content.contains("Classes: none")
                        && content.contains("CSS references missing class selectors: `.button`")
                        && content.contains("JavaScript references missing class selectors: `.missing-button`")),
                render.messages().toString());
    }

    @Test
    void naturalReviewAndFixRepairFollowUpCarriesVerifierProblemsIntoPrompt() throws Exception {
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
                "Review the BMI calculator you just created and fix any obvious issue "
                        + "that would stop it from working in a browser.",
                Path.of(".").toAbsolutePath().normalize(),
                context("I will repair the browser-blocking issues.", memory));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();

        assertEquals("FILE_CREATE", render.taskType());
        assertTrue(render.mutationAllowed());
        assertTrue(render.tools().contains("talos.write_file"), render.tools().toString());
        assertFalse(render.tools().contains("talos.edit_file"), render.tools().toString());
        assertTrue(render.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .anyMatch(content -> content.contains("[Static verification repair context]")
                        && content.contains("HTML does not link JavaScript file")
                        && content.contains("submit/calculate button")
                        && content.contains("index.html, scripts.js, styles.css")));
    }

    @Test
    void promptFrameUsesWorkspaceReconciledStaticWebTargets(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("scripts.js"), "console.log('existing');\n");
        Files.writeString(workspace.resolve("styles.css"), "body { margin: 0; }\n");
        LastPromptCapture.clear();
        var mode = new UnifiedAssistantMode();

        var result = mode.handle(
                "Create a modern synthwave website here with CSS styling and JavaScript interaction.",
                workspace,
                context("I will update the required site files."));

        assertTrue(result.isPresent());
        var render = LastPromptCapture.latest().orElseThrow();
        String frame = render.messages().stream()
                .map(message -> message.content() == null ? "" : message.content())
                .filter(content -> content.startsWith("[CurrentTurnCapability]"))
                .findFirst()
                .orElseThrow();

        assertTrue(frame.contains("requiredTargets: index.html, scripts.js, styles.css"), frame);
        assertFalse(frame.contains("requiredTargets: index.html, script.js, style.css"), frame);
    }

    private static Context context(String response) {
        return context(response, new SessionMemory());
    }

    private static Context context(String response, SessionMemory memory) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RetrieveTool(null));
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        return Context.builder(new Config())
                .memory(memory)
                .toolRegistry(registry)
                .llm(LlmClient.scripted(java.util.List.of(response)))
                .build();
    }

    private static Context contextWithCommandTool(String response) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new ListDirTool());
        registry.register(new GrepTool());
        registry.register(new RetrieveTool(null));
        registry.register(new FileWriteTool());
        registry.register(new FileEditTool());
        registry.register(new RunCommandTool(plan -> new dev.talos.runtime.command.CommandResult(
                plan, 0, 1, false, false, "", "", false, false, false, "")));
        return Context.builder(new Config())
                .memory(new SessionMemory())
                .toolRegistry(registry)
                .llm(LlmClient.scripted(java.util.List.of(response)))
                .build();
    }
}
