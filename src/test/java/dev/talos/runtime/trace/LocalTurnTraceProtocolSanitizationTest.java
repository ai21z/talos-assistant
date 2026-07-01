package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceProtocolSanitizationTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsProtocolSanitizationReason() {
        beginTrace();

        LocalTurnTraceCapture.recordProtocolSanitized("  malformed tool protocol debris was replaced  ");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "PROTOCOL_SANITIZED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(Map.of("reason", "malformed tool protocol debris was replaced"), event.data());
    }

    @Test
    void protocolSanitizationTraceEventConstructionHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/ProtocolSanitizationTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "protocol sanitization trace event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("ProtocolSanitizationTraceEventFactory."), captureSource);
        assertFalse(captureSource.contains("\"PROTOCOL_SANITIZED\""), captureSource);
        assertFalse(captureSource.contains("Map.of(\"reason\""), captureSource);

        assertTrue(factorySource.contains("PROTOCOL_SANITIZED"), factorySource);
        assertTrue(factorySource.contains("\"reason\""), factorySource);
    }

    private static void beginTrace() {
        LocalTurnTraceCapture.begin(
                "trc-protocol-sanitized",
                "sid-protocol-sanitized",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "replace malformed protocol");
    }
}
