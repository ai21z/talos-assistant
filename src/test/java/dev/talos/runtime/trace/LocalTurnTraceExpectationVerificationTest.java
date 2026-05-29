package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceExpectationVerificationTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsExpectationVerifiedEventWithRedactedPathAndBoundedMetrics() {
        beginTrace();

        LocalTurnTraceCapture.recordExpectationVerified(
                "  LITERAL_CONTENT  ",
                "  PASSED  ",
                "C:/workspace/protected/private-notes.md",
                "  expected source  ",
                "  expected-hash  ",
                -1,
                12,
                -3,
                "  observed-hash  ",
                -5,
                34,
                -8);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "EXPECTATION_VERIFIED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("", event.phase());
        assertEquals("", event.toolName());
        assertEquals("LITERAL_CONTENT", event.data().get("kind"));
        assertEquals("PASSED", event.data().get("status"));
        assertEquals("<protected-path>", event.data().get("pathHint"));
        assertEquals("expected source", event.data().get("sourcePattern"));
        assertEquals("expected-hash", event.data().get("expectedHash"));
        assertEquals(0, event.data().get("expectedBytes"));
        assertEquals(12, event.data().get("expectedChars"));
        assertEquals(0, event.data().get("expectedLines"));
        assertEquals("observed-hash", event.data().get("observedHash"));
        assertEquals(0, event.data().get("observedBytes"));
        assertEquals(34, event.data().get("observedChars"));
        assertEquals(0, event.data().get("observedLines"));
    }

    @Test
    void expectationVerificationEventShapeHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of("src/main/java/dev/talos/runtime/trace/ExpectationVerificationTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "EXPECTATION_VERIFIED event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordExpectationVerified");
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("ExpectationVerificationTraceEventFactory."), captureSource);
        assertFalse(methodBody.contains("new LinkedHashMap"), methodBody);
        assertFalse(methodBody.contains("\"EXPECTATION_VERIFIED\""), methodBody);
        assertFalse(methodBody.contains("TraceRedactor.pathHint"), methodBody);
        assertFalse(methodBody.contains("Math.max"), methodBody);

        assertTrue(factorySource.contains("EXPECTATION_VERIFIED"), factorySource);
        assertTrue(factorySource.contains("TraceRedactor.pathHint"), factorySource);
        assertTrue(factorySource.contains("Math.max(0, expectedBytes)"), factorySource);
        assertTrue(factorySource.contains("Math.max(0, observedLines)"), factorySource);
        assertTrue(factorySource.contains("expectedChars"), factorySource);
        assertTrue(factorySource.contains("observedChars"), factorySource);
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
                "trc-expectation-verification",
                "sid-expectation-verification",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record expectation");
    }
}
