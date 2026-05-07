package dev.talos.runtime.verification;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.llm.LlmClient;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.NoOpApprovalGate;
import dev.talos.runtime.ToolCallLoop;
import dev.talos.runtime.TurnProcessor;
import dev.talos.runtime.TurnTaskContractCapture;
import dev.talos.runtime.TurnUserRequestCapture;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.impl.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.RenamePathTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkspaceOperationStaticVerifierTest {

    @TempDir
    Path workspace;

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
    }

    @Test
    void copyMoveRenameSequenceVerifiesFinalWorkspaceStateFromToolLoopOutcomes() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "notes\n");

        String request = "Copy notes.md to notes-copy.md, move notes-copy.md to archive/notes-copy.md, "
                + "then rename archive/notes-copy.md to final-notes.md.";
        ToolCallLoop.LoopResult loopResult = runLoop(
                request,
                tools(new CopyPathTool(), new MovePathTool(), new RenamePathTool()),
                """
                {"name":"talos.copy_path","arguments":{"from":"notes.md","to":"notes-copy.md"}}
                {"name":"talos.move_path","arguments":{"from":"notes-copy.md","to":"archive/notes-copy.md"}}
                {"name":"talos.rename_path","arguments":{"path":"archive/notes-copy.md","new_name":"final-notes.md"}}
                """);

        assertEquals(
                List.of("notes-copy.md", "archive/notes-copy.md", "archive/final-notes.md"),
                loopResult.toolOutcomes().stream().map(ToolCallLoop.ToolOutcome::pathHint).toList(),
                "workspace operation outcomes should expose resulting changed paths, not source paths");

        assertTrue(Files.exists(workspace.resolve("notes.md")));
        assertFalse(Files.exists(workspace.resolve("notes-copy.md")));
        assertFalse(Files.exists(workspace.resolve("archive/notes-copy.md")));
        assertEquals("notes\n", Files.readString(workspace.resolve("archive/final-notes.md")));

        TaskVerificationResult verification = StaticTaskVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(request),
                loopResult,
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verification.status(), verification.problems().toString());
        assertTrue(verification.problems().isEmpty(), verification.problems().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("copy source exists: notes.md")),
                verification.facts().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("move source absent: notes-copy.md")),
                verification.facts().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("rename destination exists: archive/final-notes.md")),
                verification.facts().toString());

    }

    @Test
    void batchWorkspaceApplyVerifiesPerOperationTargetsFromToolLoopOutcome() throws Exception {
        Files.writeString(workspace.resolve("README.md"), "# Fixture\n");
        Files.writeString(workspace.resolve("source.txt"), "source\n");

        String request = "Use talos.apply_workspace_batch only. Apply operations_json for exactly these operations: "
                + "mkdir docs, copy README.md to docs/README.md, move source.txt to docs/source.txt, "
                + "rename docs/source.txt to final-source.txt.";
        ToolCallLoop.LoopResult loopResult = runLoop(
                request,
                tools(new BatchWorkspaceApplyTool()),
                """
                {"name":"talos.apply_workspace_batch","arguments":{"operations_json":"[
                  {\\"op\\":\\"mkdir\\",\\"path\\":\\"docs\\"},
                  {\\"op\\":\\"copy_path\\",\\"from\\":\\"README.md\\",\\"to\\":\\"docs/README.md\\"},
                  {\\"op\\":\\"move_path\\",\\"from\\":\\"source.txt\\",\\"to\\":\\"docs/source.txt\\"},
                  {\\"op\\":\\"rename_path\\",\\"path\\":\\"docs/source.txt\\",\\"new_name\\":\\"final-source.txt\\"}
                ]"}}
                """);

        assertTrue(Files.isDirectory(workspace.resolve("docs")));
        assertTrue(Files.exists(workspace.resolve("README.md")));
        assertEquals("# Fixture\n", Files.readString(workspace.resolve("docs/README.md")));
        assertFalse(Files.exists(workspace.resolve("source.txt")));
        assertFalse(Files.exists(workspace.resolve("docs/source.txt")));
        assertEquals("source\n", Files.readString(workspace.resolve("docs/final-source.txt")));

        TaskVerificationResult verification = StaticTaskVerifier.verify(
                workspace,
                TaskContractResolver.fromUserRequest(request),
                loopResult,
                0);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verification.status(), verification.problems().toString());
        assertTrue(verification.problems().isEmpty(), verification.problems().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("directory exists: docs")),
                verification.facts().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("copy destination exists: docs/README.md")),
                verification.facts().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("move source absent: source.txt")),
                verification.facts().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("rename destination exists: docs/final-source.txt")),
                verification.facts().toString());
    }

    private ToolCallLoop.LoopResult runLoop(String request, ToolRegistry registry, String initialResponse) {
        TaskContract contract = TaskContractResolver.fromUserRequest(request);
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(contract);

        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                new NoOpApprovalGate(),
                registry);
        ToolCallLoop loop = new ToolCallLoop(processor, 10);
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, Map.of()))
                .llm(LlmClient.scripted(List.of("")))
                .build();
        var messages = new ArrayList<>(List.of(
                dev.talos.spi.types.ChatMessage.system("sys"),
                dev.talos.spi.types.ChatMessage.user(request)));

        return loop.run(initialResponse, messages, workspace, context);
    }

    private static ToolRegistry tools(dev.talos.tools.TalosTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (dev.talos.tools.TalosTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }
}
