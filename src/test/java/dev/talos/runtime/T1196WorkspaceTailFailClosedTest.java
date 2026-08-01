package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.intent.TargetRole;
import dev.talos.runtime.task.TaskContract;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.task.TaskType;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.policy.ActionObligation;
import dev.talos.runtime.policy.ActionObligationPolicy;
import dev.talos.runtime.toolcall.NativeToolSpecPolicy;
import dev.talos.runtime.trace.ActiveToolAuthorityTraceTest;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolFailureReason;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.TalosTool;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.DeletePathTool;
import dev.talos.tools.impl.FileEditTool;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import dev.talos.tools.impl.RenamePathTool;
import dev.talos.tools.impl.ReadFileTool;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1196WorkspaceTailFailClosedTest extends ActiveToolAuthorityTraceTest {

    @AfterEach
    void clearTurnState() {
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
    }

    @Test
    void typedBlockedWorkspaceTailCannotReachApprovalOrMutation(
            @TempDir Path workspace
    ) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new CopyPathTool());
        ApprovalGate approvalGate = (description, detail) -> {
            approvals.incrementAndGet();
            return true;
        };
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                approvalGate,
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request =
                "Edit index.html so it works, then copy style.css to script.js.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.copy_path",
                        Map.of("from", "style.css", "to", "script.js")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                result.error().reason());
        assertTrue(
                result.errorMessage().contains(
                        "Coordinated workspace-operation tails remain blocked"),
                result.errorMessage());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(workspace.resolve("script.js")));
        assertEquals("body {}\n", Files.readString(workspace.resolve("style.css")));
    }

    @Test
    void blockedWorkspaceActionCannotEraseSamePathContentAuthority(
            @TempDir Path workspace
    ) throws Exception {
        Path target = workspace.resolve("style.css");
        Files.writeString(target, "body {}\n");
        String request = "Edit index.html so update style.css, then delete style.css.";
        var resolution = TaskContractResolver.resolveFromUserRequest(request);

        assertTrue(resolution.blocksWorkspaceOperations());
        assertEquals(
                TargetRole.MUST_MUTATE,
                resolution.intent().targets().find("style.css")
                        .orElseThrow().role());
        assertEquals(
                ActionObligation.MUTATING_TOOL_REQUIRED,
                ActionObligationPolicy.derive(
                        resolution,
                        ExecutionPhase.APPLY));

        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileEditTool());
        registry.register(new DeletePathTool());
        List<String> visible = NativeToolSpecPolicy.names(
                NativeToolSpecPolicy.select(
                        resolution,
                        ExecutionPhase.APPLY,
                        registry,
                        new Config()));
        assertTrue(visible.contains("talos.edit_file"));
        assertFalse(visible.contains("talos.delete_path"));

        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(resolution);

        ToolResult edit = processor.executeTool(
                session,
                new ToolCall(
                        "talos.edit_file",
                        Map.of(
                                "path", "style.css",
                                "old_string", "body {}",
                                "new_string", "body { color: red; }")),
                context);
        ToolResult delete = processor.executeTool(
                session,
                new ToolCall(
                        "talos.delete_path",
                        Map.of("path", "style.css")),
                context);

        assertTrue(edit.success(), edit.errorMessage());
        assertFalse(delete.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                delete.error().reason());
        assertEquals(1, approvals.get());
        assertEquals("body { color: red; }\n", Files.readString(target));
    }

    @Test
    void everyWorkspaceOperationFamilyIsRejectedByTheTypedPlanBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
        Files.createDirectories(workspace.resolve("docs"));
        List<BlockedCall> cases = List.of(
                new BlockedCall(
                        new MakeDirectoryTool(),
                        "Make it work so create a directory named generated.",
                        new ToolCall("talos.mkdir", Map.of("path", "generated"))),
                new BlockedCall(
                        new MakeDirectoryTool(),
                        "Make it work so create me a new folder generated.",
                        new ToolCall("talos.mkdir", Map.of("path", "generated"))),
                new BlockedCall(
                        new MakeDirectoryTool(),
                        "Edit index.html so it works, then create .config and assets, "
                                + "then copy style.css to .config/style.css.",
                        new ToolCall("talos.mkdir", Map.of("path", ".config"))),
                new BlockedCall(
                        new MakeDirectoryTool(),
                        "Edit index.html so it works, then create docs.v1 and assets, "
                                + "then copy style.css to docs.v1/style.css.",
                        new ToolCall("talos.mkdir", Map.of("path", "docs.v1"))),
                new BlockedCall(
                        new CopyPathTool(),
                        "Edit index.html so it works, then copy style.css to script.js.",
                        new ToolCall(
                                "talos.copy_path",
                                Map.of("from", "style.css", "to", "script.js"))),
                new BlockedCall(
                        new MovePathTool(),
                        "Edit index.html so it works, then move style.css to script.js.",
                        new ToolCall(
                                "talos.move_path",
                                Map.of("from", "style.css", "to", "script.js"))),
                new BlockedCall(
                        new RenamePathTool(),
                        "Edit index.html so it works, then rename style.css to script.js.",
                        new ToolCall(
                                "talos.rename_path",
                                Map.of("path", "style.css", "new_name", "script.js"))),
                new BlockedCall(
                        new DeletePathTool(),
                        "Edit index.html so it works, then delete style.css.",
                        new ToolCall("talos.delete_path", Map.of("path", "style.css"))),
                new BlockedCall(
                        new DeletePathTool(),
                        "Edit index.html so it works, then rm style.css.",
                        new ToolCall("talos.delete_path", Map.of("path", "style.css"))),
                new BlockedCall(
                        new MakeDirectoryTool(),
                        "Edit index.html so it works, then create docs directory.",
                        new ToolCall("talos.mkdir", Map.of("path", "docs"))),
                new BlockedCall(
                        new CopyPathTool(),
                        "Edit index.html so it works, then copy docs to backup.",
                        new ToolCall(
                                "talos.copy_path",
                                Map.of("from", "docs", "to", "backup"))),
                new BlockedCall(
                        new MovePathTool(),
                        "Edit index.html so it works, then move docs to backup.",
                        new ToolCall(
                                "talos.move_path",
                                Map.of("from", "docs", "to", "backup"))),
                new BlockedCall(
                        new RenamePathTool(),
                        "Edit index.html so it works, then rename docs to backup.",
                        new ToolCall(
                                "talos.rename_path",
                                Map.of("path", "docs", "new_name", "backup"))),
                new BlockedCall(
                        new DeletePathTool(),
                        "Edit index.html so it works, then delete docs.",
                        new ToolCall("talos.delete_path", Map.of("path", "docs"))),
                new BlockedCall(
                        new DeletePathTool(),
                        "Edit index.html so it works, then remove docs.",
                        new ToolCall("talos.delete_path", Map.of("path", "docs"))),
                new BlockedCall(
                        new DeletePathTool(),
                        "Edit index.html so it works, then rm docs.",
                        new ToolCall("talos.delete_path", Map.of("path", "docs"))),
                new BlockedCall(
                        new BatchWorkspaceApplyTool(),
                        "Edit index.html so it works, then copy style.css to script.js.",
                        new ToolCall(
                                "talos.apply_workspace_batch",
                                Map.of(
                                        "operations_json",
                                        """
                                        [{"op":"copy_path","from":"style.css",
                                          "to":"script.js"}]
                                        """))),
                new BlockedCall(
                        new MakeDirectoryTool(),
                        "Edit index.html so it works, but do not create a directory named generated.",
                        new ToolCall("talos.mkdir", Map.of("path", "generated"))),
                new BlockedCall(
                        new CopyPathTool(),
                        "Edit index.html so it works, but do not copy style.css to script.js.",
                        new ToolCall(
                                "talos.copy_path",
                                Map.of("from", "style.css", "to", "script.js"))),
                new BlockedCall(
                        new MovePathTool(),
                        "Edit index.html so it works, but do not move style.css to script.js.",
                        new ToolCall(
                                "talos.move_path",
                                Map.of("from", "style.css", "to", "script.js"))),
                new BlockedCall(
                        new RenamePathTool(),
                        "Edit index.html so it works, but do not rename style.css to script.js.",
                        new ToolCall(
                                "talos.rename_path",
                                Map.of("path", "style.css", "new_name", "script.js"))),
                new BlockedCall(
                        new DeletePathTool(),
                        "Edit index.html so it works, but do not delete style.css.",
                        new ToolCall("talos.delete_path", Map.of("path", "style.css"))),
                new BlockedCall(
                        new BatchWorkspaceApplyTool(),
                        "Edit index.html so it works, but do not copy style.css to script.js.",
                        new ToolCall(
                                "talos.apply_workspace_batch",
                                Map.of(
                                        "operations_json",
                                        """
                                        [{"op":"copy_path","from":"style.css",
                                          "to":"script.js"}]
                                        """))));

        for (BlockedCall testCase : cases) {
            AtomicInteger approvals = new AtomicInteger();
            ToolRegistry registry = new ToolRegistry();
            registry.register(testCase.tool());
            ApprovalGate approvalGate = (description, detail) -> {
                approvals.incrementAndGet();
                return true;
            };
            TurnProcessor processor = new TurnProcessor(
                    ModeController.defaultController(),
                    approvalGate,
                    registry);
            Session session = new Session(workspace, new Config());
            Context context = Context.builder(new Config())
                    .sandbox(new Sandbox(workspace, null))
                    .build();
            TurnUserRequestCapture.set(testCase.request());
            TurnTaskContractCapture.set(
                    assertDoesNotThrow(
                            () -> TaskContractResolver.resolveFromUserRequest(
                                    testCase.request()),
                            testCase.request()));

            ToolResult result = processor.executeTool(
                    session,
                    testCase.call(),
                    context);

            assertFalse(result.success(), testCase.request());
            assertEquals(
                    testCase.request().contains("do not")
                            ? ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN
                            : ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    result.error().reason(),
                    testCase.request());
            assertEquals(0, approvals.get(), testCase.request());
            assertFalse(Files.exists(workspace.resolve("generated")));
            assertFalse(Files.exists(workspace.resolve(".config")));
            assertFalse(Files.exists(workspace.resolve("docs.v1")));
            assertFalse(Files.exists(workspace.resolve("script.js")));
            assertFalse(Files.exists(workspace.resolve("backup")));
            assertTrue(Files.isDirectory(workspace.resolve("docs")));
            assertEquals(
                    "body {}\n",
                    Files.readString(workspace.resolve("style.css")),
                    testCase.call().toolName());
        }
    }

    @Test
    void pureBlockedWorkspaceTailRejectsInjectedContentMutationBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path target = workspace.resolve("victim.txt");
        Files.writeString(target, "original\n");
        String request =
                "Make it work so create a directory named generated.";

        for (BlockedCall testCase : List.of(
                new BlockedCall(
                        new FileWriteTool(),
                        request,
                        new ToolCall(
                                "talos.write_file",
                                Map.of(
                                        "path", "victim.txt",
                                        "content", "replaced\n"))),
                new BlockedCall(
                        new FileEditTool(),
                        request,
                        new ToolCall(
                                "talos.edit_file",
                                Map.of(
                                        "path", "victim.txt",
                                        "old_string", "original",
                                        "new_string", "changed"))))) {
            AtomicInteger approvals = new AtomicInteger();
            ToolRegistry registry = new ToolRegistry();
            registry.register(testCase.tool());
            ApprovalGate approvalGate = (description, detail) -> {
                approvals.incrementAndGet();
                return true;
            };
            TurnProcessor processor = new TurnProcessor(
                    ModeController.defaultController(),
                    approvalGate,
                    registry);
            Session session = new Session(workspace, new Config());
            Context context = Context.builder(new Config())
                    .sandbox(new Sandbox(workspace, null))
                    .build();
            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromUserRequest(request));

            ToolResult result = processor.executeTool(
                    session,
                    testCase.call(),
                    context);

            assertFalse(result.success(), testCase.call().toolName());
            assertEquals(
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    result.error().reason(),
                    testCase.call().toolName());
            assertEquals(0, approvals.get(), testCase.call().toolName());
            assertEquals(
                    "original\n",
                    Files.readString(target),
                    testCase.call().toolName());
            assertFalse(Files.exists(workspace.resolve("generated")));
        }
    }

    @Test
    void caseSensitiveWorkspaceRejectsSourceCaseVariantAsOutputBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path upperProbe = workspace.resolve(".talos-case-probe-A");
        Path lowerProbe = workspace.resolve(".talos-case-probe-a");
        Files.writeString(upperProbe, "upper");
        Files.writeString(lowerProbe, "lower");
        Assumptions.assumeFalse(
                sameNoFollowFileKey(upperProbe, lowerProbe),
                "requires a verified case-sensitive filesystem");
        Assumptions.assumeFalse(
                Files.readString(upperProbe).equals(
                        Files.readString(lowerProbe)),
                "requires observably distinct case-sensitive entries");
        Files.delete(upperProbe);
        Files.delete(lowerProbe);

        Path source = workspace.resolve("a.md");
        Files.writeString(source, "source bytes\n");
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new FileWriteTool());
        ApprovalGate approvalGate = (description, detail) -> {
            approvals.incrementAndGet();
            return true;
        };
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                approvalGate,
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Create A.md based on a.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult read = processor.executeTool(
                session,
                new ToolCall(
                        "talos.read_file",
                        Map.of("path", "a.md")),
                context);
        ToolResult write = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "a.md",
                                "content", "mutated source\n")),
                context);

        assertTrue(read.success(), read.errorMessage());
        assertFalse(write.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                write.error().reason());
        assertEquals(0, approvals.get());
        assertEquals("source bytes\n", Files.readString(source));
        assertFalse(Files.exists(workspace.resolve("A.md")));

        ToolResult correctWrite = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "A.md",
                                "content", "authorized output\n")),
                context);

        assertTrue(correctWrite.success(), correctWrite.errorMessage());
        assertEquals(1, approvals.get());
        assertEquals(
                "authorized output\n",
                Files.readString(workspace.resolve("A.md")));
        assertEquals("source bytes\n", Files.readString(source));
    }

    @Test
    void outsideTargetDiagnosticPreservesExpectedTargetCase(
            @TempDir Path workspace
    ) {
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Create A.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "B.md",
                                "content", "outside\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                result.error().reason());
        assertTrue(
                result.errorMessage().contains(
                        "current expected target set: A.md."),
                result.errorMessage());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(workspace.resolve("A.md")));
        assertFalse(Files.exists(workspace.resolve("B.md")));
    }

    @Test
    void caseSensitiveWorkspaceRejectsConstraintCaseVariantBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path upperProbe = workspace.resolve(".talos-case-probe-A");
        Path lowerProbe = workspace.resolve(".talos-case-probe-a");
        Files.writeString(upperProbe, "upper");
        Files.writeString(lowerProbe, "lower");
        Assumptions.assumeFalse(
                sameNoFollowFileKey(upperProbe, lowerProbe),
                "requires a verified case-sensitive filesystem");
        Assumptions.assumeFalse(
                Files.readString(upperProbe).equals(
                        Files.readString(lowerProbe)),
                "requires observably distinct case-sensitive entries");
        Files.delete(upperProbe);
        Files.delete(lowerProbe);

        Path required = workspace.resolve("A.md");
        Path constraint = workspace.resolve("a.md");
        Files.writeString(required, "required bytes\n");
        Files.writeString(constraint, "constraint bytes\n");
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request =
                "Update A.md so a.md remains valid, and maybe update style.css.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult rejected = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "a.md",
                                "content", "unauthorized\n")),
                context);

        assertFalse(rejected.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                rejected.error().reason());
        assertTrue(
                rejected.errorMessage().contains(
                        "current expected target set: A.md."),
                rejected.errorMessage());
        assertEquals(0, approvals.get());
        assertEquals("required bytes\n", Files.readString(required));
        assertEquals("constraint bytes\n", Files.readString(constraint));

        ToolResult allowed = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "A.md",
                                "content", "authorized\n")),
                context);

        assertTrue(allowed.success(), allowed.errorMessage());
        assertEquals(1, approvals.get());
        assertEquals("authorized\n", Files.readString(required));
        assertEquals("constraint bytes\n", Files.readString(constraint));
    }

    @Test
    void observedSameFileCaseAliasRemainsAllowedOnCaseInsensitiveWorkspace(
            @TempDir Path workspace
    ) throws Exception {
        Path expected = workspace.resolve("A.md");
        Path alias = workspace.resolve("a.md");
        Files.writeString(expected, "original\n");
        Assumptions.assumeTrue(
                Files.exists(alias)
                        && "original\n".equals(Files.readString(alias)),
                "requires an observed case-insensitive filesystem alias");

        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update A.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "a.md",
                                "content", "updated\n")),
                context);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
        assertEquals("updated\n", Files.readString(expected));
        assertEquals("updated\n", Files.readString(alias));
    }

    @Test
    void caseSensitiveWorkspaceKeepsRequiredAndForbiddenCaseVariantsDistinct(
            @TempDir Path workspace
    ) throws Exception {
        Path upper = workspace.resolve("A.md");
        Path lower = workspace.resolve("a.md");
        Files.writeString(upper, "upper\n");
        Files.writeString(lower, "lower\n");
        Assumptions.assumeFalse(
                sameNoFollowFileKey(upper, lower),
                "requires distinct case-sensitive file identities");
        Assumptions.assumeFalse(
                Files.readString(upper).equals(Files.readString(lower)),
                "requires observably distinct case-sensitive entries");

        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update A.md so do not edit a.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult allowed = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "A.md",
                                "content", "updated upper\n")),
                context);
        ToolResult forbidden = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "a.md",
                                "content", "forbidden lower\n")),
                context);

        assertTrue(allowed.success(), allowed.errorMessage());
        assertFalse(forbidden.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN,
                forbidden.error().reason());
        assertEquals(1, approvals.get());
        assertEquals("updated upper\n", Files.readString(upper));
        assertEquals("lower\n", Files.readString(lower));
    }

    @Test
    void observedCaseInsensitiveAliasHonorsCaseVariantProhibition(
            @TempDir Path workspace
    ) throws Exception {
        Path upper = workspace.resolve("A.md");
        Path lower = workspace.resolve("a.md");
        Files.writeString(upper, "original\n");
        Assumptions.assumeTrue(
                Files.exists(lower)
                        && "original\n".equals(Files.readString(lower)),
                "requires an observed case-insensitive filesystem alias");

        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update A.md so do not edit a.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "A.md",
                                "content", "forbidden\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertEquals("original\n", Files.readString(upper));
    }

    @Test
    void absentCaseInsensitiveAliasCannotEvadeCaseVariantProhibition(
            @TempDir Path workspace
    ) throws Exception {
        Assumptions.assumeTrue(
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"),
                "requires Windows path semantics");
        Path probe = workspace.resolve("Case-Probe.md");
        Path probeAlias = workspace.resolve("case-probe.md");
        Files.writeString(probe, "probe\n");
        Assumptions.assumeTrue(
                Files.exists(probeAlias)
                        && "probe\n".equals(Files.readString(probeAlias)),
                "requires an observed case-insensitive filesystem");

        Path upper = workspace.resolve("Absent-Alias.md");
        Path lower = workspace.resolve("absent-alias.md");
        assertFalse(Files.exists(upper));
        assertFalse(Files.exists(lower));

        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request =
                "Update Absent-Alias.md so do not edit absent-alias.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "Absent-Alias.md",
                                "content", "forbidden\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(upper));
        assertFalse(Files.exists(lower));
    }

    @Test
    void structuredPathPunctuationCannotAliasExpectedTargetBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Create A.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "A.md!",
                                "content", "outside authority\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(workspace.resolve("A.md")));
        assertFalse(Files.exists(workspace.resolve("A.md!")));
    }

    @Test
    void linuxStructuredBackslashCannotAliasExpectedSeparatorBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Assumptions.assumeFalse(
                System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("win"),
                "requires a platform where backslash is an ordinary path character");
        Files.createDirectories(workspace.resolve("docs"));
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Create docs/A.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "docs\\A.md",
                                "content", "outside authority\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(workspace.resolve("docs").resolve("A.md")));
        assertFalse(Files.exists(workspace.resolve("docs\\A.md")));
    }

    @Test
    void inWorkspaceSymlinkTargetCannotInheritAliasAuthorityBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path victim = workspace.resolve("victim.md");
        Path alias = workspace.resolve("alias.md");
        Files.writeString(victim, "original\n");
        try {
            Files.createSymbolicLink(alias, Path.of("victim.md"));
        } catch (Exception unsupported) {
            Assumptions.assumeTrue(false, "symbolic-link fixture unavailable");
        }
        Assumptions.assumeTrue(Files.isSymbolicLink(alias));

        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update alias.md.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "victim.md",
                                "content", "outside authority\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertEquals("original\n", Files.readString(victim));
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void exactLinkedFinalForbiddenPathFailsBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path victim = workspace.resolve("victim.md");
        Path alias = workspace.resolve("alias.md");
        Files.writeString(victim, "original\n");
        try {
            Files.createSymbolicLink(alias, Path.of("victim.md"));
        } catch (Exception unsupported) {
            Assumptions.assumeTrue(false, "symbolic-link fixture unavailable");
        }
        Assumptions.assumeTrue(Files.isSymbolicLink(alias));

        assertLinkedForbiddenPathBlockedBeforeApproval(
                workspace,
                "alias.md",
                victim);
        assertTrue(Files.isSymbolicLink(alias));
    }

    @Test
    void linkedAncestorForbiddenPathFailsBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path realDirectory = workspace.resolve("real");
        Path linkedDirectory = workspace.resolve("linked");
        Files.createDirectories(realDirectory);
        Path victim = realDirectory.resolve("style.css");
        Files.writeString(victim, "original\n");
        try {
            Files.createSymbolicLink(linkedDirectory, Path.of("real"));
        } catch (Exception unsupported) {
            Assumptions.assumeTrue(false, "symbolic-link fixture unavailable");
        }
        Assumptions.assumeTrue(Files.isSymbolicLink(linkedDirectory));

        assertLinkedForbiddenPathBlockedBeforeApproval(
                workspace,
                "linked/style.css",
                victim);
        assertTrue(Files.isSymbolicLink(linkedDirectory));
    }

    @Test
    void ordinaryNestedExpectedTargetRetainsExactAuthority(
            @TempDir Path workspace
    ) throws Exception {
        Path nested = workspace.resolve("docs").resolve("style.css");
        Files.createDirectories(nested.getParent());
        Files.writeString(nested, "body {}\n");
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileEditTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update docs/style.css.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.edit_file",
                        Map.of(
                                "path", "docs/style.css",
                                "old_string", "body {}",
                                "new_string", "body { color: blue; }")),
                context);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
        assertEquals(
                "body { color: blue; }\n",
                Files.readString(nested));
    }

    private static void assertLinkedForbiddenPathBlockedBeforeApproval(
            Path workspace,
            String forbiddenPath,
            Path victim
    ) throws Exception {
        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update workspace files, but do not edit "
                + forbiddenPath + ".";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(new TaskContract(
                TaskType.FILE_EDIT,
                true,
                true,
                false,
                Set.of(),
                Set.of(forbiddenPath),
                request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", forbiddenPath,
                                "content", "forbidden\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_FORBIDDEN,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertEquals("original\n", Files.readString(victim));
    }

    @Test
    void linkedAncestorCannotTransferExpectedAuthorityBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Path realDirectory = workspace.resolve("real");
        Path linkedDirectory = workspace.resolve("linked");
        Files.createDirectories(realDirectory);
        Path victim = realDirectory.resolve("style.css");
        Files.writeString(victim, "original\n");
        try {
            Files.createSymbolicLink(linkedDirectory, Path.of("real"));
        } catch (Exception unsupported) {
            Assumptions.assumeTrue(false, "symbolic-link fixture unavailable");
        }
        Assumptions.assumeTrue(Files.isSymbolicLink(linkedDirectory));

        AtomicInteger approvals = new AtomicInteger();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
        TurnProcessor processor = new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
        Session session = new Session(workspace, new Config());
        Context context = Context.builder(new Config())
                .sandbox(new Sandbox(workspace, null))
                .build();
        String request = "Update linked/style.css.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));

        ToolResult result = processor.executeTool(
                session,
                new ToolCall(
                        "talos.write_file",
                        Map.of(
                                "path", "real/style.css",
                                "content", "outside authority\n")),
                context);

        assertFalse(result.success());
        assertEquals(
                ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                result.error().reason());
        assertEquals(0, approvals.get());
        assertEquals("original\n", Files.readString(victim));
        assertTrue(Files.isSymbolicLink(linkedDirectory));
    }

    @Test
    void naturalWorkspaceBatchVariantsRejectInjectedWritesBeforeApproval(
            @TempDir Path workspace
    ) throws Exception {
        Files.writeString(workspace.resolve("style.css"), "body {}\n");
        List<InjectedWriteCase> cases = List.of(
                new InjectedWriteCase(
                        "Edit index.html so it works, then create \".config\" "
                                + "and assets, then copy style.css to "
                                + ".config/style.css.",
                        ".config"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create `docs.v1` "
                                + "and assets, then move style.css to "
                                + "docs.v1/style.css.",
                        "docs.v1"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create 'docs.v1' "
                                + "and assets, then rename style.css to "
                                + "docs.v1/style.css.",
                        "docs.v1"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create ``docs.v1`` "
                                + "and assets, then copy style.css to "
                                + "docs.v1/style.css.",
                        "docs.v1"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs and/or "
                                + "assets, then copy style.css to "
                                + "docs/style.css.",
                        "docs"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs or "
                                + "assets, then copy style.css to "
                                + "docs/style.css.",
                        "docs"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs, assets, "
                                + "and cache, then copy style.css to "
                                + "docs/style.css.",
                        "cache"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs and "
                                + "assets, and copy style.css to "
                                + "docs/style.css.",
                        "assets"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs assets; "
                                + "then copy style.css to docs/style.css.",
                        "assets"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs first "
                                + "and assets, then copy style.css to "
                                + "docs/style.css.",
                        "assets"),
                new InjectedWriteCase(
                        "Edit index.html so it works, then create docs and "
                                + "assets, then delete old.txt.",
                        "assets"));

        for (InjectedWriteCase testCase : cases) {
            AtomicInteger approvals = new AtomicInteger();
            ToolRegistry registry = new ToolRegistry();
            registry.register(new FileWriteTool());
            TurnProcessor processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        return true;
                    },
                    registry);
            Session session = new Session(workspace, new Config());
            Context context = Context.builder(new Config())
                    .sandbox(new Sandbox(workspace, null))
                    .build();
            TurnUserRequestCapture.set(testCase.request());
            TurnTaskContractCapture.set(
                    TaskContractResolver.resolveFromUserRequest(
                            testCase.request()));

            ToolResult result = processor.executeTool(
                    session,
                    new ToolCall(
                            "talos.write_file",
                            Map.of(
                                    "path", testCase.path(),
                                    "content", "injected\n")),
                    context);

            assertFalse(result.success(), testCase.request());
            assertEquals(
                    ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                    result.error().reason(),
                    testCase.request());
            assertEquals(0, approvals.get(), testCase.request());
            assertFalse(
                    Files.exists(workspace.resolve(testCase.path())),
                    testCase.request());
            assertEquals(
                    "body {}\n",
                    Files.readString(workspace.resolve("style.css")),
                    testCase.request());
        }
    }

    @Test
    void pureOptionalProposalAndReportedTailsCannotExposeOrExecuteMutation(
            @TempDir Path workspace
    ) throws Exception {
        Path target = workspace.resolve("style.css");
        Files.writeString(target, "original\n");
        for (String request : List.of(
                "Review index.html so maybe update style.css.",
                "Inspect index.html so should I update style.css?",
                "Review index.html so the guide says update style.css.",
                "Review index.html so the output says: update style.css.",
                "Review index.html so the result reads update style.css.",
                "Review index.html so diagnostic output says update style.css.",
                "Review index.html so tool output says: update style.css.",
                "Review index.html so our build file style.css still works.",
                "Review index.html so the production scaffold file style.css "
                        + "still works.",
                "Review index.html so our build style.css still works.",
                "Review index.html so production build style.css still works.",
                "Review index.html so my update style.css remains accurate.")) {
            var resolution =
                    TaskContractResolver.resolveFromUserRequest(request);
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ReadFileTool());
            registry.register(new FileWriteTool());
            registry.register(new FileEditTool());
            List<String> names = NativeToolSpecPolicy.names(
                    NativeToolSpecPolicy.select(
                            resolution,
                            ExecutionPhase.APPLY,
                            registry,
                            new Config()));

            assertFalse(names.contains("talos.write_file"), request);
            assertFalse(names.contains("talos.edit_file"), request);
            assertEquals(
                    ActionObligation.NONE,
                    ActionObligationPolicy.derive(
                            resolution,
                            ExecutionPhase.APPLY),
                    request);
            assertTrue(resolution.limitsMutationAuthority(), request);
            assertTrue(resolution.blocksUnscopedMutation(), request);
            assertEquals(
                    TargetRole.MENTIONED_ONLY,
                    resolution.intent().targets().find("style.css")
                            .orElseThrow().role(),
                    request);

            AtomicInteger approvals = new AtomicInteger();
            TurnProcessor processor = new TurnProcessor(
                    ModeController.defaultController(),
                    (description, detail) -> {
                        approvals.incrementAndGet();
                        return true;
                    },
                    registry);
            Session session = new Session(workspace, new Config());
            Context context = Context.builder(new Config())
                    .sandbox(new Sandbox(workspace, null))
                    .build();
            TurnUserRequestCapture.set(request);
            TurnTaskContractCapture.set(resolution);

            ToolResult result = processor.executeTool(
                    session,
                    new ToolCall(
                            "talos.write_file",
                            Map.of(
                                    "path", "style.css",
                                    "content", "changed\n")),
                    context);

            assertFalse(result.success(), request);
            if (result.error().reason() == ToolFailureReason.NONE) {
                assertTrue(
                        result.errorMessage().contains(
                                "did not ask to modify files"),
                        request + " -> " + result.errorMessage());
            } else {
                assertEquals(
                        ToolFailureReason.PRE_APPROVAL_TARGET_OUTSIDE_EXPECTED,
                        result.error().reason(),
                        request);
            }
            assertEquals(0, approvals.get(), request);
            assertEquals("original\n", Files.readString(target), request);
        }
    }

    private static boolean sameNoFollowFileKey(Path first, Path second)
            throws java.io.IOException {
        java.nio.file.attribute.BasicFileAttributes firstAttributes =
                Files.readAttributes(
                        first,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
        java.nio.file.attribute.BasicFileAttributes secondAttributes =
                Files.readAttributes(
                        second,
                        java.nio.file.attribute.BasicFileAttributes.class,
                        java.nio.file.LinkOption.NOFOLLOW_LINKS);
        Object firstKey = firstAttributes.fileKey();
        Object secondKey = secondAttributes.fileKey();
        return firstKey != null
                && secondKey != null
                && firstKey.equals(secondKey);
    }

    private record BlockedCall(TalosTool tool, String request, ToolCall call) {}

    private record InjectedWriteCase(String request, String path) {}
}
