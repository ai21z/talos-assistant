package dev.talos.runtime.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalTurnTraceModelResponseTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsModelResponseSummaryAndEventWithoutRawAssistantText() throws Exception {
        beginTrace();

        LocalTurnTraceCapture.recordModelResponseReceived("Answer mentions SECRET=abc.");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "MODEL_RESPONSE_RECEIVED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();

        assertEquals(TraceRedactor.hash("Answer mentions SECRET=abc."), event.data().get("assistantHash"));
        assertEquals("Answer mentions SECRET=abc.".length(), event.data().get("assistantChars"));
        assertEquals(TraceRedactor.hash("Answer mentions SECRET=abc."), trace.redaction().assistantHash());

        String json = MAPPER.writeValueAsString(trace);
        assertFalse(json.contains("SECRET=abc"), "local trace must not store raw assistant text");
    }

    @Test
    void modelResponseTraceRecordingHasDedicatedRecorderOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path recorderPath = Path.of("src/main/java/dev/talos/runtime/trace/ModelResponseTraceRecorder.java");

        assertTrue(Files.exists(recorderPath),
                "model response trace summary and event recording should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordModelResponseReceived");
        String recorderSource = Files.readString(recorderPath);

        assertTrue(captureSource.contains("ModelResponseTraceRecorder."), captureSource);
        assertFalse(methodBody.contains("assistantSummary("), methodBody);
        assertFalse(methodBody.contains("\"MODEL_RESPONSE_RECEIVED\""), methodBody);
        assertFalse(methodBody.contains("\"assistantHash\""), methodBody);
        assertFalse(methodBody.contains("\"assistantChars\""), methodBody);

        assertTrue(recorderSource.contains("assistantSummary("), recorderSource);
        assertTrue(recorderSource.contains("MODEL_RESPONSE_RECEIVED"), recorderSource);
        assertTrue(recorderSource.contains("\"assistantHash\""), recorderSource);
        assertTrue(recorderSource.contains("\"assistantChars\""), recorderSource);
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
                "trc-model-response",
                "sid-model-response",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record model response trace");
    }
}
