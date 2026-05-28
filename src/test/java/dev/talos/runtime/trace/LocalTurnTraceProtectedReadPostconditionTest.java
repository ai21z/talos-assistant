package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceProtectedReadPostconditionTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsProtectedReadPostconditionWithRedactedPathHints() {
        LocalTurnTraceCapture.begin(
                "trc-protected-read-postcondition",
                "sid",
                1,
                "2026-05-28T00:00:00Z",
                "sid",
                "auto",
                "test",
                "model",
                "read protected file");

        LocalTurnTraceCapture.recordProtectedReadPostcondition(
                "REPAIRED",
                List.of(".env", "protected/private-notes.md"),
                "  replaced generic refusal  ");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "PROTECTED_READ_POSTCONDITION_CHECKED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(Map.of(
                "status", "REPAIRED",
                "pathHints", List.of("<protected-path>", "<protected-path>"),
                "reason", "replaced generic refusal"), event.data());
    }

    @Test
    void protectedReadPostconditionTraceEventConstructionHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/ProtectedReadPostconditionTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "protected-read postcondition trace event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("ProtectedReadPostconditionTraceEventFactory."), captureSource);
        assertFalse(captureSource.contains("\"PROTECTED_READ_POSTCONDITION_CHECKED\""), captureSource);
        assertFalse(captureSource.contains("\"pathHints\""), captureSource);
        assertFalse(captureSource.contains("TraceRedactor::pathHint"), captureSource);

        assertTrue(factorySource.contains("PROTECTED_READ_POSTCONDITION_CHECKED"), factorySource);
        assertTrue(factorySource.contains("\"pathHints\""), factorySource);
        assertTrue(factorySource.contains("TraceRedactor::pathHint"), factorySource);
    }
}
