package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.tools.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step-1 live-path test: prove that {@link ScopeGuard} is consulted during
 * the real mutation path (TurnProcessor.executeTool) and that its warning
 * is surfaced through the approval gate — the user sees it at decision
 * time instead of only appearing in logs.
 */
class TurnProcessorScopeGuardTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @AfterEach
    void cleanup() {
        TurnUserRequestCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    /** Approval gate that records the detail it was given, then approves. */
    static final class CapturingGate implements ApprovalGate {
        final AtomicReference<String> lastDetail = new AtomicReference<>();
        @Override public boolean approve(String desc, String detail) {
            lastDetail.set(detail);
            return true;
        }
    }

    private static TurnProcessor buildProcessor(ApprovalGate gate) {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new NopWriteTool());
        return new TurnProcessor(ModeController.defaultController(), gate, reg);
    }

    @Test
    void offScopeMutationSurfacesScopeWarningInApprovalDetail() {
        CapturingGate gate = new CapturingGate();
        TurnProcessor tp = buildProcessor(gate);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        // Simulate an active turn where the user asked for web redesign.
        TurnUserRequestCapture.set("please redesign this site — tweak the homepage");

        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "math_operations.py",
                "content", "print('hi')"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertTrue(r.success(), "gate approves; execution should proceed");
        String detail = gate.lastDetail.get();
        assertNotNull(detail, "approval detail should have been shown");
        assertTrue(detail.toLowerCase().contains("scope:"),
                "scope warning must be surfaced to the user: " + detail);
        assertTrue(detail.contains("math_operations.py"),
                "target path should appear in the warning: " + detail);
    }

    @Test
    void inScopeMutationHasNoScopeWarning() {
        CapturingGate gate = new CapturingGate();
        TurnProcessor tp = buildProcessor(gate);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        TurnUserRequestCapture.set("redesign this site — update index.html");

        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "index.html",
                "content", "<html></html>"));
        tp.executeTool(s, call, ctx);

        String detail = gate.lastDetail.get();
        assertNotNull(detail);
        assertFalse(detail.toLowerCase().contains("scope:"),
                "in-scope target must not trigger a scope warning: " + detail);
    }

    @Test
    void nonWebRequestProducesNoScopeWarning() {
        CapturingGate gate = new CapturingGate();
        TurnProcessor tp = buildProcessor(gate);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        // Request doesn't look web-scoped → guard must stay silent even for .py.
        TurnUserRequestCapture.set("please add a unit test for the adder helper");

        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "math_operations.py",
                "content", "x=1"));
        tp.executeTool(s, call, ctx);

        String detail = gate.lastDetail.get();
        assertFalse(detail.toLowerCase().contains("scope:"),
                "non-web-scoped request must not produce scope warning: " + detail);
    }

    @Test
    void readOnlyToolBypassesScopeGuard() {
        CapturingGate gate = new CapturingGate();
        ToolRegistry reg = new ToolRegistry();
        reg.register(new NopReadTool());
        TurnProcessor tp = new TurnProcessor(ModeController.defaultController(), gate, reg);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        TurnUserRequestCapture.set("redesign this site");
        ToolCall call = new ToolCall("test.read", Map.of("path", "math_operations.py"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertTrue(r.success());
        assertNull(gate.lastDetail.get(),
                "read-only tools must not invoke approval at all");
    }

    // ---- Minimal tools (local to this test) ----

    private static final class NopWriteTool implements TalosTool {
        @Override public String name() { return "test.write"; }
        @Override public String description() { return "no-op write"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.write", "no-op write", null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call) { return ToolResult.ok("wrote"); }
    }

    private static final class NopReadTool implements TalosTool {
        @Override public String name() { return "test.read"; }
        @Override public String description() { return "no-op read"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.read", "no-op read", null, ToolRiskLevel.READ_ONLY);
        }
        @Override public ToolResult execute(ToolCall call) { return ToolResult.ok("read"); }
    }
}


