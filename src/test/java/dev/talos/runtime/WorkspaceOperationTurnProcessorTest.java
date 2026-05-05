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
import dev.talos.tools.impl.MovePathTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceOperationTurnProcessorTest {

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void approvedMoveUsesBundleCheckpointAndRestoreCoversSourceAndDestination(
            @TempDir Path temp
    ) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("source.txt"), "source-before");
        Files.writeString(workspace.resolve("dest.txt"), "dest-before");

        CheckpointService checkpoints = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        TurnProcessor processor = processor(gateApproves(), checkpoints);
        Config config = config(true);

        LocalTurnTraceCapture.begin("trc-workspace-move", "sid", 1,
                "2026-05-05T00:00:00Z", "sid", "auto", "test", "model", "move source");
        TurnUserRequestCapture.set("Move source.txt to dest.txt and overwrite it.");

        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.move_path", Map.of(
                        "from", "source.txt",
                        "to", "dest.txt",
                        "overwrite", "true")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertFalse(Files.exists(workspace.resolve("source.txt")));
        assertEquals("source-before", Files.readString(workspace.resolve("dest.txt")));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("CREATED", trace.checkpoint().status());

        CheckpointRestoreResult restore = checkpoints.restore(workspace, trace.checkpoint().checkpointId());
        assertTrue(restore.success(), restore.message());
        assertEquals("source-before", Files.readString(workspace.resolve("source.txt")));
        assertEquals("dest-before", Files.readString(workspace.resolve("dest.txt")));
    }

    @Test
    void protectedDestinationMoveIsDeniedBeforeApproval(@TempDir Path workspace) throws Exception {
        Files.writeString(workspace.resolve("public.txt"), "public");
        AtomicInteger approvals = new AtomicInteger();
        TurnProcessor processor = processor(gateApproves(approvals),
                new CheckpointService(new FileBundleCheckpointStore(workspace.resolve(".checkpoints"))));
        Config config = config(true);

        TurnUserRequestCapture.set("Move public.txt to .env");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.move_path", Map.of("from", "public.txt", "to", ".env")),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("protected path"), result.errorMessage());
        assertEquals(0, approvals.get(), "protected mutation must be denied before approval");
        assertTrue(Files.exists(workspace.resolve("public.txt")));
        assertFalse(Files.exists(workspace.resolve(".env")));
    }

    private static TurnProcessor processor(ApprovalGate gate, CheckpointService checkpointService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new MovePathTool());
        return new TurnProcessor(
                ModeController.defaultController(),
                gate,
                registry,
                ApprovalPolicy.ALWAYS_ASK,
                checkpointService);
    }

    private static ApprovalGate gateApproves() {
        return gateApproves(new AtomicInteger());
    }

    private static ApprovalGate gateApproves(AtomicInteger calls) {
        return new ApprovalGate() {
            @Override public boolean approve(String description, String detail) {
                return approveFull(description, detail).isApproved();
            }
            @Override public ApprovalResponse approveFull(String description, String detail) {
                calls.incrementAndGet();
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
