package dev.talos.runtime.trace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalTurnTraceCheckpointRecorderTest {

    @AfterEach
    void cleanup() {
        LocalTurnTraceCapture.clear();
    }

    @Test
    void recordsCheckpointSummaryAndEventPayload() {
        LocalTurnTraceCapture.begin(
                "trc-checkpoint",
                "sid",
                1,
                "2026-05-28T00:00:00Z",
                "sid",
                "auto",
                "test",
                "model",
                "write file");

        LocalTurnTraceCapture.recordCheckpoint(
                "CREATED",
                "chk-123",
                "  Checkpoint created.  ",
                3);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("CREATED", trace.checkpoint().status());
        assertEquals("chk-123", trace.checkpoint().checkpointId());

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "CHECKPOINT_CREATED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals(Map.of(
                "status", "CREATED",
                "checkpointId", "chk-123",
                "capturedFiles", 3,
                "reason", "Checkpoint created."), event.data());
    }

    @Test
    void blankCheckpointStatusUsesRecordedFallbackAndOmitsBlankReason() {
        LocalTurnTraceCapture.begin(
                "trc-checkpoint-blank",
                "sid",
                1,
                "2026-05-28T00:00:00Z",
                "sid",
                "auto",
                "test",
                "model",
                "write file");

        LocalTurnTraceCapture.recordCheckpoint(" ", " ", "  ", 0);

        LocalTurnTrace trace = LocalTurnTraceCapture.complete();

        assertEquals("", trace.checkpoint().status());
        assertEquals("", trace.checkpoint().checkpointId());

        TurnTraceEvent event = trace.events().stream()
                .filter(candidate -> "CHECKPOINT_RECORDED".equals(candidate.type()))
                .findFirst()
                .orElseThrow();
        assertEquals("", event.data().get("status"));
        assertEquals("", event.data().get("checkpointId"));
        assertEquals(0, event.data().get("capturedFiles"));
        assertFalse(event.data().containsKey("reason"));
    }

    @Test
    void checkpointTraceRecordingHasDedicatedRecorderOwner() throws Exception {
        Path capturePath = Path.of("src/main/java/dev/talos/runtime/trace/LocalTurnTraceCapture.java");
        Path recorderPath = Path.of("src/main/java/dev/talos/runtime/trace/CheckpointTraceRecorder.java");

        assertTrue(Files.exists(recorderPath),
                "checkpoint trace recording should have a dedicated recorder source file");

        String captureSource = Files.readString(capturePath);
        String recorderSource = Files.readString(recorderPath);

        assertTrue(captureSource.contains("CheckpointTraceRecorder.record("), captureSource);
        assertFalse(captureSource.contains("\"CHECKPOINT_\""), captureSource);
        assertFalse(captureSource.contains("builder.checkpoint("), captureSource);

        assertTrue(recorderSource.contains("builder.checkpoint("), recorderSource);
        assertTrue(recorderSource.contains("\"CHECKPOINT_\""), recorderSource);
        assertTrue(recorderSource.contains("capturedFiles"), recorderSource);
    }
}
