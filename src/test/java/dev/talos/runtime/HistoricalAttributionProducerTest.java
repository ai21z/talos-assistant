package dev.talos.runtime;

import dev.talos.cli.repl.Context;
import dev.talos.cli.modes.ModeController;
import dev.talos.core.Config;
import dev.talos.core.security.Sandbox;
import dev.talos.runtime.task.TaskContractResolver;
import dev.talos.runtime.trace.LocalTurnTraceCapture;
import dev.talos.tools.*;
import dev.talos.tools.impl.FileWriteTool;
import dev.talos.tools.impl.MutationEffectTestFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Real producer and registry effects are exercised in temporary workspaces, without model generation. */
class HistoricalAttributionProducerTest {
    @TempDir Path workspace;

    @AfterEach void clear() {
        TurnTaskContractCapture.clear();
        TurnUserRequestCapture.clear();
        LocalTurnTraceCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test void createdParentIsNotAFileEdit() throws Exception {
        var evidence = execute(new FileWriteTool(), "nested.v1/README.md", "AFTER");
        assertTrue(Files.isDirectory(workspace.resolve("nested.v1")));
        assertEquals("AFTER", Files.readString(workspace.resolve("nested.v1/README.md")));
        assertTrue(evidence.hasAppliedEdit(JsonSessionStore.sessionIdFor(workspace), "nested.v1/README.md"));
        assertFalse(evidence.hasAppliedEdit(JsonSessionStore.sessionIdFor(workspace), "nested.v1"));
        assertEquals("nested.v1/README.md", evidence.calls().getFirst().declaredTarget());
    }

    @Test void parentOnlyFailureDoesNotCertifyEitherFileOrDirectory() {
        var evidence = execute(new FileWriteTool(), "failed.v1/README.md", "\uD800");
        assertTrue(Files.isDirectory(workspace.resolve("failed.v1")));
        assertFalse(Files.exists(workspace.resolve("failed.v1/README.md")));
        assertFalse(evidence.hasAppliedEdit(JsonSessionStore.sessionIdFor(workspace), "failed.v1"));
        assertFalse(evidence.hasAppliedEdit(JsonSessionStore.sessionIdFor(workspace), "failed.v1/README.md"));
        assertEquals("failed.v1/README.md", evidence.calls().getFirst().declaredTarget());
        assertFalse(evidence.calls().getFirst().reportedSuccess());
    }

    @Test void postPromotionFailureRetainsActualFileAttribution() throws Exception {
        var evidence = execute(MutationEffectTestFixtures.postPromotionWriteFailure(), "post.v1/README.md", "AFTER");
        assertEquals("AFTER", Files.readString(workspace.resolve("post.v1/README.md")));
        assertFalse(evidence.calls().getFirst().reportedSuccess());
        assertTrue(evidence.hasAppliedEdit(JsonSessionStore.sessionIdFor(workspace), "post.v1/README.md"));
        assertFalse(evidence.hasAppliedEdit(JsonSessionStore.sessionIdFor(workspace), "post.v1"));
    }

    private HistoricalAttributionEvidence.Turn execute(TalosTool tool, String path, String content) {
        var registry = new ToolRegistry();
        registry.register(tool);
        var processor = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var config = new Config(null);
        var session = new Session(workspace, config);
        var context = Context.builder(config).sandbox(new Sandbox(workspace, Map.of())).build();
        String request = "Write " + path + ". Use talos.write_file.";
        TurnUserRequestCapture.set(request);
        TurnTaskContractCapture.set(TaskContractResolver.fromUserRequest(request));
        LocalTurnTraceCapture.begin("attribution-producer", "offline", 1, "2026-10-01T00:00:00Z",
                JsonSessionStore.sessionIdFor(workspace), "agent", "fixture", "fixture", request);
        TurnAuditCapture.begin();
        processor.executeTool(session, new ToolCall(tool.name(), Map.of("path", path, "content", content)), context);
        var audit = TurnAuditCapture.end().withLocalTrace(LocalTurnTraceCapture.complete());
        assertEquals(1, audit.toolCalls().size());
        assertEquals(TurnRecord.ToolCallDisposition.EXECUTED, audit.toolCalls().getFirst().executionDisposition());
        assertEquals(path, audit.toolCalls().getFirst().pathHint());
        var memory = new SessionMemory();
        memory.recordWorkStatus(new TurnResult(new Result.Ok("Constructed answer"), null, 1, Duration.ZERO, audit));
        return memory.historicalAttributionEvidence().turns().getFirst();
    }
}
