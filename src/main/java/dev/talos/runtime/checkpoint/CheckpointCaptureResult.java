package dev.talos.runtime.checkpoint;

public record CheckpointCaptureResult(
        boolean success,
        boolean skipped,
        String checkpointId,
        String status,
        String message,
        int capturedFiles
) {
    public CheckpointCaptureResult {
        checkpointId = checkpointId == null ? "" : checkpointId;
        status = status == null ? "" : status;
        message = message == null ? "" : message;
    }

    public static CheckpointCaptureResult captured(String checkpointId, int capturedFiles) {
        return new CheckpointCaptureResult(true, false, checkpointId, "CREATED",
                "Checkpoint created.", capturedFiles);
    }

    public static CheckpointCaptureResult skipped(String reason) {
        return new CheckpointCaptureResult(true, true, "", "SKIPPED", reason, 0);
    }

    public static CheckpointCaptureResult failure(String message) {
        return new CheckpointCaptureResult(false, false, "", "FAILED", message, 0);
    }
}
