package dev.talos.runtime.toolcall;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.talos.runtime.task.TaskType;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolResult;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class TerminalReadOnlyStopAnswerTest {

    @Test
    void staticDiagnosticsDoNotTerminateBeforeRequestedExplanationAndCalculation(@TempDir Path workspace) throws Exception {
        writeBrokenWebFixture(workspace);
        var messages = new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(
                "Inspect index.html and script.js. Explain why this website is broken, give the correct selector, "
                        + "and calculate 18 / 3. Do not change files.")));
        LoopState state = state(messages, workspace);
        state.totalToolsInvoked = 2;
        state.pathsReadThisTurn.add("index.html");
        state.pathsReadThisTurn.add("script.js");

        assertNull(TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)));
    }

    @AfterEach
    void clearTaskCapture() {
        TurnTaskContractCapture.clear();
    }

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
    void webEvidenceAllowsModelSynthesisInsteadOfAssumingDiagnosticResponseOwnership(@TempDir Path workspace) throws Exception {
        writeBrokenWebFixture(workspace);
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user("Inspect this BMI website and identify why it is broken.")));
        LoopState state = state(messages, workspace);
        state.totalToolsInvoked = 2;
        state.pathsReadThisTurn.add("index.html");
        state.pathsReadThisTurn.add("script.js");

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0));

        assertNull(answer);
    }

    @Test
    void syntheticWorkspaceExplainMarkerDoesNotCreateDiagnosticResponseOwnership(
            @TempDir Path workspace
    ) throws Exception {
        writeBrokenWebFixture(workspace);
        String original =
                "Inspect this BMI website and identify why it is broken.";
        TurnTaskContractCapture.set(
                TaskContractResolver.fromUserRequest(original));
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(original),
                ChatMessage.user(
                        "Synthesis retry.\nTask type: WORKSPACE_EXPLAIN")));
        LoopState state = state(messages, workspace);
        state.totalToolsInvoked = 2;
        state.pathsReadThisTurn.add("index.html");
        state.pathsReadThisTurn.add("script.js");

        String answer =
                TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0));

        assertNull(answer);
    }

    @Test
    void capturedWorkspaceExplainCannotBeRelabeledBySyntheticWebPrompt(
            @TempDir Path workspace
    ) throws Exception {
        writeBrokenWebFixture(workspace);
        String original = "Explain this workspace.";
        TurnTaskContractCapture.set(
                TaskContractResolver.fromUserRequest(original));
        var messages = new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(original),
                ChatMessage.user(
                        "Inspect this BMI website and identify why it is "
                                + "broken.")));
        LoopState state = state(messages, workspace);
        state.totalToolsInvoked = 2;
        state.pathsReadThisTurn.add("index.html");
        state.pathsReadThisTurn.add("script.js");

        assertNull(TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)));
    }

    private static void writeBrokenWebFixture(Path workspace) throws Exception {
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
        Files.writeString(
                workspace.resolve("styles.css"),
                "body { font-family: sans-serif; }\n");
        Files.writeString(workspace.resolve("script.js"), """
                document.querySelector('.missing-button');
                """);
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

    private static final String TWO_TARGET_REQUEST = "Read README.md and notes.md and summarize both.";

    @Test
    void singleTargetReadUnderItsAbsoluteSpellingRendersTheReadTarget(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.md"), "grounded note\n");
        String absolute = root.resolve("notes.md").toString();
        LoopState state = state(singleReadMessages("Read notes.md", absolute, "1 | grounded note"), root);
        recordRead(state, absolute, "1 | grounded note");

        assertEquals("""
                Read notes.md:
                1 | grounded note""", TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)));
    }

    @Test
    void singleTargetReadUnderItsRelativeSpellingRendersTheReadTarget(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.md"), "grounded note\n");
        LoopState state = state(singleReadMessages("Read notes.md", "notes.md", "1 | grounded note"), root);
        recordRead(state, "notes.md", "1 | grounded note");

        assertEquals("""
                Read notes.md:
                1 | grounded note""", TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)));
    }

    @Test
    void siblingOrSameNameInAnotherFolderDoesNotRenderTheSingleReadTarget(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("notes.md"), "grounded note\n");
        Files.writeString(root.resolve("other.md"), "other note\n");
        Files.writeString(root.resolve("docs").resolve("notes.md"), "docs note\n");
        for (Path read : List.of(root.resolve("other.md"), root.resolve("docs").resolve("notes.md"))) {
            TurnTaskContractCapture.clear();
            String absolute = read.toString();
            LoopState state = state(singleReadMessages("Read notes.md", absolute, "1 | not the target"), root);
            recordRead(state, absolute, "1 | not the target");

            assertNull(TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)), absolute);
        }
    }

    @Test
    void multiTargetEvidenceIsCompleteWhenBothTargetsWereReadUnderRelativeSpellings(@TempDir Path temporary)
            throws Exception {
        Path root = twoTargetFixture(temporary);
        LoopState state = twoTargetState(root);
        recordRead(state, "README.md", "1 | First document");
        recordRead(state, "notes.md", "1 | Second document");

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0));

        assertNotNull(answer);
        assertTrue(answer.startsWith("Read evidence complete, but no final synthesis was produced."), answer);
        assertTrue(answer.contains("\n- README.md\n- notes.md"), answer);
    }

    @Test
    void multiTargetEvidenceIsCompleteWhenOneTargetWasReadUnderItsAbsoluteSpelling(@TempDir Path temporary)
            throws Exception {
        Path root = twoTargetFixture(temporary);
        LoopState state = twoTargetState(root);
        recordRead(state, "README.md", "1 | First document");
        recordRead(state, root.resolve("notes.md").toString(), "1 | Second document");

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0));

        assertNotNull(answer, "an absolute read of notes.md is a read of the requested notes.md");
        assertTrue(answer.startsWith("Read evidence complete, but no final synthesis was produced."), answer);
        assertTrue(answer.contains("\n- README.md\n- notes.md"), answer);
        assertFalse(answer.contains(root.resolve("notes.md").toString()), "displayed targets keep contract text");
    }

    @Test
    void multiTargetEvidenceIsNotCompleteAfterASiblingOrSameNameElsewhereRead(@TempDir Path temporary)
            throws Exception {
        Path root = twoTargetFixture(temporary);
        Files.createDirectories(root.resolve("docs"));
        Files.writeString(root.resolve("docs").resolve("notes.md"), "Docs notes\n");
        Files.writeString(root.resolve("other.md"), "Other\n");
        for (Path read : List.of(root.resolve("docs").resolve("notes.md"), root.resolve("other.md"))) {
            TurnTaskContractCapture.clear();
            LoopState state = twoTargetState(root);
            recordRead(state, "README.md", "1 | First document");
            recordRead(state, read.toString(), "1 | Not a target");

            assertNull(TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0)), read.toString());
        }
    }

    private static Path twoTargetFixture(Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("README.md"), "First document\n");
        Files.writeString(root.resolve("notes.md"), "Second document\n");
        return root;
    }

    private static LoopState twoTargetState(Path root) {
        var contract = TaskContractResolver.fromUserRequest(TWO_TARGET_REQUEST);
        assertEquals(TaskType.READ_ONLY_QA, contract.type(), contract.toString());
        assertEquals(java.util.Set.of("README.md", "notes.md"), contract.expectedTargets(), contract.toString());
        TurnTaskContractCapture.set(contract);
        return state(new ArrayList<>(List.of(ChatMessage.system("sys"), ChatMessage.user(TWO_TARGET_REQUEST))), root);
    }

    private static ArrayList<ChatMessage> singleReadMessages(String request, String readPath, String body) {
        return new ArrayList<>(List.of(
                ChatMessage.system("sys"),
                ChatMessage.user(request),
                ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                        "call-1", "talos.read_file", java.util.Map.of("path", readPath)))),
                ChatMessage.toolResult("call-1", "[tool_result: talos.read_file]\n" + body + "\n[/tool_result]")));
    }

    private static void recordRead(LoopState state, String path, String body) {
        state.toolOutcomes.add(new ToolCallLoop.ToolOutcome(
                "talos.read_file", path, true, false, false, "read " + path, ""));
        ReadEvidenceStateAccounting.recordSuccessfulToolResult(
                state,
                new ToolCall("talos.read_file", java.util.Map.of("path", path)),
                path,
                ToolResult.ok(body));
    }

    @Test
    void theStopAnswerIsNotGivenWhenTheLatestReadWasAnotherFile(@TempDir Path temporary) throws Exception {
        Path root = temporary.toRealPath();
        Files.writeString(root.resolve("notes.md"), "grounded note\n");
        Files.writeString(root.resolve("other.md"), "other note\n");
        String absolute = root.resolve("notes.md").toString();
        var messages = singleReadMessages("Read notes.md", absolute, "1 | grounded note");
        messages.add(ChatMessage.assistantWithToolCalls("", List.of(new ChatMessage.NativeToolCall(
                "call-2", "talos.read_file", java.util.Map.of("path", "other.md")))));
        messages.add(ChatMessage.toolResult("call-2", "[tool_result: talos.read_file]\n1 | other note\n[/tool_result]"));
        LoopState state = state(messages, root);
        recordRead(state, absolute, "1 | grounded note");
        recordRead(state, "other.md", "1 | other note");

        String answer = TerminalReadOnlyStopAnswer.tryAnswer(state, outcome(0));

        assertTrue(answer == null || !answer.contains("other note"), String.valueOf(answer));
    }
}
