package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.core.Config;
import dev.talos.tools.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live-path test: {@link TurnProcessor} rejects template-placeholder
 * payloads BEFORE they reach the approval gate, so a reflex "y" cannot
 * destroy real files.
 *
 * <p>Regression guard for the real transcript destruction in
 * {@code test-output.txt} Turn 6 (qwen2.5-coder:14b overwrote
 * {@code index.html} with literal {@code <updated_index_html_content>}
 * after the user approved the gate).
 */
class TurnProcessorPlaceholderGuardTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @AfterEach void cleanup() {
        TurnUserRequestCapture.clear();
        if (TurnAuditCapture.isActive()) TurnAuditCapture.end();
    }

    /** A gate that fails the test if the call reaches it. */
    private static ApprovalGate unreachableGate() {
        return new ApprovalGate() {
            @Override public boolean approve(String d, String x) {
                throw new AssertionError("gate must not be reached; call should be pre-rejected");
            }
            @Override public ApprovalResponse approveFull(String d, String x) {
                throw new AssertionError("gate must not be reached; call should be pre-rejected");
            }
        };
    }

    private static TurnProcessor processorWithWriteTool(ApprovalGate gate) {
        ToolRegistry reg = new ToolRegistry();
        reg.register(new RecordingWriteTool());
        return new TurnProcessor(ModeController.defaultController(), gate, reg);
    }

    @Test
    void writeFileWithPlaceholderContentIsRejectedBeforeApproval() {
        TurnProcessor tp = processorWithWriteTool(unreachableGate());
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        // Exact transcript shape.
        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "index.html",
                "content", "<updated_index_html_content>"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertFalse(r.success(), "placeholder content must produce a failed tool result");
        String err = r.errorMessage() == null ? "" : r.errorMessage();
        assertTrue(err.toLowerCase().contains("template placeholder")
                        || err.toLowerCase().contains("placeholder"),
                "error must identify the problem as a placeholder: " + err);
        assertTrue(err.contains("<updated_index_html_content>"),
                "error should echo the offending value so the model sees it: " + err);
    }

    @Test
    void editFileWithPlaceholderNewStringIsRejected() {
        TurnProcessor tp = processorWithWriteTool(unreachableGate());
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "index.html",
                "old_string", "<title>Old</title>",
                "new_string", "<updated_title>"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertFalse(r.success());
        assertTrue(r.errorMessage().contains("new_string"),
                "rejection must name the offending parameter: " + r.errorMessage());
    }

    @Test
    void legitimateSmallWriteStillReachesApproval() {
        // Proof that the guard doesn't false-positive — a tiny but real
        // HTML stub must pass through the guard and hit the gate.
        AtomicInteger gateCalls = new AtomicInteger(0);
        ApprovalGate approving = (d, x) -> { gateCalls.incrementAndGet(); return true; };
        TurnProcessor tp = processorWithWriteTool(approving);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "index.html",
                "content", "<html></html>"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertTrue(r.success(), "real-content write must succeed");
        assertEquals(1, gateCalls.get(), "approval gate must have been reached");
    }

    @Test
    void readOnlyToolWithPlaceholderPathIsNowRejected() {
        // Path-param placeholder guard was extended to cover ALL tools after
        // a live-transcript failure: read_file(path=<html-file-path>) caused
        // an InvalidPathException crash because Path.of("<html-file-path>") is
        // illegal on Windows. Placeholder paths are definitionally wrong for
        // any file tool, so the guard now fires unconditionally on path params.
        ToolRegistry reg = new ToolRegistry();
        reg.register(new NopReadTool());
        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), unreachableGate(), reg);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("test.read", Map.of(
                "path", "<html-file-path>"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertFalse(r.success(), "placeholder path must be rejected for read-only tools");
        String err = r.errorMessage() == null ? "" : r.errorMessage();
        assertTrue(err.toLowerCase().contains("placeholder"),
                "error must identify the problem as a placeholder: " + err);
        assertTrue(err.contains("<html-file-path>"),
                "error should echo the offending value so the model sees it: " + err);
    }

    @Test
    void mutatingToolWithPlaceholderPathIsAlsoRejectedBeforeApproval() {
        // The path-param guard runs before the approval gate, so mutating tools
        // with a placeholder path value don't reach the gate either.
        TurnProcessor tp = processorWithWriteTool(unreachableGate());
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();
        TurnUserRequestCapture.set("update the file");

        ToolCall call = new ToolCall("test.write", Map.of(
                "path", "<target-file>",
                "content", "real content here"));
        ToolResult r = tp.executeTool(s, call, ctx);

        assertFalse(r.success(), "placeholder path must be rejected even for mutating tools");
        assertTrue(r.errorMessage().contains("<target-file>"),
                "error should echo the offending path: " + r.errorMessage());
    }

    @Test
    void toolThrowingRuntimeExceptionProducesFailResultInsteadOfCrash() {
        // Exception wrapping: if a tool throws unexpectedly (e.g. InvalidPathException
        // from Path.of with bad input that slipped through guards), executeTool must
        // return ToolResult.fail rather than propagating the exception up through
        // ToolCallLoop → AssistantTurnExecutor where it becomes "LLM call failed".
        ToolRegistry reg = new ToolRegistry();
        reg.register(new ThrowingTool(new RuntimeException("synthetic tool crash")));
        TurnProcessor tp = new TurnProcessor(
                ModeController.defaultController(), unreachableGate(), reg);
        Session s = new Session(WS, new Config());
        Context ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("test.thrower", Map.of());
        ToolResult r = tp.executeTool(s, call, ctx);

        assertFalse(r.success(), "unexpected exception must produce a failed tool result");
        String err = r.errorMessage() == null ? "" : r.errorMessage();
        assertTrue(err.contains("synthetic tool crash"),
                "error message should include the original exception message: " + err);
    }

    // ---- helper tools ----

    private static final class RecordingWriteTool implements TalosTool {
        @Override public String name() { return "test.write"; }
        @Override public String description() { return "write"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.write", "write", null, ToolRiskLevel.WRITE);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("wrote"); }
    }

    private static final class NopReadTool implements TalosTool {
        @Override public String name() { return "test.read"; }
        @Override public String description() { return "read"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.read", "read", null, ToolRiskLevel.READ_ONLY);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { return ToolResult.ok("read"); }
    }

    private static final class ThrowingTool implements TalosTool {
        private final RuntimeException toThrow;
        ThrowingTool(RuntimeException ex) { this.toThrow = ex; }
        @Override public String name() { return "test.thrower"; }
        @Override public String description() { return "throws on every call"; }
        @Override public ToolDescriptor descriptor() {
            return new ToolDescriptor("test.thrower", "throws on every call", null, ToolRiskLevel.READ_ONLY);
        }
        @Override public ToolResult execute(ToolCall call, ToolContext ctx) { throw toThrow; }
    }
}

