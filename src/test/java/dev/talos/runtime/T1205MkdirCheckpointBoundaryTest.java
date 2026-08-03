package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.runtime.intent.WorkspaceOperationObligation;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.runtime.workspace.WorkspaceOperationAuthorityPlan;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.CopyPathTool;
import dev.talos.tools.impl.MakeDirectoryTool;
import dev.talos.tools.impl.MovePathTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class T1205MkdirCheckpointBoundaryTest {

    private static final String REQUEST =
            "Create a directory named docs/reports.";

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
    }

    @Test
    void parserRequiresAnExistingParentForTypedMkdir() {
        var resolution = TaskContractResolver.resolveFromUserRequest(REQUEST);

        assertEquals(
                WorkspaceOperationAuthorityPlan.State.AUTHORIZED_SINGLE,
                resolution.workspaceOperationPlan().state());
        WorkspaceOperationObligation operation = resolution
                .workspaceOperationPlan()
                .authorizedOperations()
                .getFirst();
        assertEquals(WorkspaceOperationObligation.Kind.MKDIR, operation.kind());
        assertEquals("docs/reports", operation.targetPath());
        assertEquals(
                WorkspaceOperationObligation.ParentPolicy.REQUIRE_EXISTING_PARENT,
                operation.parentPolicy());
    }

    @Test
    void missingParentFailsBeforeApprovalAndCreatesNothing(
            @TempDir Path temp
    ) {
        Path workspace = temp.resolve("workspace");
        assertTrue(workspace.toFile().mkdir());
        AtomicInteger approvals = new AtomicInteger();
        Config config = config(true);
        TurnProcessor processor = processor(
                approvals,
                new CheckpointService(new FileBundleCheckpointStore(
                        temp.resolve("checkpoints"))));
        capture(REQUEST);

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.mkdir", Map.of("path", "docs/reports")),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("existing ordinary"),
                result.errorMessage());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(workspace.resolve("docs")));
    }

    @Test
    void existingParentMakesCheckpointRestoreWorkspaceExact(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace.resolve("docs"));
        String before = workspaceShape(workspace);
        AtomicInteger approvals = new AtomicInteger();
        Config config = config(true);
        CheckpointService checkpoints = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        TurnProcessor processor = processor(approvals, checkpoints);
        capture(REQUEST);
        LocalTurnTraceCapture.begin(
                "t1205-mkdir-restore",
                "session",
                1,
                "2026-08-03T00:00:00Z",
                "session",
                "auto",
                "test",
                "model",
                REQUEST);

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.mkdir", Map.of("path", "docs/reports")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get());
        assertTrue(Files.isDirectory(workspace.resolve("docs/reports")));
        Files.writeString(workspace.resolve("docs/reports/later.txt"), "later");
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("CREATED", trace.checkpoint().status());

        var restored = checkpoints.restore(
                workspace,
                trace.checkpoint().checkpointId());

        assertTrue(restored.success(), restored.message());
        assertEquals(before, workspaceShape(workspace));
        assertTrue(Files.isDirectory(workspace.resolve("docs")));
        assertFalse(Files.exists(workspace.resolve("docs/reports")));
    }

    @Test
    void linkedParentFailsBeforeApprovalWithoutExternalMutation(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Path outside = temp.resolve("outside");
        Files.createDirectories(workspace);
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(workspace.resolve("docs"), outside);
        } catch (UnsupportedOperationException | java.io.IOException denied) {
            Assumptions.assumeTrue(false,
                    "symbolic-link creation unavailable: " + denied.getMessage());
        }
        AtomicInteger approvals = new AtomicInteger();
        Config config = config(false);
        TurnProcessor processor = processor(
                approvals,
                new CheckpointService(new FileBundleCheckpointStore(
                        temp.resolve("checkpoints"))));
        capture(REQUEST);

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.mkdir", Map.of("path", "docs/reports")),
                context(workspace, config));

        assertFalse(result.success());
        assertEquals(0, approvals.get());
        assertFalse(Files.exists(outside.resolve("reports")));
    }

    @Test
    void mkdirCopyAndMoveRefuseParentRemovedDuringApproval(
            @TempDir Path temp
    ) throws Exception {
        List<ApprovalRaceFixture> fixtures = List.of(
                new ApprovalRaceFixture(
                        "Create a directory named out/reports.",
                        new ToolCall("talos.mkdir", Map.of(
                                "path", "out/reports"))),
                new ApprovalRaceFixture(
                        "Copy source.txt to out/copied.txt.",
                        new ToolCall("talos.copy_path", Map.of(
                                "from", "source.txt",
                                "to", "out/copied.txt"))),
                new ApprovalRaceFixture(
                        "Move source.txt to out/moved.txt.",
                        new ToolCall("talos.move_path", Map.of(
                                "from", "source.txt",
                                "to", "out/moved.txt"))));

        int ordinal = 0;
        for (ApprovalRaceFixture fixture : fixtures) {
            Path workspace = temp.resolve("race-" + ordinal++);
            Files.createDirectories(workspace.resolve("out"));
            Files.writeString(workspace.resolve("source.txt"), "source");
            AtomicInteger approvals = new AtomicInteger();
            ApprovalGate deletingGate = new ApprovalGate() {
                @Override
                public boolean approve(String description, String detail) {
                    approvals.incrementAndGet();
                    try {
                        Files.delete(workspace.resolve("out"));
                    } catch (java.io.IOException e) {
                        throw new UncheckedIOException(e);
                    }
                    return true;
                }
            };
            Config config = config(false);
            TurnProcessor processor = processor(
                    deletingGate,
                    new CheckpointService(new FileBundleCheckpointStore(
                            temp.resolve("race-checkpoints-" + ordinal))));
            capture(fixture.request());

            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    fixture.call(),
                    context(workspace, config));

            assertFalse(result.success(), fixture.call().toolName());
            assertTrue(result.errorMessage().contains(
                    "existing ordinary parent"), result.errorMessage());
            assertEquals(1, approvals.get(), fixture.call().toolName());
            assertFalse(Files.exists(workspace.resolve("out")),
                    fixture.call().toolName());
            assertTrue(Files.isRegularFile(workspace.resolve("source.txt")),
                    fixture.call().toolName());
            TurnTaskContractCapture.clear();
            TurnUserRequestCapture.clear();
        }
    }

    private static void capture(String request) {
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(
                TaskContractResolver.resolveFromUserRequest(request));
    }

    private static TurnProcessor processor(
            AtomicInteger approvals,
            CheckpointService checkpoints
    ) {
        ApprovalGate gate = new ApprovalGate() {
            @Override
            public boolean approve(String description, String detail) {
                approvals.incrementAndGet();
                return true;
            }
        };
        return processor(gate, checkpoints);
    }

    private static TurnProcessor processor(
            ApprovalGate gate,
            CheckpointService checkpoints
    ) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MakeDirectoryTool());
        registry.register(new CopyPathTool());
        registry.register(new MovePathTool());
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                checkpoints);
    }

    private static Context context(Path workspace, Config config) {
        return Context.builder(config)
                .sandbox(new Sandbox(workspace, Map.of()))
                .build();
    }

    private static Config config(boolean enabled) {
        Config config = new Config();
        config.data.put("checkpoint", Map.of(
                "enabled", enabled,
                "fail_closed", true,
                "max_file_bytes", 1_000_000,
                "max_turn_bytes", 2_000_000));
        return config;
    }

    private static String workspaceShape(Path workspace) throws Exception {
        StringBuilder shape = new StringBuilder();
        try (var paths = Files.walk(workspace)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(workspace)) continue;
                Path relative = workspace.relativize(path);
                shape.append(Files.isDirectory(path) ? "D " : "F ")
                        .append(relative.toString().replace('\\', '/'))
                        .append('\n');
                if (Files.isRegularFile(path)) {
                    shape.append(Files.readString(path)).append('\n');
                }
            }
        }
        return shape.toString();
    }

    private record ApprovalRaceFixture(String request, ToolCall call) {}
}
