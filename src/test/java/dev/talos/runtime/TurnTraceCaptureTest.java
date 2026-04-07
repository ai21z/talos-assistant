package dev.talos.runtime;
import dev.talos.core.retrieval.RetrievalTrace;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class TurnTraceCaptureTest {
    @AfterEach
    void cleanup() {
        // Always clear to prevent test pollution
        TurnTraceCapture.consume();
    }
    @Test void captureAndConsumeReturnsTrace() {
        RetrievalTrace trace = new RetrievalTrace();
        trace.record("Bm25Stage", 1_000_000, 0, 5);
        TurnTraceCapture.capture(trace);
        RetrievalTrace consumed = TurnTraceCapture.consume();
        assertSame(trace, consumed);
        assertEquals(1, consumed.entries().size());
        assertEquals("Bm25Stage", consumed.entries().get(0).stageName());
    }
    @Test void consumeClearsTheTrace() {
        TurnTraceCapture.capture(new RetrievalTrace());
        assertNotNull(TurnTraceCapture.consume());
        // Second consume should return null (cleared)
        assertNull(TurnTraceCapture.consume());
    }
    @Test void consumeWithoutCaptureReturnsNull() {
        assertNull(TurnTraceCapture.consume());
    }
    @Test void captureNullIsAllowed() {
        TurnTraceCapture.capture(null);
        assertNull(TurnTraceCapture.consume());
    }
    @Test void captureOverwritesPrevious() {
        RetrievalTrace first = new RetrievalTrace();
        first.record("Stage1", 100, 0, 3);
        RetrievalTrace second = new RetrievalTrace();
        second.record("Stage2", 200, 0, 7);
        TurnTraceCapture.capture(first);
        TurnTraceCapture.capture(second);
        RetrievalTrace consumed = TurnTraceCapture.consume();
        assertSame(second, consumed);
    }
}
