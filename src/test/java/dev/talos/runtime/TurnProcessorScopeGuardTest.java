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
 * is surfaced through the approval gate - the user sees it at decision
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
        TurnUserRequestCapture.set("please redesign this site - tweak the homepage");

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

        TurnUserRequestCapture.set("redesign this site - update index.html");

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

    /**
     * Prompt 4 - scope-guard override for remembered AUTO_APPROVE policy.
     *
     * <p>When the user has answered "a" earlier this session to remember
     * approvals for in-workspace writes, a subsequent drift to an off-scope
     * target (e.g. {@code math_operations.py} during a web redesign) must
     * NOT silently auto-approve. The guard's warning must reach the user's
     * eyes, so the policy's AUTO_APPROVE is downgraded to ASK whenever the
     * scope warning fires.
     */
    @Test
    void scopeWarningForcesAskEvenWhenPolicyWouldAutoApprove() {
        CapturingGate gate = new CapturingGate();
        ToolRegistry reg = new ToolRegistry();
        reg.register(new NopWriteTool());

        // Policy has already been asked to remember in-workspace writes.
        SessionApprovalPolicy policy = new SessionApprovalPolicy();
        ToolCall prime = new ToolCall("test.write", Map.of(
                "path", WS.resolve("index.html").toString(),
                "content", "<html></html>"));
        policy.rememberApproval(WS, prime, ToolRiskLevel.WRITE);
        assertTrue(policy.rememberInWorkspaceWritesEnabled());

        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), gate, reg, policy);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        // Simulate a turn where the user's request is web-scoped, but the
        // model drifted to a Python file inside the workspace.
        TurnUserRequestCapture.set("please redesign this site - tweak the homepage");
        ToolCall drift = new ToolCall("test.write", Map.of(
                "path", WS.resolve("math_operations.py").toString(),
                "content", "print('hi')"));
        tp.executeTool(s, drift, ctx);

        // The policy would have AUTO_APPROVED (in-workspace, non-sensitive,
        // remembered), but the scope warning forces ASK. The gate must have
        // been shown the warning.
        String detail = gate.lastDetail.get();
        assertNotNull(detail,
                "scope warning must force the gate open even when policy auto-approves");
        assertTrue(detail.toLowerCase().contains("scope:"),
                "scope warning must appear in the approval detail: " + detail);
    }

    /**
     * Sanity regression: a remembered in-workspace WRITE to a non-sensitive,
     * on-scope target must still AUTO_APPROVE (the scope override must not
     * accidentally disable the remembered-approval path).
     */
    @Test
    void rememberedApprovalStillBypassesGateForOnScopeWrites() {
        CapturingGate gate = new CapturingGate();
        ToolRegistry reg = new ToolRegistry();
        reg.register(new NopWriteTool());

        SessionApprovalPolicy policy = new SessionApprovalPolicy();
        ToolCall prime = new ToolCall("test.write", Map.of(
                "path", WS.resolve("index.html").toString(),
                "content", "<html></html>"));
        policy.rememberApproval(WS, prime, ToolRiskLevel.WRITE);

        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), gate, reg, policy);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        TurnUserRequestCapture.set("redesign this site - tweak the homepage");
        ToolCall onScope = new ToolCall("test.write", Map.of(
                "path", WS.resolve("style.css").toString(),
                "content", "body{}"));
        tp.executeTool(s, onScope, ctx);

        assertNull(gate.lastDetail.get(),
                "on-scope in-workspace write under remembered approval must bypass the gate");
    }

    // ---- Minimal tools (local to this test) ----

    private static final class NopWriteTool implements TalosTool {
        @Override public String name() { return "test.write"; }
        @Override public String description() { return "no-op write"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.write", "no-op write", null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("wrote"); }
    }

    private static final class NopReadTool implements TalosTool {
        @Override public String name() { return "test.read"; }
        @Override public String description() { return "no-op read"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.read", "no-op read", null, ToolRiskLevel.READ_ONLY);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("read"); }
    }
}


