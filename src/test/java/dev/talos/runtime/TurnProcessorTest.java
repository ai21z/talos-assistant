package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.core.Config;
import dev.talos.tools.*;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TurnProcessorTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @Test void nullInputReturnsNull() throws Exception {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        assertNull(tp.process(session, null, ctx));
        assertNull(tp.process(session, "  ", ctx));
        // Turn counter should not have incremented for null/blank inputs
        assertEquals(0, session.turnCount());
    }

    @Test void turnCounterIncrements() throws Exception {
        // Use a controller with a stub registered as "ask" so auto-mode's ASSIST route finds it
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r1 = tp.process(session, "hello", ctx);
        assertNotNull(r1);
        assertEquals(1, r1.turnNumber());

        TurnResult r2 = tp.process(session, "world", ctx);
        assertNotNull(r2);
        assertEquals(2, r2.turnNumber());

        assertEquals(2, session.turnCount());
    }

    @Test void timingIsPositive() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "test", ctx);
        assertNotNull(r);
        assertNotNull(r.elapsed());
        assertFalse(r.elapsed().isNegative());
    }

    @Test void noModeHandlesReturnsNull() throws Exception {
        // Empty controller — no modes registered
        var tp = new TurnProcessor(new ModeController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "orphan input", ctx);
        assertNull(r);
    }

    @Test void exceptionPropagatesForEnvelopeHandling() {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context c) throws Exception {
                throw new IllegalStateException("boom");
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        // Exceptions propagate to the caller (ExecutionPipeline) for redaction + audit
        var ex = assertThrows(IllegalStateException.class,
                () -> tp.process(session, "crash", ctx));
        assertEquals("boom", ex.getMessage());
        // Turn counter still incremented (turn was started before dispatch)
        assertEquals(1, session.turnCount());
    }

    @Test void approvalGateDefaultsToNoOp() {
        var tp = new TurnProcessor(ModeController.defaultController());
        assertNotNull(tp.approvalGate());
        assertTrue(tp.approvalGate().approve("test", null));
    }

    @Test void customApprovalGateIsPreserved() {
        ApprovalGate deny = (desc, detail) -> false;
        var tp = new TurnProcessor(ModeController.defaultController(), deny);
        assertSame(deny, tp.approvalGate());
        assertFalse(tp.approvalGate().approve("anything", null));
    }

    // ---- Tool dispatch tests ----

    @Test void executeToolDispatchesToRegisteredTool() {
        ToolRegistry registry = new ToolRegistry();
        registry.register(new EchoTool());

        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("test.echo", Map.of("input", "hello"));
        ToolResult result = tp.executeTool(session, call, ctx);

        assertTrue(result.success());
        assertEquals("Echo: hello", result.output());
    }

    @Test void executeToolReturnsErrorForUnknownTool() {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolCall call = new ToolCall("nonexistent.tool", Map.of());
        ToolResult result = tp.executeTool(session, call, ctx);

        assertFalse(result.success());
        assertEquals(ToolError.NOT_FOUND, result.error().code());
    }

    @Test void executeToolWithNullCallReturnsError() {
        var tp = new TurnProcessor(ModeController.defaultController());
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolResult result = tp.executeTool(session, null, ctx);
        assertFalse(result.success());
    }

    @Test void toolRegistryAccessor() {
        ToolRegistry registry = new ToolRegistry();
        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        assertSame(registry, tp.toolRegistry());
    }

    @Test void toolReceivesWorkspaceFromSession() {
        ToolRegistry registry = new ToolRegistry();
        // Tool that records the workspace it received
        registry.register(new TalosTool() {
            @Override public String name() { return "test.ws"; }
            @Override public String description() { return "test"; }
            @Override public ToolDescriptor descriptor() { return new ToolDescriptor("test.ws", "test"); }
            @Override public ToolResult execute(ToolCall call) { return ToolResult.fail("no context"); }
            @Override public ToolResult execute(ToolCall call, ToolContext ctx) {
                return ToolResult.ok(ctx.workspace().toString());
            }
        });

        var tp = new TurnProcessor(ModeController.defaultController(), new NoOpApprovalGate(), registry);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        ToolResult result = tp.executeTool(session, new ToolCall("test.ws", Map.of()), ctx);
        assertTrue(result.success());
        assertEquals(WS.toString(), result.output());
    }

    // ---- Test tools ----

    private static class EchoTool implements TalosTool {
        @Override public String name() { return "test.echo"; }
        @Override public String description() { return "Echoes input"; }
        @Override public ToolDescriptor descriptor() { return new ToolDescriptor("test.echo", "Echoes input"); }
        @Override public ToolResult execute(ToolCall call) {
            return ToolResult.ok("Echo: " + call.param("input", "(empty)"));
        }
    }

    // ---- Stub mode for isolated testing ----

    private static class StubMode implements dev.talos.cli.modes.Mode {
        private final String modeName;
        private final boolean handles;

        StubMode(String name, boolean handles) {
            this.modeName = name;
            this.handles = handles;
        }

        @Override public String name() { return modeName; }
        @Override public boolean canHandle(String raw) { return handles; }
        @Override public Optional<Result> handle(String raw, Path ws, Context ctx) throws Exception {
            return Optional.of(new Result.Ok("stub-answer"));
        }
    }
}

