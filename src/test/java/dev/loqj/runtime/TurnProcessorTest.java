package dev.loqj.runtime;

import dev.loqj.cli.modes.ModeController;
import dev.loqj.cli.repl.Context;
import dev.loqj.cli.repl.Result;
import dev.loqj.core.Config;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
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

    // ---- Stub mode for isolated testing ----

    private static class StubMode implements dev.loqj.cli.modes.Mode {
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



