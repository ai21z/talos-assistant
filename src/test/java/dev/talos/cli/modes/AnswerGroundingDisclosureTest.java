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
}
