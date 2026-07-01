package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTracePendingActionObligationTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsRaisedBreachedAndFallbackPendingObligationEvents() {
        beginTrace();

        LocalTurnTraceCapture.recordPendingActionObligation(
                "RAISED",
                "EXPECTED_TARGETS_REMAINING",
                List.of("README.md", "src/App.java"),
                "  needs executable write/edit tool calls  ");
        LocalTurnTraceCapture.recordPendingActionObligation(
                "BREACHED",
                "STATIC_REPAIR_TARGETS_REMAINING",
                List.of("styles.css"),
                "model response had no executable write/edit tool calls");
        LocalTurnTraceCapture.recordPendingActionObligation(
                "CHECKED",
                null,
                null,
                null);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        List<TurnTraceEvent> pendingEvents = trace.events().stream()
                .filter(event -> event.type().startsWith("PENDING_ACTION_OBLIGATION_"))
                .toList();
        assertEquals(3, pendingEvents.size());

        TurnTraceEvent raised = pendingEvents.get(0);
        assertEquals("PENDING_ACTION_OBLIGATION_RAISED", raised.type());
        assertEquals("RAISED", raised.data().get("status"));
        assertEquals("EXPECTED_TARGETS_REMAINING", raised.data().get("kind"));
        assertEquals(List.of("README.md", "src/App.java"), raised.data().get("targets"));
        assertEquals("needs executable write/edit tool calls", raised.data().get("reason"));

        TurnTraceEvent breached = pendingEvents.get(1);
        assertEquals("PENDING_ACTION_OBLIGATION_BREACHED", breached.type());
        assertEquals("BREACHED", breached.data().get("status"));
        assertEquals("STATIC_REPAIR_TARGETS_REMAINING", breached.data().get("kind"));
        assertEquals(List.of("styles.css"), breached.data().get("targets"));
        assertEquals("model response had no executable write/edit tool calls", breached.data().get("reason"));

        TurnTraceEvent fallback = pendingEvents.get(2);
        assertEquals("PENDING_ACTION_OBLIGATION_EVALUATED", fallback.type());
        assertEquals("CHECKED", fallback.data().get("status"));
        assertEquals("", fallback.data().get("kind"));
        assertEquals(List.of(), fallback.data().get("targets"));
        assertEquals("", fallback.data().get("reason"));
    }

    @Test
    void pendingActionObligationEventShapeHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/PendingActionObligationTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "pending action-obligation event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordPendingActionObligation");
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("PendingActionObligationTraceEventFactory."), captureSource);
        assertFalse(methodBody.contains("switch"), methodBody);
        assertFalse(methodBody.contains("PENDING_ACTION_OBLIGATION_RAISED"), methodBody);
        assertFalse(methodBody.contains("PENDING_ACTION_OBLIGATION_BREACHED"), methodBody);
        assertFalse(methodBody.contains("PENDING_ACTION_OBLIGATION_EVALUATED"), methodBody);
        assertFalse(methodBody.contains("targets == null"), methodBody);

        assertTrue(factorySource.contains("PENDING_ACTION_OBLIGATION_RAISED"), factorySource);
        assertTrue(factorySource.contains("PENDING_ACTION_OBLIGATION_BREACHED"), factorySource);
        assertTrue(factorySource.contains("PENDING_ACTION_OBLIGATION_EVALUATED"), factorySource);
        assertTrue(factorySource.contains("List.copyOf(targets)"), factorySource);
        assertTrue(factorySource.contains("\"targets\""), factorySource);
    }

    private static String methodBody(String source, String methodName) {
        int start = source.indexOf(methodName);
        assertTrue(start >= 0, "method not found: " + methodName);
        int brace = source.indexOf('{', start);
        assertTrue(brace >= 0, "method opening brace not found: " + methodName);
        int depth = 0;
        for (int i = brace; i < source.length(); i++) {
            char ch = source.charAt(i);
            if (ch == '{') depth++;
            if (ch == '}') depth--;
            if (depth == 0) {
                return source.substring(brace, i + 1);
            }
        }
        throw new AssertionError("method closing brace not found: " + methodName);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-pending-action-obligation",
                "sid-pending-action-obligation",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record pending action obligation");
    }
}
