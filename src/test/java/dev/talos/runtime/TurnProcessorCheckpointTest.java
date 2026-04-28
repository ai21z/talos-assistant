package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.CheckpointStore;
import dev.talos.runtime.checkpoint.FileBundleCheckpointStore;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.trace.LocalTurnTrace;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.impl.FileWriteTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class TurnProcessorCheckpointTest {

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        TurnTaskContractCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void approvedWriteCreatesCheckpointBeforeMutationAndRecordsTrace(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        Files.writeString(workspace.resolve("index.html"), "original");
        CheckpointService checkpointService = new CheckpointService(
                new FileBundleCheckpointStore(temp.resolve("checkpoints")));
        TurnProcessor processor = processor(gateApproves(), checkpointService);
        Config config = config(true);
        LocalTurnTraceCapture.begin("trc-test", "sid", 1,
                "2026-04-29T00:00:00Z", "sid", "auto", "test", "model", "update index");

        TurnUserRequestCapture.set("update index.html");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "changed")),
                context(workspace, config));

        assertTrue(result.success(), result.errorMessage());
        assertEquals("changed", Files.readString(workspace.resolve("index.html")));
        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertEquals("CREATED", trace.checkpoint().status());
        assertFalse(trace.checkpoint().checkpointId().isBlank());

        CheckpointRestoreResult restore = checkpointService.restore(workspace, trace.checkpoint().checkpointId());
        assertTrue(restore.success(), restore.message());
        assertEquals("original", Files.readString(workspace.resolve("index.html")));
    }

    @Test
    void checkpointFailureBlocksMutationAfterApproval(@TempDir Path temp) throws Exception {
        Path workspace = temp.resolve("workspace");
        Files.createDirectories(workspace);
        AtomicInteger gateCalls = new AtomicInteger();
        CheckpointService checkpointService = new CheckpointService(new FailingCheckpointStore());
        TurnProcessor processor = processor(gateApproves(gateCalls), checkpointService);
        Config config = config(true);

        TurnUserRequestCapture.set("write index.html");
        ToolResult result = processor.executeTool(
                new Session(workspace, config),
                new ToolCall("talos.write_file", Map.of("path", "index.html", "content", "changed")),
                context(workspace, config));

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("checkpoint"), result.errorMessage());
        assertEquals(1, gateCalls.get(), "approval should happen before checkpoint creation");
        assertFalse(Files.exists(workspace.resolve("index.html")),
                "tool execution must not happen when required checkpoint capture fails");
    }

    private static TurnProcessor processor(ApprovalGate gate, CheckpointService checkpointService) {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new FileWriteTool());
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

    private static final class FailingCheckpointStore implements CheckpointStore {
        @Override
        public CheckpointCaptureResult captureBeforeMutation(
                Path workspace,
                Config config,
                ToolCall call,
                String traceId,
                int turnNumber
        ) {
            return CheckpointCaptureResult.failure("simulated checkpoint failure");
        }

        @Override
        public CheckpointRestoreResult restore(Path workspace, String checkpointId) {
            return CheckpointRestoreResult.failure(checkpointId, "not implemented");
        }
    }
}
