package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceBackendMalformedResponseTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsBackendMalformedResponseDiagnosticsWithoutRawBodyPreview() {
        beginTrace();

        LocalTurnTraceCapture.recordBackendMalformedResponse(
                "  compat chat stream tool arguments  ",
                "  sha256:abc123  ",
                -7);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "BACKEND_MALFORMED_RESPONSE_CAPTURED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(Map.of(
                "context", "compat chat stream tool arguments",
                "bodyHash", "sha256:abc123",
                "bodyChars", 0), event.data());
        assertFalse(event.data().containsKey("bodyPreview"), event.data().toString());
    }

    @Test
    void backendMalformedResponseTraceEventConstructionHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/BackendMalformedResponseTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "backend malformed response trace event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("BackendMalformedResponseTraceEventFactory."), captureSource);
        assertFalse(captureSource.contains("\"BACKEND_MALFORMED_RESPONSE_CAPTURED\""), captureSource);
        assertFalse(captureSource.contains("data.put(\"bodyHash\""), captureSource);
        assertFalse(captureSource.contains("data.put(\"bodyChars\""), captureSource);

        assertTrue(factorySource.contains("BACKEND_MALFORMED_RESPONSE_CAPTURED"), factorySource);
        assertTrue(factorySource.contains("data.put(\"context\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"bodyHash\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"bodyChars\""), factorySource);
        assertFalse(factorySource.contains("bodyPreview"), factorySource);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-backend-malformed-response",
                "sid-backend-malformed-response",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "replace malformed backend response");
    }
}
