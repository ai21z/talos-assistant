package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for approval-gated tool execution in {@link TurnProcessor}.
 * Verifies that READ_ONLY tools bypass the gate, WRITE/DESTRUCTIVE tools
 * require approval, and denied operations return a DENIED error.
 */
class ApprovalGatedToolTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test
    void readOnlyToolBypassesApprovalGate() {
        // Gate that always denies — should not matter for READ_ONLY
        var registry = new ToolRegistry();
        registry.register(readOnlyTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> false, // always deny
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_read", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success(), "READ_ONLY tool should bypass approval gate");
        assertEquals("read-ok", result.output());
    }

    @Test
    void writeToolApprovedExecutes() {
        var registry = new ToolRegistry();
        registry.register(writeTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> true, // always approve
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of("path", "foo.txt"));

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success(), "Approved WRITE tool should execute");
        assertEquals("write-ok", result.output());
    }

    @Test
    void writeToolDeniedReturnsDeniedError() {
        var registry = new ToolRegistry();
        registry.register(writeTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> false, // always deny
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of("path", "foo.txt"));

        ToolResult result = processor.executeTool(session, call, ctx);
        assertFalse(result.success(), "Denied WRITE tool should fail");
        assertNotNull(result.error());
        assertEquals(ToolError.DENIED, result.error().code());
    }

    @Test
    void destructiveToolDeniedReturnsDeniedError() {
        var registry = new ToolRegistry();
        registry.register(destructiveTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> false,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_destroy", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertFalse(result.success());
        assertEquals(ToolError.DENIED, result.error().code());
    }

    @Test
    void destructiveToolApprovedExecutes() {
        var registry = new ToolRegistry();
        registry.register(destructiveTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                (desc, detail) -> true,
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_destroy", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success());
        assertEquals("destroy-ok", result.output());
    }

    @Test
    void unknownToolReturnsNotFound() {
        var processor = new TurnProcessor(
                ModeController.defaultController(),
                new NoOpApprovalGate(),
                new ToolRegistry());

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("nonexistent", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertFalse(result.success());
        assertEquals(ToolError.NOT_FOUND, result.error().code());
    }

    @Test
    void approvalGateReceivesToolNameInDescription() {
        var registry = new ToolRegistry();
        registry.register(writeTool());

        final String[] captured = {null, null};
        ApprovalGate gate = (desc, detail) -> {
            captured[0] = desc;
            captured[1] = detail;
            return true;
        };

        var processor = new TurnProcessor(
                ModeController.defaultController(), gate, registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of("path", "src/Main.java"));

        processor.executeTool(session, call, ctx);

        assertNotNull(captured[0]);
        assertTrue(captured[0].contains("talos.test_write"),
                "Approval description should contain tool name");
        assertNotNull(captured[1]);
        assertTrue(captured[1].contains("src/Main.java"),
                "Approval detail should contain target path");
    }

    @Test
    void noOpGateAllowsWriteTools() {
        // Default behavior: NoOpApprovalGate always approves
        var registry = new ToolRegistry();
        registry.register(writeTool());

        var processor = new TurnProcessor(
                ModeController.defaultController(),
                new NoOpApprovalGate(),
                registry);

        var ctx = Context.builder(new Config()).build();
        var session = new Session(WS, new Config());
        var call = new ToolCall("talos.test_write", Map.of());

        ToolResult result = processor.executeTool(session, call, ctx);
        assertTrue(result.success(), "NoOpApprovalGate should approve everything");
    }

    // ── Stub tools ──────────────────────────────────────────────────

    private static TalosTool readOnlyTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.test_read"; }
            @Override public String description() { return "Read-only test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.test_read", "Read-only test", null, ToolRiskLevel.READ_ONLY);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("read-ok"); }
        };
    }

    private static TalosTool writeTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.test_write"; }
            @Override public String description() { return "Write test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.test_write", "Write test", null, ToolRiskLevel.WRITE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("write-ok"); }
        };
    }

    private static TalosTool destructiveTool() {
        return new TalosTool() {
            @Override public String name() { return "talos.test_destroy"; }
            @Override public String description() { return "Destructive test tool"; }
            @Override public ToolDescriptor descriptor() {
                return new ToolDescriptor("talos.test_destroy", "Destructive test", null, ToolRiskLevel.DESTRUCTIVE);
            }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("destroy-ok"); }
        };
    }
}

