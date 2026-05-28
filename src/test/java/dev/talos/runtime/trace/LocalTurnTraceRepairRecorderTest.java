package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LocalTurnTraceRepairRecorderTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsRepairSummaryAndEvent() {
        beginTrace();

        LocalTurnTraceCapture.recordRepair("  PLANNED  ", "  static repair required  ");

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("PLANNED", trace.repair().status());
        assertEquals("static repair required", trace.repair().summary());
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "REPAIR_DECISION_RECORDED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "status", "PLANNED",
                "summary", "static repair required"), event.data());
    }

    @Test
    void nullRepairFieldsAreRecordedAsEmptyStrings() {
        beginTrace();

        LocalTurnTraceCapture.recordRepair(null, null);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertTrue(trace.repair().status().isBlank());
        assertTrue(trace.repair().summary().isBlank());
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "REPAIR_DECISION_RECORDED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "status", "",
                "summary", ""), event.data());
    }

    @Test
    void repairRecordingHasDedicatedRecorderOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path recorderPath = Path.of("src/main/java/dev/talos/runtime/trace/RepairTraceRecorder.java");

        assertTrue(Files.exists(recorderPath),
                "repair summary and event recording should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordRepair");
        String recorderSource = Files.readString(recorderPath);

        assertTrue(captureSource.contains("RepairTraceRecorder."), captureSource);
        assertFalse(methodBody.contains("builder.repair"), methodBody);
        assertFalse(methodBody.contains("\"REPAIR_DECISION_RECORDED\""), methodBody);

        assertTrue(recorderSource.contains("repair(safeStatus, safeSummary)"), recorderSource);
        assertTrue(recorderSource.contains("REPAIR_DECISION_RECORDED"), recorderSource);
        assertTrue(recorderSource.contains("status"), recorderSource);
        assertTrue(recorderSource.contains("summary"), recorderSource);
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
                "trc-repair-recorder",
                "sid-repair-recorder",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record repair");
    }
}
