package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.runtime.workspace.BatchWorkspaceApplyTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceBatchTurnProcessorTest {

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void approvedBatchUsesOneApprovalAndBundleCheckpoint(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        Files.writeString(workspace.resolve("dest.txt"), "dest-before");

        AtomicInteger approvals = new AtomicInteger();
        CheckpointService checkpoints = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        TurnProcessor processor = processor(gateApproves(approvals), checkpoints);
        Config config = config(true);

        LocalTurnTraceCapture.begin("trc-workspace-batch", "sid", 1,
                "2026-05-05T00:00:00Z", "sid", "auto", "test", "model", "batch");
        TurnUserRequestCapture.set("Create docs and move source.txt to dest.txt.");

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"docs"},
                          {"op":"move_path","from":"source.txt","to":"dest.txt","overwrite":true}
                        ]
                        """)),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals(1, approvals.get(), "batch should ask for approval once");
        assertTrue(Files.isDirectory(workspace.resolve("docs")));
        assertFalse(Files.exists(workspace.resolve("source.txt")));
        assertEquals("source-before", Files.readString(workspace.resolve("dest.txt")));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("CREATED", trace.checkpoint().status());

        CheckpointRestoreResult restore = checkpoints.restore(workspace, trace.checkpoint().checkpointId());
        assertTrue(restore.success(), restore.message());
        assertFalse(Files.exists(workspace.resolve("docs")));
        assertEquals("source-before", Files.readString(workspace.resolve("source.txt")));
        assertEquals("dest-before", Files.readString(workspace.resolve("dest.txt")));
    }

    @Test
    void successfulBatchAuditRecordsAllChangedPaths(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("styles.css"), "body { color: black; }");
        TurnProcessor processor = processor(gateApproves(new AtomicInteger()),
                new CheckpointService(new FileBundleCheckpointStore(workspace.resolve(".checkpoints"))));
        Config config = config(true);

        TurnAuditCapture.begin();
        try {
            ToolResult result = processor.executeTool(
                    new Session(workspace, config),
                    new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                            [
                              {"op":"mkdir","path":"batch-one"},
                              {"op":"mkdir","path":"batch-two"},
                              {"op":"copy_path","from":"styles.css","to":"batch-one/styles-copy.css"}
                            ]
                            """)),
                    context(workspace, config));

            assertTrue(result.success(), result.errorMessage());
            TurnAudit audit = TurnAuditCapture.end();
            assertEquals(1, audit.toolCalls().size());
            TurnRecord.ToolCallSummary call = audit.toolCalls().getFirst();
            assertEquals("talos.apply_workspace_batch", call.name());
            assertEquals("batch-one", call.pathHint());
            assertEquals(List.of("batch-one", "batch-two", "batch-one/styles-copy.css"), call.pathHints());
        } finally {
            if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
        }
    }

    @Test
    void deleteBatchUsesDestructiveApprovalRiskAndBundleCheckpoint(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("old-plan.md"), "delete me");

        AtomicReference<String> approvalDescription = new AtomicReference<>("");
        CheckpointService checkpoints = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        TurnProcessor processor = processor(gateApproves(new AtomicInteger(), approvalDescription), checkpoints);
        Config config = config(true);

        LocalTurnTraceCapture.begin("trc-workspace-batch-delete", "sid", 1,
                "2026-05-11T00:00:00Z", "sid", "auto", "test", "model", "delete");
        TurnUserRequestCapture.set("Delete old-plan.md.");

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [{"op":"delete_path","path":"old-plan.md"}]
                        """)),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals("destructive operation: talos.apply_workspace_batch", approvalDescription.get());
        assertFalse(Files.exists(workspace.resolve("old-plan.md")));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("CREATED", trace.checkpoint().status());

        CheckpointRestoreResult restore = checkpoints.restore(workspace, trace.checkpoint().checkpointId());
        assertTrue(restore.success(), restore.message());
        assertEquals("delete me", Files.readString(workspace.resolve("old-plan.md")));
    }

    @Test
    void protectedNestedBatchDestinationIsDeniedBeforeApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "public");
        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = processor(gateApproves(approvals),
                new CheckpointService(new FileBundleCheckpointStore(workspace.resolve(".checkpoints"))));
        Config config = config(true);

        TurnUserRequestCapture.set("Move public.txt to .env");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [{"op":"move_path","from":"public.txt","to":".env"}]
                        """)),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("protected path"), result.errorMessage());
        assertEquals(0, approvals.get(), "protected batch mutation must be denied before approval");
        assertTrue(Files.exists(workspace.resolve("public.txt")));
        assertFalse(Files.exists(workspace.resolve(".env")));
    }

    @Test
    void partialBatchFailureReportsAppliedAndFailedOperationPaths(@TempDir Path workspace) throws Exception {
        TurnProcessor processor = processor(gateApproves(new AtomicInteger()),
                new CheckpointService(new FileBundleCheckpointStore(workspace.resolve(".checkpoints"))));
        Config config = config(true);

        TurnUserRequestCapture.set("Create docs and move missing.txt to docs/missing.txt.");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.apply_workspace_batch", Map.of("operations_json", """
                        [
                          {"op":"mkdir","path":"docs"},
                          {"op":"move_path","from":"missing.txt","to":"docs/missing.txt"}
                        ]
                        """)),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("Batch partially applied."), result.errorMessage());
        assertTrue(result.errorMessage().contains("Applied: docs"), result.errorMessage());
        assertTrue(result.errorMessage().contains("Failed: missing.txt -> docs/missing.txt"), result.errorMessage());
        assertTrue(Files.isDirectory(workspace.resolve("docs")));
    }

    private static TurnProcessor processor(ApprovalGate gate, CheckpointService checkpointService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new BatchWorkspaceApplyTool());
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                checkpointService);
    }

    private static ApprovalGate gateApproves(AtomicInteger calls) {
        return gateApproves(calls, new AtomicReference<>(""));
    }

    private static ApprovalGate gateApproves(AtomicInteger calls, AtomicReference<String> descriptionRef) {
        return new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                calls.incrementAndGet();
                descriptionRef.set(description);
                return ApprovalResponse.APPROVED;
            }
        };
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
}
