package dev.talos.runtime;

import dev.talos.cli.modes.ModeController;
import dev.talos.cli.repl.Context;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.core.retrieval.RetrievalTrace;
import dev.talos.tools.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class TurnProcessorTest {

    private static final Path WS = Path.of(".").toAbsolutePath().normalize();

    @AfterEach
    void cleanupTrace() {
        // Clear any leftover trace from tests
        TurnTraceCapture.consume();
    }

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

    // ---- Trace capture tests ----

    @Test void traceIsCapturedFromRagLikeMode() throws Exception {
        // Simulate a mode that captures a trace (like RagMode does)
        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                RetrievalTrace trace = new RetrievalTrace();
                trace.record("Bm25Stage", 1_000_000, 0, 5);
                trace.record("DedupStage", 500_000, 5, 4);
                TurnTraceCapture.capture(trace);
                return Optional.of(new Result.Ok("rag-answer"));
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "explain X", ctx);
        assertNotNull(r);
        assertNotNull(r.trace(), "Trace should be populated from capture");
        assertEquals(2, r.trace().entries().size());
        assertEquals("Bm25Stage", r.trace().entries().get(0).stageName());
    }

    @Test void traceIsNullForNonRagMode() throws Exception {
        // AskMode doesn't capture a trace → trace should be null
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r = tp.process(session, "hello", ctx);
        assertNotNull(r);
        assertNull(r.trace(), "Non-RAG modes should produce null trace");
    }

    @Test void traceIsClearedBetweenTurns() throws Exception {
        var modes = new ModeController();
        // First turn: RAG-like (captures trace)
        // Second turn: plain (no capture)
        var callCount = new int[]{0};
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                callCount[0]++;
                if (callCount[0] == 1) {
                    RetrievalTrace trace = new RetrievalTrace();
                    trace.record("Bm25Stage", 100, 0, 3);
                    TurnTraceCapture.capture(trace);
                }
                // Second call: no capture → should see null trace
                return Optional.of(new Result.Ok("answer-" + callCount[0]));
            }
        });
        var tp = new TurnProcessor(modes);
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        TurnResult r1 = tp.process(session, "rag question", ctx);
        assertNotNull(r1.trace());

        TurnResult r2 = tp.process(session, "plain question", ctx);
        assertNull(r2.trace(), "Trace from previous turn must not leak");
    }

    // ---- Memory listener integration with streamed results ----

    @Test void memoryListenerRecordsStreamedResults() throws Exception {
        SessionMemory memory = new SessionMemory();
        ConversationManager cm = new ConversationManager(memory, new TokenBudget());

        var modes = new ModeController();
        modes.add(new StubMode("ask", true) {
            @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
                return Optional.of(new Result.Streamed("streamed answer body", "\n[Sources]"));
            }
        });
        var tp = new TurnProcessor(modes);
        tp.addListener(new MemoryUpdateListener(cm));

        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();

        tp.process(session, "explain something", ctx);

        assertEquals(1, cm.turnCount());
        var history = cm.buildHistory();
        assertEquals(2, history.size());
        assertEquals("explain something", history.get(0).content());
        assertEquals("streamed answer body", history.get(1).content());
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

