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
import dev.talos.runtime.obligation.RequestObligationCompiler;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolRegistry;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileWriteTool;
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
    void directVerifierExposesWorkspaceOperationFactsTargetsAndAliases() throws Exception {
        Files.writeString(workspace.resolve("notes.md"), "notes\n");
        Files.createDirectories(workspace.resolve("archive"));
        Files.writeString(workspace.resolve("archive/notes-copy.md"), "notes\n");

        WorkspaceOperationStaticVerifier.Result result = WorkspaceOperationStaticVerifier.verify(
                workspace,
                List.of(WorkspaceOperationPlan.copyPath(
                        "notes.md",
                        "archive/notes-copy.md",
                        WorkspaceOperationPlan.OverwritePolicy.FAIL_IF_EXISTS,
                        false)));

        assertTrue(result.problems().isEmpty(), result.problems().toString());
        assertTrue(result.facts().contains("copy source exists: notes.md."), result.facts().toString());
        assertTrue(result.facts().contains("copy destination exists: archive/notes-copy.md."),
                result.facts().toString());
        assertTrue(result.mutationTargets().contains("archive/notes-copy.md"), result.mutationTargets().toString());
        assertTrue(result.expectedTargetExemptions().contains("notes.md"),
                result.expectedTargetExemptions().toString());
        assertTrue(result.expectedTargetAliases().contains("notes-copy.md"),
                result.expectedTargetAliases().toString());
    }

    @Test
    void copyMoveRenameSequenceWithoutOneTypedOperationCannotMutateWorkspace() throws Exception {
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

        assertEquals(3, loopResult.toolOutcomes().size());
        assertTrue(loopResult.toolOutcomes().stream().noneMatch(ToolCallLoop.ToolOutcome::success),
                "a multi-operation request must not confer authority on any individual proposal");
        assertTrue(Files.exists(workspace.resolve("notes.md")));
        assertFalse(Files.exists(workspace.resolve("notes-copy.md")));
        assertFalse(Files.exists(workspace.resolve("archive/notes-copy.md")));
        assertFalse(Files.exists(workspace.resolve("archive/final-notes.md")));

        TaskVerificationResult verification = StaticTaskVerifier.verify(
                workspace,
                request,
                loopResult,
                0);

        assertEquals(TaskVerificationStatus.NOT_RUN, verification.status(), verification.problems().toString());
        assertTrue(verification.facts().isEmpty(), verification.facts().toString());
    }

    @Test
    void operationsJsonBatchWithoutTypedAuthorityCannotMutateWorkspace() throws Exception {
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

        assertEquals(1, loopResult.toolOutcomes().size());
        assertFalse(loopResult.toolOutcomes().getFirst().success());
        assertFalse(Files.exists(workspace.resolve("docs")));
        assertTrue(Files.exists(workspace.resolve("README.md")));
        assertTrue(Files.exists(workspace.resolve("source.txt")));
        assertFalse(Files.exists(workspace.resolve("docs/source.txt")));
        assertFalse(Files.exists(workspace.resolve("docs/final-source.txt")));

        TaskVerificationResult verification = StaticTaskVerifier.verify(
                workspace,
                request,
                loopResult,
                0);

        assertEquals(TaskVerificationStatus.NOT_RUN, verification.status(), verification.problems().toString());
        assertTrue(verification.facts().isEmpty(), verification.facts().toString());
    }

    @Test
    void naturalBatchWithoutTypedAuthorityCannotMutateWorkspace() throws Exception {
        Files.writeString(workspace.resolve("styles.css"), "body { color: black; }\n");

        String request = "batch this: create batch-one and batch-two, "
                + "then copy styles.css to batch-one/styles-copy.css.";
        ToolCallLoop.LoopResult loopResult = runLoop(
                request,
                tools(new BatchWorkspaceApplyTool()),
                """
                {"name":"talos.apply_workspace_batch","arguments":{"operations_json":"[
                  {\\"op\\":\\"mkdir\\",\\"path\\":\\"batch-one\\"},
                  {\\"op\\":\\"mkdir\\",\\"path\\":\\"batch-two\\"},
                  {\\"op\\":\\"copy_path\\",\\"from\\":\\"styles.css\\",\\"to\\":\\"batch-one/styles-copy.css\\"}
                ]"}}
                """);

        assertEquals(1, loopResult.toolOutcomes().size());
        assertFalse(loopResult.toolOutcomes().getFirst().success());
        assertFalse(Files.exists(workspace.resolve("batch-one")));
        assertFalse(Files.exists(workspace.resolve("batch-two")));
        assertTrue(Files.exists(workspace.resolve("styles.css")));

        TaskVerificationResult verification = StaticTaskVerifier.verify(
                workspace,
                request,
                loopResult,
                0);

        assertEquals(TaskVerificationStatus.NOT_RUN, verification.status(), verification.problems().toString());
        assertTrue(verification.facts().isEmpty(), verification.facts().toString());
    }

    @Test
    void deletePathVerifiesTargetIsAbsentFromToolLoopOutcome() throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(workspace.resolve("docs/old-plan.md"), "delete me\n");

        String request = "Delete docs/old-plan.md please.";
        ToolCallLoop.LoopResult loopResult = runLoop(
                request,
                tools(new DeletePathTool()),
                """
                {"name":"talos.delete_path","arguments":{"path":"docs/old-plan.md"}}
                """);

        assertFalse(Files.exists(workspace.resolve("docs/old-plan.md")));

        TaskVerificationResult verification = verifyWithTypedAuthority(
                request,
                loopResult);

        assertEquals(TaskVerificationStatus.READBACK_ONLY, verification.status(), verification.problems().toString());
        assertTrue(verification.problems().isEmpty(), verification.problems().toString());
        assertTrue(verification.facts().stream().anyMatch(f -> f.contains("deleted target absent: docs/old-plan.md")),
                verification.facts().toString());
    }

    @Test
    void pureTypedDeleteDoesNotInferAnUnrelatedArtifactProfileFromItsFilename()
            throws Exception {
        Files.createDirectories(workspace.resolve("docs"));
        Files.writeString(
                workspace.resolve("docs/synthwave-webpage-plan.md"),
                "delete me\n");

        String request = "Delete docs/synthwave-webpage-plan.md please.";
        ToolCallLoop.LoopResult loopResult = runLoop(
                request,
                tools(new DeletePathTool()),
                """
                {"name":"talos.delete_path","arguments":{"path":"docs/synthwave-webpage-plan.md"}}
                """);

        TaskVerificationResult verification = verifyWithTypedAuthority(
                request,
                loopResult);

        assertEquals(
                TaskVerificationStatus.READBACK_ONLY,
                verification.status(),
                verification.problems().toString());
        assertTrue(verification.problems().isEmpty(),
                verification.problems().toString());
        assertTrue(verification.facts().stream().anyMatch(fact ->
                        fact.contains(
                                "deleted target absent: docs/synthwave-webpage-plan.md")),
                verification.facts().toString());
    }

    @Test
    void genericWriteDoesNotSatisfyMoveOperationWhenSourceRemains() throws Exception {
        Files.createDirectories(workspace.resolve("workspace-notes"));
        Files.writeString(workspace.resolve("workspace-notes/readme-renamed.md"), "source\n");

        String request = "Move workspace-notes/readme-renamed.md to archive/readme-renamed.md.";
        ToolCallLoop.LoopResult loopResult = runLoop(
                request,
                tools(new FileWriteTool()),
                """
                {"name":"talos.write_file","arguments":{"path":"archive/readme-renamed.md","content":"source\\n"}}
                """);

        assertTrue(Files.exists(workspace.resolve("workspace-notes/readme-renamed.md")));
        assertFalse(Files.exists(workspace.resolve("archive/readme-renamed.md")));
        assertEquals(1, loopResult.toolOutcomes().size());
        assertFalse(loopResult.toolOutcomes().getFirst().success());

        TaskVerificationResult verification = verifyWithTypedAuthority(
                request,
                loopResult);

        assertEquals(TaskVerificationStatus.NOT_RUN, verification.status());
        assertTrue(verification.problems().isEmpty(), verification.problems().toString());
    }

    @Test
    void mkdirAtExactFileTargetFailsInsteadOfReadbackOnly() throws Exception {
        Files.createDirectories(workspace.resolve("workspace-notes/summary.txt"));

        String request = "Create a directory named workspace-notes and create workspace-notes/summary.txt "
                + "containing exactly created by audit.";
        WorkspaceOperationPlan mkdirPlan = WorkspaceOperationPlan.batch(
                WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY,
                List.of(WorkspaceOperationPlan.PathEffect.absentBefore(
                        "workspace-notes/summary.txt",
                        true,
                        WorkspaceOperationPlan.OperationKind.CREATE_DIRECTORY)),
                dev.talos.tools.ToolRiskLevel.WRITE,
                true,
                WorkspaceOperationPlan.OverwritePolicy.NOT_APPLICABLE,
                false,
                "Create directory workspace-notes/summary.txt.",
                "Mkdir: workspace-notes/summary.txt");
        ToolCallLoop.LoopResult loopResult = new ToolCallLoop.LoopResult(
                "Created the requested path.", 1, 1,
                List.of("talos.mkdir"), List.of(),
                1, 0, false, 1, List.of(),
                0, 0, 0, 0,
                List.of(new ToolCallLoop.ToolOutcome(
                        "talos.mkdir",
                        "workspace-notes/summary.txt",
                        true,
                        true,
                        false,
                        "Created directory workspace-notes/summary.txt",
                        "",
                        null,
                        "",
                        mkdirPlan)));

        TaskVerificationResult verification = StaticTaskVerifier.verify(
                workspace,
                request,
                loopResult,
                0);

        assertEquals(TaskVerificationStatus.FAILED, verification.status());
        assertTrue(verification.summary().contains("Exact content verification failed"),
                verification.summary());
        assertTrue(verification.problems().stream()
                        .anyMatch(problem -> problem.contains("workspace-notes/summary.txt")
                                && problem.contains("not a readable file")),
                verification.problems().toString());
    }

    private ToolCallLoop.LoopResult runLoop(String request, ToolRegistry registry, String initialResponse) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        TaskContract contract = resolution.contract();
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(resolution);

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

    private TaskVerificationResult verifyWithTypedAuthority(
            String request,
            ToolCallLoop.LoopResult loopResult
    ) {
        var resolution = TaskContractResolver.resolveFromUserRequest(request);
        return StaticTaskVerifier.verifyWithEvidence(
                        workspace,
                        resolution.contract(),
                        RequestObligationCompiler.compile(resolution.contract()),
                        loopResult,
                        0,
                        resolution.workspaceOperationPlan())
                .compatibilityResult();
    }

    private static ToolRegistry tools(dev.talos.tools.TalosTool... tools) {
        ToolRegistry registry = new ToolRegistry();
        for (dev.talos.tools.TalosTool tool : tools) {
            registry.register(tool);
        }
        return registry;
    }
}
