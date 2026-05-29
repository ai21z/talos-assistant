package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceActionObligationTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsActionObligationEventsWithOptionalFailureKind() {
        beginTrace();

        LocalTurnTraceCapture.recordActionObligation(
                "  MUTATING_TOOL_REQUIRED  ",
                "  SELECTED  ",
                "  task requires mutation  ");
        LocalTurnTraceCapture.recordActionObligation(
                "STATIC_REPAIR_WRITE_CONTENT",
                "FAILED",
                "  placeholder content rejected  ",
                "  STATIC_REPAIR_INVALID_WRITE_CONTENT  ");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        List<TurnTraceEvent> events = trace.events().stream()
                .filter(event -> "ACTION_OBLIGATION_EVALUATED".equals(event.type()))
                .toList();
        assertEquals(2, events.size());

        TurnTraceEvent selected = events.get(0);
        assertEquals("MUTATING_TOOL_REQUIRED", selected.data().get("obligation"));
        assertEquals("SELECTED", selected.data().get("status"));
        assertEquals("task requires mutation", selected.data().get("reason"));
        assertFalse(selected.data().containsKey("failureKind"));

        TurnTraceEvent failed = events.get(1);
        assertEquals("STATIC_REPAIR_WRITE_CONTENT", failed.data().get("obligation"));
        assertEquals("FAILED", failed.data().get("status"));
        assertEquals("placeholder content rejected", failed.data().get("reason"));
        assertEquals("STATIC_REPAIR_INVALID_WRITE_CONTENT", failed.data().get("failureKind"));
    }

    @Test
    void actionObligationEventShapeHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/ActionObligationTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "action-obligation event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String firstOverload = methodBodyFromMarker(
                captureSource,
                "recordActionObligation(String obligation, String status, String reason)");
        String secondOverload = methodBodyFromMarker(
                captureSource,
                "recordActionObligation(\n            String obligation");
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("ActionObligationTraceEventFactory."), captureSource);
        assertFalse(firstOverload.contains("\"ACTION_OBLIGATION_EVALUATED\""), firstOverload);
        assertFalse(firstOverload.contains("Map.of"), firstOverload);
        assertFalse(secondOverload.contains("\"ACTION_OBLIGATION_EVALUATED\""), secondOverload);
        assertFalse(secondOverload.contains("new LinkedHashMap"), secondOverload);
        assertFalse(secondOverload.contains("data.put"), secondOverload);

        assertTrue(factorySource.contains("ACTION_OBLIGATION_EVALUATED"), factorySource);
        assertTrue(factorySource.contains("new LinkedHashMap"), factorySource);
        assertTrue(factorySource.contains("\"obligation\""), factorySource);
        assertTrue(factorySource.contains("\"status\""), factorySource);
        assertTrue(factorySource.contains("\"reason\""), factorySource);
        assertTrue(factorySource.contains("\"failureKind\""), factorySource);
    }

    private static String methodBodyFromMarker(String source, String marker) {
        String normalized = source.replace("\r\n", "\n");
        int start = normalized.indexOf(marker);
        assertTrue(start >= 0, "method marker not found: " + marker);
        int brace = normalized.indexOf('{', start);
        assertTrue(brace >= 0, "method opening brace not found: " + marker);
        int depth = 0;
        for (int i = brace; i < normalized.length(); i++) {
            char ch = normalized.charAt(i);
            if (ch == '{') depth++;
            if (ch == '}') depth--;
            if (depth == 0) {
                return normalized.substring(brace, i + 1);
            }
        }
        throw new AssertionError("method closing brace not found: " + marker);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-action-obligation",
                "sid-action-obligation",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record action obligation");
    }
}
