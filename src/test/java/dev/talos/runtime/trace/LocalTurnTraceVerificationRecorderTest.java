package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalTurnTraceVerificationRecorderTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsVerificationSummaryAndEvent() {
        beginTrace();

        LocalTurnTraceCapture.recordVerification(
                "  FAILED  ",
                "  Static verification failed.  ",
                List.of("Missing script.js", "Button selector missing"));

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("  FAILED  ", trace.verification().status());
        assertEquals("  Static verification failed.  ", trace.verification().summary());
        assertEquals(List.of("Missing script.js", "Button selector missing"), trace.verification().problems());
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "VERIFICATION_COMPLETED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "status", "FAILED",
                "problemCount", 2), event.data());
    }

    @Test
    void nullVerificationProblemsCountAsZero() {
        beginTrace();

        LocalTurnTraceCapture.recordVerification(null, null, null);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertTrue(trace.verification().status().isBlank());
        assertTrue(trace.verification().summary().isBlank());
        assertTrue(trace.verification().problems().isEmpty());
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "VERIFICATION_COMPLETED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "status", "",
                "problemCount", 0), event.data());
    }

    @Test
    void verificationRecordingHasDedicatedRecorderOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path recorderPath = Path.of("src/main/java/dev/talos/runtime/trace/VerificationTraceRecorder.java");

        assertTrue(Files.exists(recorderPath),
                "verification summary and event recording should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordVerification");
        String recorderSource = Files.readString(recorderPath);

        assertTrue(captureSource.contains("VerificationTraceRecorder."), captureSource);
        assertFalse(methodBody.contains("builder.verification"), methodBody);
        assertFalse(methodBody.contains("\"VERIFICATION_COMPLETED\""), methodBody);

        assertTrue(recorderSource.contains("verification(status, summary, problems)"), recorderSource);
        assertTrue(recorderSource.contains("VERIFICATION_COMPLETED"), recorderSource);
        assertTrue(recorderSource.contains("status"), recorderSource);
        assertTrue(recorderSource.contains("problemCount"), recorderSource);
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
                "trc-verification-recorder",
                "sid-verification-recorder",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record verification");
    }
}
