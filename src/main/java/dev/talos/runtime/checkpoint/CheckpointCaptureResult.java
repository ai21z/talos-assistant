package dev.talos.runtime.checkpoint;

public record CheckpointCaptureResult(
        boolean success,
        boolean skipped,
        String checkpointId,
        String status,
        String message,
        int capturedFiles,
        String authorityPlanId,
        String operationId
) {
    public CheckpointCaptureResult {
        checkpointId = checkpointId == null ? "" : checkpointId;
        status = status == null ? "" : status;
        message = message == null ? "" : message;
        if (capturedFiles < 0) {
            throw new IllegalArgumentException(
                    "checkpoint captured-file count must not be negative");
        }
        authorityPlanId = authorityPlanId == null
                ? ""
                : authorityPlanId.strip().toLowerCase();
        operationId = operationId == null
                ? ""
                : operationId.strip().toLowerCase();
        if (authorityPlanId.isEmpty() != operationId.isEmpty()
                || !authorityPlanId.isEmpty()
                        && (!authorityPlanId.matches("[0-9a-f]{64}")
                                || !operationId.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "checkpoint authority identities must be an exact SHA-256 pair");
        }
        boolean created = success
                && !skipped
                && "CREATED".equals(status)
                && !checkpointId.isBlank();
        boolean intentionallySkipped = success
                && skipped
                && "SKIPPED".equals(status)
                && checkpointId.isBlank()
                && capturedFiles == 0;
        boolean failed = !success
                && !skipped
                && "FAILED".equals(status)
                && checkpointId.isBlank()
                && capturedFiles == 0;
        if (!created && !intentionallySkipped && !failed) {
            throw new IllegalArgumentException(
                    "checkpoint result flags and status must describe one coherent outcome");
        }
    }

    public CheckpointCaptureResult(
            boolean success,
            boolean skipped,
            String checkpointId,
            String status,
            String message,
            int capturedFiles
    ) {
        this(success, skipped, checkpointId, status, message, capturedFiles,
                "", "");
    }

    public static CheckpointCaptureResult captured(String checkpointId, int capturedFiles) {
        return new CheckpointCaptureResult(true, false, checkpointId, "CREATED",
                "Checkpoint created.", capturedFiles);
    }

    public static CheckpointCaptureResult captured(
            String checkpointId,
            int capturedFiles,
            String authorityPlanId,
            String operationId
    ) {
        return new CheckpointCaptureResult(
                true,
                false,
                checkpointId,
                "CREATED",
                "Checkpoint created.",
                capturedFiles,
                authorityPlanId,
                operationId);
    }

    public static CheckpointCaptureResult skipped(String reason) {
        return new CheckpointCaptureResult(true, true, "", "SKIPPED", reason, 0);
    }

    public static CheckpointCaptureResult skipped(
            String reason,
            String authorityPlanId,
            String operationId
    ) {
        return new CheckpointCaptureResult(
                true, true, "", "SKIPPED", reason, 0,
                authorityPlanId, operationId);
    }

    public static CheckpointCaptureResult failure(String message) {
        return new CheckpointCaptureResult(false, false, "", "FAILED", message, 0);
    }

    public static CheckpointCaptureResult failure(
            String message,
            String authorityPlanId,
            String operationId
    ) {
        return new CheckpointCaptureResult(
                false, false, "", "FAILED", message, 0,
                authorityPlanId, operationId);
    }
}
