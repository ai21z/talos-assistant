package dev.talos.runtime;
import dev.talos.cli.repl.Result;
import dev.talos.cli.repl.SessionMemory;
import dev.talos.core.context.ConversationManager;
import dev.talos.core.context.TokenBudget;
import dev.talos.spi.types.ChatMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
class MemoryUpdateListenerTest {
    private SessionMemory memory;
    private ConversationManager cm;
    private MemoryUpdateListener listener;
    @BeforeEach
    void setUp() {
        memory = new SessionMemory();
        cm = new ConversationManager(memory, new TokenBudget());
        listener = new MemoryUpdateListener(cm);
    }
    @Test void okResultIsRecordedInMemory() {
        listener.onTurnComplete(tr(new Result.Ok("Hello!"), 1), "hi");
        assertEquals(1, cm.turnCount());
        assertEquals("Hello!", cm.buildHistory().get(1).content());
    }
    @Test void streamedResultIsRecordedInMemory() {
        listener.onTurnComplete(tr(new Result.Streamed("streamed answer", "[Sources]"), 1), "explain X");
        assertEquals(1, cm.turnCount());
        assertEquals("streamed answer", cm.buildHistory().get(1).content());
    }
    @Test void streamedWithEmptySuffixIsRecorded() {
        listener.onTurnComplete(tr(new Result.Streamed("plain streamed", ""), 1), "hey");
        assertEquals(1, cm.turnCount());
        assertEquals("plain streamed", cm.buildHistory().get(1).content());
    }
    @Test void multiTurnStreamedConversation() {
        listener.onTurnComplete(tr(new Result.Streamed("a1", ""), 1), "q1");
        listener.onTurnComplete(tr(new Result.Streamed("a2", ""), 2), "q2");
        listener.onTurnComplete(tr(new Result.Streamed("a3", ""), 3), "q3");
        assertEquals(3, cm.turnCount());
        List<ChatMessage> h = cm.buildHistory();
        assertEquals(6, h.size());
        assertEquals("q1", h.get(0).content());
        assertEquals("a3", h.get(5).content());
    }
    @Test void mixedStreamedAndOkTurns() {
        listener.onTurnComplete(tr(new Result.Streamed("chat", ""), 1), "hello");
        listener.onTurnComplete(tr(new Result.Ok("rag"), 2), "explain");
        assertEquals(2, cm.turnCount());
    }
    @Test void infoResultIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Info("rebuilt"), 1), "reindex");
        assertEquals(0, cm.turnCount());
    }
    @Test void trustedInfoIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.TrustedInfo("ws: /home"), 1), "ws");
        assertEquals(0, cm.turnCount());
    }
    @Test void errorResultIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Error("boom", 500), 1), "crash");
        assertEquals(0, cm.turnCount());
    }
    @Test void tableResultIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Table("T", List.of("c"), List.of(List.of("r"))), 1), "list");
        assertEquals(0, cm.turnCount());
    }
    @Test void streamLifecycleNotRecorded() {
        listener.onTurnComplete(tr(new Result.StreamStart(""), 1), "a");
        listener.onTurnComplete(tr(new Result.StreamChunk("x"), 2), "b");
        listener.onTurnComplete(tr(new Result.StreamEnd(), 3), "c");
        assertEquals(0, cm.turnCount());
    }
    @Test void nullResultIsIgnored() {
        listener.onTurnComplete(null, "hello");
        assertEquals(0, cm.turnCount());
    }
    @Test void nullUserInputIsIgnored() {
        listener.onTurnComplete(tr(new Result.Ok("a"), 1), null);
        assertEquals(0, cm.turnCount());
    }
    @Test void blankUserInputIsIgnored() {
        listener.onTurnComplete(tr(new Result.Ok("a"), 1), "   ");
        assertEquals(0, cm.turnCount());
    }
    @Test void blankAnswerIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Ok("   "), 1), "hello");
        assertEquals(0, cm.turnCount());
    }
    @Test void emptyStreamedFullTextIsNotRecorded() {
        listener.onTurnComplete(tr(new Result.Streamed("", "[Sources]"), 1), "q");
        assertEquals(0, cm.turnCount());
    }
    @Test void extractTextFromNull() {
        assertNull(MemoryUpdateListener.extractText(null));
    }
    @Test void extractTextFromOk() {
        assertEquals("hello", MemoryUpdateListener.extractText(new Result.Ok("hello")));
    }
    @Test void extractTextFromStreamed() {
        assertEquals("body", MemoryUpdateListener.extractText(new Result.Streamed("body", "[S]")));
    }
    private static TurnResult tr(Result r, int turn) {
        return new TurnResult(r, null, turn, Duration.ofMillis(50));
    }
}
