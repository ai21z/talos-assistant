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

class LocalTurnTracePromptAuditRecorderTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsPromptAuditSnapshotAndSummaryEvent() {
        beginTrace();

        PromptAuditSnapshot snapshot = promptAuditSnapshot();
        LocalTurnTraceCapture.recordPromptAudit(snapshot);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals(snapshot, trace.promptAudit());
        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "PROMPT_AUDIT_RECORDED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "taskType", "FILE_EDIT",
                "actionObligation", "MUTATING_TOOL_REQUIRED",
                "currentTurnFrameInjected", true,
                "currentTurnFramePlacement", "AFTER_HISTORY_BEFORE_USER",
                "historyPolicy", "INCLUDED"), event.data());
    }

    @Test
    void emptyPromptAuditSnapshotRemainsUnrecorded() {
        beginTrace();

        LocalTurnTraceCapture.recordPromptAudit(PromptAuditSnapshot.empty());

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();
        assertFalse(trace.events().stream()
                .anyMatch(candidate -> "PROMPT_AUDIT_RECORDED".equals(candidate.type())));
        assertTrue(trace.promptAudit().taskType().isBlank());
        assertTrue(trace.promptAudit().nativeTools().isEmpty());
    }

    @Test
    void promptAuditRecordingHasDedicatedRecorderOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path recorderPath = Path.of("src/main/java/dev/talos/runtime/trace/PromptAuditTraceRecorder.java");

        assertTrue(Files.exists(recorderPath),
                "prompt audit snapshot and event recording should have a dedicated owner");

        String captureSource = Files.readString(capturePath);
        String methodBody = methodBody(captureSource, "recordPromptAudit");
        String recorderSource = Files.readString(recorderPath);

        assertTrue(captureSource.contains("PromptAuditTraceRecorder."), captureSource);
        assertTrue(methodBody.contains("snapshot.hasPromptAuditData()"), methodBody);
        assertFalse(methodBody.contains("builder.promptAudit"), methodBody);
        assertFalse(methodBody.contains("\"PROMPT_AUDIT_RECORDED\""), methodBody);

        assertTrue(recorderSource.contains("promptAudit(snapshot)"), recorderSource);
        assertTrue(recorderSource.contains("PROMPT_AUDIT_RECORDED"), recorderSource);
        assertTrue(recorderSource.contains("taskType"), recorderSource);
        assertTrue(recorderSource.contains("actionObligation"), recorderSource);
        assertTrue(recorderSource.contains("currentTurnFrameInjected"), recorderSource);
        assertTrue(recorderSource.contains("currentTurnFramePlacement"), recorderSource);
        assertTrue(recorderSource.contains("historyPolicy"), recorderSource);
    }

    private static PromptAuditSnapshot promptAuditSnapshot() {
        return new PromptAuditSnapshot(
                1,
                "FILE_EDIT",
                true,
                true,
                "APPLY",
                "APPLY",
                "MUTATING_TOOL_REQUIRED",
                "NONE",
                "NOT_DERIVED",
                "NONE_OR_NOT_DERIVED",
                "NONE_OR_NOT_DERIVED",
                "STATIC_TASK_VERIFIER",
                "INCLUDED",
                2,
                true,
                "AFTER_HISTORY_BEFORE_USER",
                "frame-hash",
                "[CurrentTurnCapability] SECRET=[redacted]",
                2,
                1,
                5,
                "prompt-hash",
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.read_file", "talos.write_file"),
                List.of("talos.shell"),
                TraceRedactionMode.DEFAULT);
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
                "trc-prompt-audit-recorder",
                "sid-prompt-audit-recorder",
                1,
                "2026-05-28T00:00:00Z",
                "workspace-hash",
                "auto",
                "test",
                "model",
                "record prompt audit");
    }
}
