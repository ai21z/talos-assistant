package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.runtime.phase.ExecutionPhase;
import dev.talos.runtime.phase.ExecutionPhaseState;
import dev.talos.tools.TalosTool;
import dev.talos.tools.ToolCall;
import dev.talos.tools.ToolContext;
import dev.talos.tools.ToolDescriptor;
import dev.talos.tools.ToolRegistry;
import dev.talos.tools.ToolResult;
import dev.talos.tools.ToolRiskLevel;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnProcessorPhasePolicyTest {

    @Test
    void inspectPhaseBlocksMutatingToolBeforeApprovalOrExecution() {
        var executions = new AtomicInteger();
        var approvals = new AtomicInteger();
        var tp = processorWithWriteTool(executions, approvals);
        var ctx = contextAt(ExecutionPhase.INSPECT);

        TurnUserRequestCapture.set("Please update index.html.");
        try {
            ToolResult result = tp.executeTool(session(), writeCall(), ctx);

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("Phase policy blocked talos.write_file during INSPECT"));
            assertEquals(0, approvals.get(), "phase rejection must happen before approval");
            assertEquals(0, executions.get(), "phase rejection must happen before tool execution");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void applyPhaseKeepsApprovalGateInFrontOfMutationExecution() {
        var executions = new AtomicInteger();
        var approvals = new AtomicInteger();
        var tp = processorWithWriteTool(executions, approvals);
        var ctx = contextAt(ExecutionPhase.APPLY);

        TurnUserRequestCapture.set("Please update index.html.");
        try {
            ToolResult result = tp.executeTool(session(), writeCall(), ctx);

            assertTrue(result.success(), result.errorMessage());
            assertEquals(1, approvals.get(), "apply phase must preserve approval semantics");
            assertEquals(1, executions.get(), "approved apply-phase mutation should execute");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    @Test
    void verifyPhaseBlocksFurtherMutatingToolBeforeApprovalOrExecution() {
        var executions = new AtomicInteger();
        var approvals = new AtomicInteger();
        var tp = processorWithWriteTool(executions, approvals);
        var ctx = contextAt(ExecutionPhase.VERIFY);

        TurnUserRequestCapture.set("Please update index.html.");
        try {
            ToolResult result = tp.executeTool(session(), writeCall(), ctx);

            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("Phase policy blocked talos.write_file during VERIFY"));
            assertEquals(0, approvals.get(), "verify-phase rejection must happen before approval");
            assertEquals(0, executions.get(), "verify-phase rejection must happen before tool execution");
        } finally {
            TurnUserRequestCapture.clear();
        }
    }

    private static TurnProcessor processorWithWriteTool(AtomicInteger executions, AtomicInteger approvals) {
        var registry = new ToolRegistry();
        registry.register(new WriteTool(executions));
        return new TurnProcessor(
                ModeController.defaultController(),
                (description, detail) -> {
                    approvals.incrementAndGet();
                    return true;
                },
                registry);
    }

    private static Context contextAt(ExecutionPhase phase) {
        return Context.builder(new Config())
                .executionPhaseState(new ExecutionPhaseState(phase))
                .build();
    }

    private static Session session() {
        return new Session(Path.of(".").toAbsolutePath().normalize(), new Config());
    }

    private static ToolCall writeCall() {
        return new ToolCall("talos.write_file", Map.of(
                "path", "index.html",
                "content", "<h1>updated</h1>"));
    }

    private record WriteTool(AtomicInteger executions) implements TalosTool {
        @Override public String name() { return "talos.write_file"; }
        @Override public String description() { return "Write file test"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor(name(), description(), null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
            executions.incrementAndGet();
            return ToolResult.ok("updated");
        }
    }
}
