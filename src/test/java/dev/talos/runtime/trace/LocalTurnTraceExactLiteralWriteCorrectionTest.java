package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceExactLiteralWriteCorrectionTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsExactLiteralWriteCorrectionEvidenceWithoutRawPayload() {
        beginTrace();

        LocalTurnTraceCapture.recordExactLiteralWriteCorrected(
                "  ./docs/README.md  ",
                "  literal-complete-file-two-lines  ",
                "  sha256:expected  ",
                -12,
                2,
                "  sha256:observed  ",
                37,
                -3);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "EXACT_LITERAL_WRITE_CORRECTED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(Map.of(
                "pathHint", "docs/README.md",
                "sourcePattern", "literal-complete-file-two-lines",
                "expectedHash", "sha256:expected",
                "expectedBytes", 0,
                "expectedLines", 2,
                "observedHash", "sha256:observed",
                "observedBytes", 37,
                "observedLines", 0), event.data());
        assertFalse(event.data().containsKey("expectedContent"), event.data().toString());
        assertFalse(event.data().containsKey("observedContent"), event.data().toString());
    }

    @Test
    void exactLiteralWriteCorrectionTraceEventConstructionHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of(
                "src/main/java/dev/talos/runtime/trace/ExactLiteralWriteCorrectionTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "exact literal write correction trace event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordExactLiteralWriteCorrected");
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("ExactLiteralWriteCorrectionTraceEventFactory."), captureSource);
        assertFalse(methodBody.contains("\"EXACT_LITERAL_WRITE_CORRECTED\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"pathHint\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"expectedHash\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"observedHash\""), methodBody);
        assertFalse(methodBody.contains("TraceRedactor.pathHint"), methodBody);

        assertTrue(factorySource.contains("EXACT_LITERAL_WRITE_CORRECTED"), factorySource);
        assertTrue(factorySource.contains("data.put(\"pathHint\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"sourcePattern\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"expectedHash\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"observedHash\""), factorySource);
        assertTrue(factorySource.contains("TraceRedactor.pathHint"), factorySource);
        assertFalse(factorySource.contains("expectedContent"), factorySource);
        assertFalse(factorySource.contains("observedContent"), factorySource);
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
                "trc-exact-literal-write-correction",
                "sid-exact-literal-write-correction",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "correct exact literal write");
    }
}
