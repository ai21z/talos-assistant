package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalReadOnlyStopAnswerTest {

    @Test
    void rendersDirectoryListingFromSelectedEvidence() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("What files are in this folder?"),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "list_dir", java.util.Map.of("path", ".")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: list_dir]
                        README.md
                        index.html
                        notes.md
                        [/tool_result]""")
        ));
        LoopState state = state(messages, Path.of("."));
        var outcome = outcome(1);

        assertEquals("""
                Directory entries:
                - README.md
                - index.html
                - notes.md""", TerminalReadOnlyStopAnswer.tryAnswer(state, outcome));
    }

    @Test
    void rendersSingleReadTargetFromLatestNonDuplicateEvidence() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Read config.json and tell me the name."),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "read_file", java.util.Map.of("path", "config.json")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: read_file]
                        1 | {"name":"t57-fixture"}
                        [/tool_result]"""),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-2", "talos.read_file", java.util.Map.of("path", "config.json")))),
                ChatMessage.toolResult("call-2", """
                        [tool_result: talos.read_file]
                        You already gathered this information and the workspace has not changed since then.
                        [/tool_result]""")
        ));
        LoopState state = state(messages, Path.of("."));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "read_file",
                "config.json",
                true,
                false,
                false,
                "read config.json",
                ""));

        assertEquals("""
                Read config.json:
                1 | {"name":"t57-fixture"}""", TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)));
    }

    @Test
    void rendersMissingReadTargetInsteadOfModelProse() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("read styles.css"),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.read_file", java.util.Map.of("path", "styles.css")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: talos.read_file]
                        [error] File not found: styles.css
                        Files in ./: index.html, script.js, style.css
                        [/tool_result]""")
        ));
        LoopState state = state(messages, Path.of("."));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                "styles.css",
                false,
                false,
                false,
                "",
                "File not found: styles.css\nFiles in ./: index.html, script.js, style.css",
                null,
                dev.talos.tools.ToolError.NOT_FOUND));

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, failedReadOutcome());

        assertEquals("""
                Could not read styles.css: File not found: styles.css
                Files in ./: index.html, script.js, style.css
                Possible intended sibling: style.css""", answer);
    }

    @Test
    void successfulReadTargetRenderingIsUnchanged() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Read notes.md"),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.read_file", java.util.Map.of("path", "notes.md")))),
                ChatMessage.toolResult("call-1", """
                        [tool_result: talos.read_file]
                        1 | grounded note
                        [/tool_result]""")
        ));
        LoopState state = state(messages, Path.of("."));
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file",
                "notes.md",
                true,
                false,
                false,
                "read notes.md",
                ""));

        assertEquals("""
                Read notes.md:
                1 | grounded note""", TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)));
    }

    @Test
    void reportsUnsupportedDocumentWithoutLeakingModelProse() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Summarize slides.pptx.")));
        LoopState state = state(messages, Path.of("."));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 1, false, false, false, 0, List.of("slides.pptx"));

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, outcome);

        assertTrue(answer.startsWith("[Document capability note:"), answer);
        assertTrue(answer.contains("slides.pptx"), answer);
        assertTrue(answer.contains("unsupported binary document"), answer);
    }

    @Test
    void suppressesUnsupportedDocumentAnswerWhenConvertedTextFallbackWasNamed() {
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Summarize extracted_slides.txt instead of slides.pptx.")));
        LoopState state = state(messages, Path.of("."));
        var outcome = new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 1, false, false, false, 0, List.of("slides.pptx"));

        assertNull(TerminalReadOnlyStopAnswer.tryAnswer(state, outcome));
    }

    @Test
    void rendersReadOnlyStaticWebDiagnosticsFromWorkspace(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("index.html"), """
                <!doctype html>
                <html>
                  <head><link rel="stylesheet" href="styles.css"></head>
                  <body>
                    <button class="real-button">Run</button>
                    <script src="script.js"></script>
                  </body>
                </html>
                """);
        Files.writeString(workspace.resolve("styles.css"), "body { font-family: sans-serif; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.missing-button');
                """);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Inspect this BMI website and identify why it is broken.")));
        LoopState state = state(messages, workspace);
        state.totalToolsInvoked = 2;
        state.pathsReadThisTurn.add("index.html");
        state.pathsReadThisTurn.add("script.js");

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0));

        assertTrue(answer.contains("Static web diagnostics found:"), answer);
        assertTrue(answer.contains(".missing-button"), answer);
    }

    private static LoopState state(List<ChatMessage> messages, Path workspace) {
        return new LoopState(
                "",
                List.of(),
                messages,
                workspace,
                null,
                null,
                10,
                0);
    }

    private static ToolCallExecutionStage.IterationOutcome outcome(int successes) {
        return new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 0, false, false, false, successes);
    }

    private static ToolCallExecutionStage.IterationOutcome failedReadOutcome() {
        return new ToolCallExecutionStage.IterationOutcome(
                0, List.of(), 1, false, false, false, 0);
    }
}
