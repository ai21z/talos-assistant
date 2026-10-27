package dev.talos.cli.modes;

import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.task.StaticWebRequirements;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.turn.CurrentTurnPlan;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnswerGroundingDisclosureTest {

    @Test
    void ignoresListedFileCandidatesThatDoNotExistOnDisk(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("existing.md"), "real evidence\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(
                        outcome("talos.list_dir", "."),
                        outcome("talos.read_file", "existing.md")),
                List.of("existing.md"));
        List<ChatMessage> messages = List.of(
                ChatMessage.user("What is this project?"),
                ChatMessage.toolResult("call_list",
                        "[tool_result:talos.list_dir]\nexisting.md\nphantom.md\n[/tool_result]"));

        var disclosure = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult,
                workspaceExplainPlan("What is this project?"),
                workspace,
                messages);

        assertTrue(disclosure.workspaceCandidateNote().isBlank(), disclosure.workspaceCandidateNote());
    }

    @Test
    void ignoresPromptNamedFileCandidatesThatDoNotExistOnDisk(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "real evidence\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(outcome("talos.read_file", "README.md")),
                List.of("README.md"));

        var disclosure = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult,
                workspaceExplainPlan("What is this project? Does phantom.md matter?"),
                workspace,
                List.of(ChatMessage.user("What is this project? Does phantom.md matter?")));

        assertTrue(disclosure.workspaceCandidateNote().isBlank(), disclosure.workspaceCandidateNote());
    }

    @Test
    void dotPrefixedReadPathCountsAsTheSameTopLevelCandidate(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "real evidence\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(
                        outcome("talos.list_dir", "."),
                        outcome("talos.read_file", "./README.md")),
                List.of("./README.md"));
        List<ChatMessage> messages = List.of(
                ChatMessage.user("What is this project?"),
                ChatMessage.toolResult("call_list",
                        "[tool_result:talos.list_dir]\nREADME.md\n[/tool_result]"));

        var disclosure = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult,
                workspaceExplainPlan("What is this project?"),
                workspace,
                messages);

        assertTrue(disclosure.workspaceCandidateNote().isBlank(), disclosure.workspaceCandidateNote());
    }

    @Test
    void symlinkCandidateResolvingOutsideWorkspaceIsNotDisclosed(@TempDir Path workspace) throws Exception {
        Path outside = Files.createTempDirectory("talos-grounding-outside-");
        Path secret = outside.resolve("secret.md");
        Files.writeString(secret, "outside\n");
        Path link = workspace.resolve("linked.md");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            assumeTrue(false, "symlink creation unavailable on this platform: " + e.getMessage());
        }
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(outcome("talos.list_dir", ".")),
                List.of());
        List<ChatMessage> messages = List.of(
                ChatMessage.user("What is this project?"),
                ChatMessage.toolResult("call_list",
                        "[tool_result:talos.list_dir]\nlinked.md\n[/tool_result]"));

        var disclosure = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult,
                workspaceExplainPlan("What is this project?"),
                workspace,
                messages);

        assertTrue(disclosure.workspaceCandidateNote().isBlank(), disclosure.workspaceCandidateNote());
    }

    @Test
    void symlinkedWorkspaceStillDisclosesUnreadRealCandidates(@TempDir Path temp) throws Exception {
        Path realWorkspace = temp.resolve("real-workspace");
        Files.createDirectories(realWorkspace);
        Files.writeString(realWorkspace.resolve("README.md"), "real evidence\n");
        Path linkedWorkspace = temp.resolve("linked-workspace");
        try {
            Files.createSymbolicLink(linkedWorkspace, realWorkspace);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            assumeTrue(false, "symlink creation unavailable on this platform: " + e.getMessage());
        }
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(outcome("talos.list_dir", ".")),
                List.of());
        List<ChatMessage> messages = List.of(
                ChatMessage.user("What is this project?"),
                ChatMessage.toolResult("call_list",
                        "[tool_result:talos.list_dir]\nREADME.md\n[/tool_result]"));

        var disclosure = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult,
                workspaceExplainPlan("What is this project?"),
                linkedWorkspace,
                messages);

        assertTrue(disclosure.workspaceCandidateNote().contains("README.md"),
                disclosure.workspaceCandidateNote());
    }

    private static CurrentTurnPlan workspaceExplainPlan(String request) {
        TaskContract contract = new TaskContract(
                TaskType.WORKSPACE_EXPLAIN,
                false,
                false,
                false,
                Set.of(),
                Set.of(),
                Set.of(),
                request,
                "test",
                StaticWebRequirements.none());
        return CurrentTurnPlan.compatibility(
                contract,
                ExecutionPhase.INSPECT,
                List.of("talos.list_dir", "talos.read_file"),
                List.of("talos.list_dir", "talos.read_file"),
                List.of());
    }

    private static ToolCallLoop.LoopResult loopResult(
            List<ToolCallLoop.ToolOutcome> outcomes,
            List<String> readPaths
    ) {
        return new ToolCallLoop.LoopResult(
                "answer",
                1,
                outcomes.size(),
                outcomes.stream().map(ToolCallLoop.ToolOutcome::toolName).toList(),
                List.of(),
                0,
                0,
                false,
                0,
                readPaths,
                0,
                0,
                0,
                0,
                outcomes);
    }

    private static ToolCallLoop.ToolOutcome outcome(String toolName, String path) {
        return new ToolCallLoop.ToolOutcome(toolName, path, true, false, false, "ok", "");
    }

    /** A search or listing outcome whose source paths name the files it touched. */
    private static ToolCallLoop.ToolOutcome outcomeWithSources(String toolName, String target, List<String> sources) {
        return new ToolCallLoop.ToolOutcome(toolName, target, true, false, false, toolName + " " + target, "",
                null, "", null, null, null, sources, "", null, null);
    }

    /** C2: a file that a search matched is neither read in full nor unread. The header says which. */
    @Test
    void searchMatchesAreCountedSeparatelyFromFullReads(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("README.md"), "readme\n");
        Files.writeString(workspace.resolve("calc.py"), "def bmi(): pass\n");
        Files.writeString(workspace.resolve("index.html"), "<html></html>\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(
                        outcomeWithSources("talos.list_dir", ".", List.of("README.md", "calc.py", "index.html")),
                        outcomeWithSources("talos.grep", "bmi", List.of("calc.py")),
                        outcome("talos.read_file", "README.md")),
                List.of("README.md"));
        List<ChatMessage> messages = List.of(
                ChatMessage.user("What is this project?"),
                ChatMessage.toolResult("call_list",
                        "[tool_result:talos.list_dir]\nREADME.md\ncalc.py\nindex.html\n[/tool_result]"));

        String note = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult, workspaceExplainPlan("What is this project?"), workspace, messages)
                .workspaceCandidateNote();

        assertTrue(note.contains("1 of 3 workspace candidate files read in full"), note);
        assertTrue(note.contains("1 searched with matches: calc.py"), note);
        assertTrue(note.contains("unread: index.html"), note);
        assertTrue(!note.contains("unread: calc.py") && !note.contains("unread: index.html, calc.py"),
                "a searched file is not unread: " + note);
    }

    /** C2: candidates come from the listing outcome of this turn, not from listing text compaction removed. */
    @Test
    void enumeratedCandidatesComeFromTheListingOutcomeNotFromCompactedText(@TempDir Path workspace) throws Exception {
        Files.createDirectories(workspace.resolve("src"));
        Files.writeString(workspace.resolve("src").resolve("app.py"), "x\n");
        Files.writeString(workspace.resolve("README.md"), "readme\n");
        ToolCallLoop.LoopResult loopResult = loopResult(
                List.of(
                        outcomeWithSources("talos.list_dir", ".", List.of("README.md", "src/app.py")),
                        outcome("talos.read_file", "README.md")),
                List.of("README.md"));
        List<ChatMessage> messages = List.of(
                ChatMessage.user("What is this project?"),
                ChatMessage.toolResult("call_list",
                        "[compacted: talos.list_dir result, 40 chars - full output elided to keep context focused]"));

        String note = AnswerGroundingDisclosure.toolLoopDisclosure(
                loopResult, workspaceExplainPlan("What is this project?"), workspace, messages)
                .workspaceCandidateNote();

        assertTrue(note.contains("1 of 2 workspace candidate files read in full"), note);
        assertTrue(note.contains("unread: src/app.py"), note);
    }
}
