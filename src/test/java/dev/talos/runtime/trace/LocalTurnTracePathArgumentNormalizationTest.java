package dev.talos.runtime.trace;

import dev.talos.tools.ToolCall;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalTurnTracePathArgumentNormalizationTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsPathArgumentNormalizationWithStablePayloadAndSlashNormalization() {
        beginTrace();

        LocalTurnTraceCapture.recordPathArgumentNormalized(
                "tool_loop",
                new ToolCall("talos.read_file", Map.of("path", "src\\Main.java")),
                "  path  ",
                "src\\Main.java",
                ".\\src\\Main.java");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "TOOL_PATH_ARGUMENT_NORMALIZED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals("tool_loop", event.phase());
        assertEquals("talos.read_file", event.toolName());
        assertEquals(Map.of(
                "key", "path",
                "rawPath", "src/Main.java",
                "normalizedPath", "./src/Main.java"), event.data());
    }

    @Test
    void pathArgumentNormalizationTraceEventConstructionHasDedicatedFactoryOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path factoryPath = Path.of(
                "src/main/java/dev/talos/runtime/trace/PathArgumentNormalizationTraceEventFactory.java");

        assertTrue(Files.exists(factoryPath),
                "path argument normalization trace event construction should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordPathArgumentNormalized");
        String factorySource = Files.readString(factoryPath);

        assertTrue(captureSource.contains("PathArgumentNormalizationTraceEventFactory."), captureSource);
        assertFalse(methodBody.contains("\"TOOL_PATH_ARGUMENT_NORMALIZED\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"key\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"rawPath\""), methodBody);
        assertFalse(methodBody.contains("data.put(\"normalizedPath\""), methodBody);
        assertFalse(methodBody.contains("replace('\\\\', '/')"), methodBody);

        assertTrue(factorySource.contains("TOOL_PATH_ARGUMENT_NORMALIZED"), factorySource);
        assertTrue(factorySource.contains("data.put(\"key\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"rawPath\""), factorySource);
        assertTrue(factorySource.contains("data.put(\"normalizedPath\""), factorySource);
        assertTrue(factorySource.contains("replace('\\\\', '/')"), factorySource);
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
                "trc-path-argument-normalization",
                "sid-path-argument-normalization",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "normalize tool path argument");
    }
}
