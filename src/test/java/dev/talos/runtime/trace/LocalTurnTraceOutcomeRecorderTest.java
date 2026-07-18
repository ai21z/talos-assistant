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

class LocalTurnTraceOutcomeRecorderTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void outcomeOnlyCompletionRecordsEvaluationButNotRenderedOutcome() {
        beginTrace();

        LocalTurnTraceCapture.recordOutcome(
                "  COMPLETE  ",
                "PASSED",
                "GRANTED_OR_NOT_REQUIRED",
                "SUCCEEDED",
                "  TASK_COMPLETE  ");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("  COMPLETE  ", trace.outcome().status());
        assertEquals("PASSED", trace.outcome().verificationStatus());
        assertEquals("PASSED", trace.verification().status());
        assertEquals("GRANTED_OR_NOT_REQUIRED", trace.outcome().approvalStatus());
        assertEquals("SUCCEEDED", trace.outcome().mutationStatus());
        assertEquals("  TASK_COMPLETE  ", trace.outcome().classification());
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "OUTCOME_EVALUATED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "status", "COMPLETE",
                "classification", "TASK_COMPLETE"), event.data());
        assertFalse(trace.events().stream()
                .anyMatch(candidate -> "OUTCOME_RENDERED".equals(candidate.type())));
    }

    @Test
    void outcomeIfAbsentDoesNotOverrideRecordedOutcome() {
        beginTrace();
        LocalTurnTraceCapture.recordRoutedAnswerCaptured("answer");

        LocalTurnTraceCapture.recordOutcome("COMPLETE", "PASSED", "NONE", "NOT_REQUESTED", "READ_ONLY_ANSWERED");
        LocalTurnTraceCapture.recordOutcomeIfAbsent("FAILED", "FAILED", "DENIED", "DENIED", "BLOCKED_BY_POLICY");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("COMPLETE", trace.outcome().status());
        assertEquals("PASSED", trace.outcome().verificationStatus());
        assertEquals("PASSED", trace.verification().status());
        assertEquals("NONE", trace.outcome().approvalStatus());
        assertEquals("NOT_REQUESTED", trace.outcome().mutationStatus());
        assertEquals("READ_ONLY_ANSWERED", trace.outcome().classification());
        List<TurnTraceEvent> outcomeEvents = trace.events().stream()
                .filter(candidate -> "OUTCOME_RENDERED".equals(candidate.type()))
                .toList();
        assertEquals(1, outcomeEvents.size());
        assertEquals(0, trace.events().stream()
                .filter(candidate -> "OUTCOME_EVALUATED".equals(candidate.type()))
                .count());
        assertEquals(Map.of(
                "status", "COMPLETE",
                "classification", "READ_ONLY_ANSWERED"), outcomeEvents.getFirst().data());
    }

    @Test
    void outcomeDoesNotOverwriteExplicitVerificationEvidence() {
        beginTrace();
        LocalTurnTraceCapture.recordVerification(
                "FAILED", "Static verification failed.", List.of("Missing expected content"));

        LocalTurnTraceCapture.recordOutcome(
                "FAILED", "NOT_RUN", "NONE", "NOT_REQUESTED", "TASK_INCOMPLETE");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("FAILED", trace.verification().status());
        assertEquals("Static verification failed.", trace.verification().summary());
        assertEquals(List.of("Missing expected content"), trace.verification().problems());
    }

    @Test
    void outcomeRecordingHasDedicatedRecorderOwnerAndKeepsDominanceGuardInFacade() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path recorderPath = Path.of("src/main/java/dev/talos/runtime/trace/OutcomeTraceRecorder.java");

        assertTrue(Files.exists(recorderPath),
                "outcome summary and event recording should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordOutcome");
        String recorderSource = Files.readString(recorderPath);

        assertTrue(captureSource.contains("OutcomeTraceRecorder."), captureSource);
        assertTrue(methodBody.contains("outcomeRecorded = true"), methodBody);
        assertFalse(methodBody.contains("builder.outcome"), methodBody);
        assertFalse(methodBody.contains("\"OUTCOME_RENDERED\""), methodBody);

        assertTrue(recorderSource.contains("outcome(status, verificationStatus, approvalStatus, mutationStatus, classification)"),
                recorderSource);
        assertTrue(recorderSource.contains("OUTCOME_RENDERED"), recorderSource);
        assertTrue(recorderSource.contains("OUTCOME_EVALUATED"), recorderSource);
        assertTrue(recorderSource.contains("status"), recorderSource);
        assertTrue(recorderSource.contains("classification"), recorderSource);
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
                "trc-outcome-recorder",
                "sid-outcome-recorder",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record outcome");
    }
}
