package dev.talos.runtime;
import dev.talos.cli.repl.Context;
import dev.talos.runtime.SessionMemory;
import dev.talos.cli.modes.ModeController;
import dev.talos.core.Config;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import org.junit.jupiter.api.Test;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class SessionLifecycleTest {
    private static final Path WS = Path.of(".").toAbsolutePath().normalize();
    @Test
    void sessionListenerDefaultsAreNoOps() {
        SessionListener listener = new SessionListener() {};
        listener.onTurnComplete(null, null);
        listener.onSessionEnd();
    }
    @Test
    void memoryUpdateListenerRecordsTurn() {
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory);
        var listener = new MemoryUpdateListener(cm);
        var result = new TurnResult(new Result.Ok("The answer is 42"), 1);
        listener.onTurnComplete(result, "What is the answer?");
        assertTrue(memory.hasContent());
        var turns = memory.getTurns();
        assertEquals(2, turns.size());
        assertEquals("What is the answer?", turns.get(0).content());
        assertEquals("The answer is 42", turns.get(1).content());
    }
    @Test
    void memoryUpdateListenerIgnoresNullResult() {
        var cm = new ConversationManager(new SessionMemory());
        var listener = new MemoryUpdateListener(cm);
        listener.onTurnComplete(null, "input");
        assertEquals(0, cm.turnCount());
    }
    @Test
    void memoryUpdateListenerIgnoresBlankInput() {
        var cm = new ConversationManager(new SessionMemory());
        var listener = new MemoryUpdateListener(cm);
        var result = new TurnResult(new Result.Ok("answer"), 1);
        listener.onTurnComplete(result, "");
        listener.onTurnComplete(result, null);
        assertEquals(0, cm.turnCount());
    }
    @Test
    void memoryUpdateListenerIgnoresNonOkResults() {
        var cm = new ConversationManager(new SessionMemory());
        var listener = new MemoryUpdateListener(cm);
        var infoResult = new TurnResult(new Result.Info("some info"), 1);
        listener.onTurnComplete(infoResult, "user input");
        assertEquals(0, cm.turnCount());
        var errorResult = new TurnResult(new Result.Error("error", 500), 1);
        listener.onTurnComplete(errorResult, "user input");
        assertEquals(0, cm.turnCount());
    }
    @Test
    void turnProcessorFiresListenerOnSuccessfulTurn() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var received = new ArrayList<String>();
        tp.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult result, String userInput) {
                received.add(userInput);
            }
        });
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();
        tp.process(session, "hello", ctx);
        assertEquals(1, received.size());
        assertEquals("hello", received.get(0));
    }
    @Test
    void turnProcessorFiresMultipleListeners() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        AtomicInteger count = new AtomicInteger(0);
        tp.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult r, String u) { count.incrementAndGet(); }
        });
        tp.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult r, String u) { count.incrementAndGet(); }
        });
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();
        tp.process(session, "test", ctx);
        assertEquals(2, count.get(), "Both listeners should fire");
    }
    @Test
    void turnProcessorListenerErrorDoesNotBreakPipeline() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var tp = new TurnProcessor(modes);
        var received = new ArrayList<String>();
        tp.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult r, String u) { throw new RuntimeException("boom"); }
        });
        tp.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult r, String u) { received.add(u); }
        });
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();
        TurnResult result = tp.process(session, "test", ctx);
        assertNotNull(result);
        assertEquals(1, received.size());
    }
    @Test
    void turnProcessorDoesNotFireOnNoResult() throws Exception {
        var tp = new TurnProcessor(new ModeController());
        AtomicInteger count = new AtomicInteger(0);
        tp.addListener(new SessionListener() {
            @Override public void onTurnComplete(TurnResult r, String u) { count.incrementAndGet(); }
        });
        var session = new Session(WS, new Config());
        var ctx = Context.builder(new Config()).build();
        TurnResult result = tp.process(session, "orphan", ctx);
        assertNull(result);
        assertEquals(0, count.get());
    }
    @Test
    void turnProcessorFireSessionEnd() {
        var tp = new TurnProcessor(new ModeController());
        AtomicInteger count = new AtomicInteger(0);
        tp.addListener(new SessionListener() {
            @Override public void onSessionEnd() { count.incrementAndGet(); }
        });
        tp.fireSessionEnd();
        assertEquals(1, count.get());
    }
    @Test
    void sessionCloseFiresListeners() {
        var session = new Session(WS, new Config());
        AtomicInteger count = new AtomicInteger(0);
        session.addCloseListener(new SessionListener() {
            @Override public void onSessionEnd() { count.incrementAndGet(); }
        });
        session.close();
        assertEquals(1, count.get());
    }
    @Test
    void sessionCloseIsIdempotent() {
        var session = new Session(WS, new Config());
        AtomicInteger count = new AtomicInteger(0);
        session.addCloseListener(new SessionListener() {
            @Override public void onSessionEnd() { count.incrementAndGet(); }
        });
        session.close();
        session.close();
        assertEquals(1, count.get());
    }
    @Test
    void sessionIsClosedReflectsState() {
        var session = new Session(WS, new Config());
        assertFalse(session.isClosed());
        session.close();
        assertTrue(session.isClosed());
    }
    @Test
    void sessionCloseListenerErrorDoesNotPreventOthers() {
        var session = new Session(WS, new Config());
        AtomicInteger count = new AtomicInteger(0);
        session.addCloseListener(new SessionListener() {
            @Override public void onSessionEnd() { throw new RuntimeException("boom"); }
        });
        session.addCloseListener(new SessionListener() {
            @Override public void onSessionEnd() { count.incrementAndGet(); }
        });
        session.close();
        assertEquals(1, count.get());
    }
    @Test
    void endToEndMemoryUpdateViaTurnProcessor() throws Exception {
        var modes = new ModeController();
        modes.add(new StubMode("ask", true));
        var memory = new SessionMemory();
        var cm = new ConversationManager(memory, new TokenBudget());
        var tp = new TurnProcessor(modes);
        tp.addListener(new MemoryUpdateListener(cm));
        var ctx = Context.builder(new Config()).memory(memory).conversationManager(cm).build();
        var session = new Session(WS, new Config(), memory);
        TurnResult r = tp.process(session, "hello world", ctx);
        assertNotNull(r);
        assertEquals(1, cm.turnCount());
        var turns = memory.getTurns();
        assertEquals("hello world", turns.get(0).content());
        assertEquals("assistant", turns.get(1).role());
    }
    private static class StubMode implements dev.talos.cli.modes.Mode {
        private final String modeName;
        private final boolean handles;
        StubMode(String name, boolean handles) { this.modeName = name; this.handles = handles; }
        @Override public String name() { return modeName; }
        @Override public boolean canHandle(String raw) { return handles; }
        @Override public Optional<Result> handle(String raw, Path ws, Context ctx) {
            return Optional.of(new Result.Ok("stub-answer"));
        }
    }
}
