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

    // ---- Sensitive in-workspace paths (Prompt 3 refinement) ----

    /**
     * Prime the session by remember-approving a plain in-workspace write.
     * After this, only sensitive paths should still prompt.
     */
    private static SessionApprovalPolicy primedPolicy(Path ws) {
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall plain = new ToolCall("t.write", Map.of(
                "path", ws.resolve("src/plain.txt").toString(),
                "content", "ok"));
        p.rememberApproval(ws, plain, ToolRiskLevel.WRITE);
        assertTrue(p.rememberInWorkspaceWritesEnabled(),
                "precondition: remember flag must be on");
        return p;
    }

    @Test
    void sensitiveDirWritesStillAskEvenAfterRemember(@TempDir Path ws) {
        SessionApprovalPolicy p = primedPolicy(ws);

        for (String sub : new String[] {
                ".git/config",
                ".git/hooks/pre-commit",
                ".github/workflows/ci.yml",
                ".ssh/authorized_keys",
                ".gnupg/trustdb.gpg"}) {
            ToolCall call = new ToolCall("t.write", Map.of(
                    "path", ws.resolve(sub).toString(),
                    "content", "payload"));
            assertEquals(ApprovalPolicy.Decision.ASK,
                    p.decide(ws, call, ToolRiskLevel.WRITE),
                    "sensitive write must still ask: " + sub);
        }

        // Sanity: a normal file in the same session auto-approves, proving
        // the flag is still on and only sensitive paths are carved out.
        ToolCall normal = new ToolCall("t.write", Map.of(
                "path", ws.resolve("src/app.java").toString(),
                "content", "ok"));
        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                p.decide(ws, normal, ToolRiskLevel.WRITE));
    }

    @Test
    void dotEnvFilesStillAskEvenAfterRemember(@TempDir Path ws) {
        SessionApprovalPolicy p = primedPolicy(ws);

        for (String name : new String[] {".env", ".env.local", ".env.production"}) {
            ToolCall call = new ToolCall("t.write", Map.of(
                    "path", ws.resolve(name).toString(),
                    "content", "SECRET=1"));
            assertEquals(ApprovalPolicy.Decision.ASK,
                    p.decide(ws, call, ToolRiskLevel.WRITE),
                    name + " must still prompt");
        }

        // Guard against over-triggering: files that merely contain "env"
        // must not be treated as sensitive.
        ToolCall envLike = new ToolCall("t.write", Map.of(
                "path", ws.resolve("docs/environment.md").toString(),
                "content", "notes"));
        assertEquals(ApprovalPolicy.Decision.AUTO_APPROVE,
                p.decide(ws, envLike, ToolRiskLevel.WRITE),
                "regular files containing 'env' must NOT be flagged sensitive");
    }

    @Test
    void rememberApprovalOnSensitiveTargetDoesNotFlipFlag(@TempDir Path ws) {
        // User's first approved write happens to target .git/config.
        // The policy must NOT silently "remember" that choice — otherwise
        // every subsequent .git write would still be blocked (good) but a
        // malicious prompt could then rely on the user having said "a"
        // to slip normal-file writes through. Symmetry: remember only flips
        // when the triggering target is itself safe.
        SessionApprovalPolicy p = new SessionApprovalPolicy();
        ToolCall gitConfig = new ToolCall("t.write", Map.of(
                "path", ws.resolve(".git/config").toString(),
                "content", "[core]\n"));
        p.rememberApproval(ws, gitConfig, ToolRiskLevel.WRITE);
        assertFalse(p.rememberInWorkspaceWritesEnabled(),
                "remember must not flip when the triggering call is sensitive");
    }

    @Test
    void isSensitiveTargetClassifier_basicCases(@TempDir Path ws) {
        var call = (java.util.function.Function<String, ToolCall>) p ->
                new ToolCall("t.w", Map.of("path", p, "content", "x"));

        assertTrue(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve(".git/config").toString())));
        assertTrue(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve(".github/workflows/build.yml").toString())));
        assertTrue(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve(".env").toString())));
        assertTrue(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve(".env.prod").toString())));

        assertFalse(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve("src/main.java").toString())));
        assertFalse(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve(".gitignore").toString())),
                ".gitignore is a normal tracked file, not VCS internals");
        assertFalse(SessionApprovalPolicy.isSensitiveTarget(ws,
                call.apply(ws.resolve("environment.md").toString())));
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
    void turnProcessorDeniesOutOfWorkspaceBeforeApprovalAfterRemember(@TempDir Path ws, @TempDir Path other) {
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

        // Out-of-workspace write: the declarative permission layer denies
        // workspace escapes before approval. Remembered approval must not
        // convert an escaped path into another prompt.
        ToolResult escaped = tp.executeTool(s, new ToolCall("test.w",
                Map.of("path", other.resolve("evil.txt").toString(), "content", "x")), ctx);
        assertFalse(escaped.success());
        assertEquals(ToolError.DENIED, escaped.error().code());
        assertEquals(1, gateCalls.get(),
                "out-of-workspace write must be denied before another approval prompt");
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
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("wrote"); }
    }
}

