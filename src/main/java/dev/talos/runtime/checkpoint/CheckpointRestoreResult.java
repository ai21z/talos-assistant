package dev.talos.runtime.checkpoint;

public record CheckpointRestoreResult(
        boolean success,
        String checkpointId,
        String message,
        int restoredFiles,
        int deletedFiles,
        int failedFiles
) {
    public CheckpointRestoreResult {
        checkpointId = checkpointId == null ? "" : checkpointId;
        message = message == null ? "" : message;
    }

    public static CheckpointRestoreResult success(
            String checkpointId,
            int restoredFiles,
            int deletedFiles
    ) {
        return new CheckpointRestoreResult(
                true,
                checkpointId,
                "Checkpoint restored.",
                restoredFiles,
                deletedFiles,
                0);
    }

    public static CheckpointRestoreResult failure(String checkpointId, String message) {
        return new CheckpointRestoreResult(false, checkpointId, message, 0, 0, 0);
    }

    public static CheckpointRestoreResult partial(
            String checkpointId,
            String message,
            int restoredFiles,
            int deletedFiles,
            int failedFiles
    ) {
        return new CheckpointRestoreResult(false, checkpointId, message, restoredFiles, deletedFiles, failedFiles);
    }
}
