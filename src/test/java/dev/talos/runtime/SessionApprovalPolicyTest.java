package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.tools.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Step-3 tests: minimal session-scoped approval policy.
 *
 * <p>Verifies the policy invariants:
 * <ul>
 *   <li>READ_ONLY is always AUTO_APPROVE.</li>
 *   <li>DESTRUCTIVE is always ASK (even after remember).</li>
 *   <li>WRITE in-workspace can be AUTO_APPROVE after remember.</li>
 *   <li>WRITE out-of-workspace is always ASK (even after remember).</li>
 *   <li>Missing-path writes stay ASK (cannot classify).</li>
 *   <li>The gate's APPROVED_REMEMBER response triggers policy memory.</li>
 * </ul>
 */
class SessionApprovalPolicyTest {

    @AfterEach void clearTls() {
        TurnUserRequestCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    @Test
    void readOnlyIsAlwaysAutoApprove(@TempDir Path ws) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall read = new ToolCall("t.read", Map.of("path", "foo.py"));
        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                p.decide(ws, read, ToolRiskLevel.READ_ONLY));
    }

    @Test
    void destructiveNeverAutoApproves(@TempDir Path ws) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall del = new ToolCall("t.rm", Map.of("path", ws.resolve("x.txt").toString()));
        // Even after asking to remember, destructive stays ASK.
        p.rememberApproval(ws, del, ToolRiskLevel.DESTRUCTIVE);
        assertFalse(p.rememberInWorkspaceWritesEnabled(),
                "remember must be a no-op for destructive calls");
        assertEquals(ApprovalPolicy.Decision.ASK,
                p.decide(ws, del, ToolRiskLevel.DESTRUCTIVE));
    }

    @Test
    void writeInWorkspaceAutoApprovesAfterRemember(@TempDir Path ws) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall write = new ToolCall("t.write", Map.of(
                "path", ws.resolve("src/file.txt").toString(),
                "content", "data"));

        assertEquals(ApprovalPolicy.Decision.ASK,
                p.decide(ws, write, ToolRiskLevel.WRITE),
                "before remember: must ask");

        p.rememberApproval(ws, write, ToolRiskLevel.WRITE);
        assertTrue(p.rememberInWorkspaceWritesEnabled());

        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                p.decide(ws, write, ToolRiskLevel.WRITE),
                "after remember: in-workspace writes auto-approve");
    }

    @Test
    void writeOutsideWorkspaceAlwaysAsks(@TempDir Path ws, @TempDir Path other) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall write = new ToolCall("t.write", Map.of(
                "path", other.resolve("evil.sh").toString(),
                "content", "rm -rf /"));
        p.rememberApproval(ws, write, ToolRiskLevel.WRITE);
        assertFalse(p.rememberInWorkspaceWritesEnabled(),
                "remember must not enable for out-of-workspace targets");
        assertEquals(ApprovalPolicy.Decision.ASK,
                p.decide(ws, write, ToolRiskLevel.WRITE));
    }

    @Test
    void writeWithNoPathStaysAsk(@TempDir Path ws) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall write = new ToolCall("t.write", Map.of("content", "x"));
        assertEquals(ApprovalPolicy.Decision.ASK,
                p.decide(ws, write, ToolRiskLevel.WRITE));
    }

    @Test
    void relativePathResolvesAgainstWorkspace(@TempDir Path ws) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall write = new ToolCall("t.write", Map.of(
                "path", "src/x.js",  // relative — resolves under ws
                "content", "data"));
        p.rememberApproval(ws, write, ToolRiskLevel.WRITE);
        assertTrue(p.rememberInWorkspaceWritesEnabled());
        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                p.decide(ws, write, ToolRiskLevel.WRITE));
    }

    // ---- End-to-end: TurnProcessor wiring ----

    @Test
    void turnProcessorAutoApprovesAfterRememberChoice(@TempDir Path ws) {
        // A gate that returns APPROVED_REMEMBER exactly once, then would
        // DENY if called again — so the test proves the second in-workspace
        // write did NOT reach the gate.
        AtomicInteger gateCalls = new AtomicInteger(0);
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String d, String x) { throw new AssertionError(); }
            @Override public ApprovalResponse approveFull(String d, String x) {
                int n = gateCalls.incrementAndGet();
                if (n == 1) return ApprovalResponse.APPROVED_REMEMBER;
                return ApprovalResponse.DENIED;
            }
        };

        SessionApprovalPolicy policy = new SessionApprovalPolicy();
        ToolRegistry reg = new ToolRegistry();
        reg.register(new RecordingWriteTool());
        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), gate, reg, policy);

        Session s = new Session(ws, new Config());
        Context ctx = Context.builder(new Config()).build();

        ToolCall c1 = new ToolCall("test.w",
                Map.of("path", ws.resolve("a.txt").toString(), "content", "1"));
        ToolResult r1 = tp.executeTool(s, c1, ctx);
        assertTrue(r1.success());
        assertEquals(1, gateCalls.get());
        assertTrue(policy.rememberInWorkspaceWritesEnabled());

        // Second in-workspace write — gate must NOT be called (would deny).
        ToolCall c2 = new ToolCall("test.w",
                Map.of("path", ws.resolve("b.txt").toString(), "content", "2"));
        ToolResult r2 = tp.executeTool(s, c2, ctx);
        assertTrue(r2.success(), "policy AUTO_APPROVE should bypass the gate");
        assertEquals(1, gateCalls.get(), "gate must not be re-prompted");
    }

    @Test
    void turnProcessorStillAsksForOutOfWorkspaceAfterRemember(@TempDir Path ws, @TempDir Path other) {
        AtomicInteger gateCalls = new AtomicInteger(0);
        ApprovalGate gate = new ApprovalGate() {
            @Override public boolean approve(String d, String x) { return true; }
            @Override public ApprovalResponse approveFull(String d, String x) {
                gateCalls.incrementAndGet();
                // First call remembers, subsequent approve once.
                return gateCalls.get() == 1
                        ? ApprovalResponse.APPROVED_REMEMBER
                        : ApprovalResponse.APPROVED;
            }
        };

        SessionApprovalPolicy policy = new SessionApprovalPolicy();
        ToolRegistry reg = new ToolRegistry();
        reg.register(new RecordingWriteTool());
        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), gate, reg, policy);

        Session s = new Session(ws, new Config());
        Context ctx = Context.builder(new Config()).build();

        // Remember approval for in-workspace writes.
        tp.executeTool(s, new ToolCall("test.w",
                Map.of("path", ws.resolve("a.txt").toString(), "content", "1")), ctx);
        assertTrue(policy.rememberInWorkspaceWritesEnabled());

        // Out-of-workspace write: gate MUST still be called despite remember.
        tp.executeTool(s, new ToolCall("test.w",
                Map.of("path", other.resolve("evil.txt").toString(), "content", "x")), ctx);
        assertEquals(2, gateCalls.get(),
                "out-of-workspace write must not use the remembered approval");
    }

    @Test
    void defaultPostureUnchangedWithAlwaysAskPolicy(@TempDir Path ws) {
        // Regression safety: with ALWAYS_ASK (the default in legacy constructors),
        // every mutating call goes through the gate just like before.
        AtomicInteger gateCalls = new AtomicInteger(0);
        ApprovalGate gate = (d, x) -> { gateCalls.incrementAndGet(); return true; };

        ToolRegistry reg = new ToolRegistry();
        reg.register(new RecordingWriteTool());
        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), gate, reg);

        Session s = new Session(ws, new Config());
        Context ctx = Context.builder(new Config()).build();

        for (int i = 0; i < 3; i++) {
            tp.executeTool(s, new ToolCall("test.w",
                    Map.of("path", ws.resolve("f" + i).toString(), "content", "c")), ctx);
        }
        assertEquals(3, gateCalls.get(),
                "legacy default (ALWAYS_ASK) must prompt on every mutating call");
    }

    // ---- helper tool ----

    private static final class RecordingWriteTool implements TalosTool {
        @Override public String name() { return "test.w"; }
        @Override public String description() { return "write"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.w", "write", null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call) { return ToolResult.ok("wrote"); }
    }
}

