package dev.talos.runtime.checkpoint;

public record CheckpointRestoreResult(
        boolean success,
        String checkpointId,
        String message,
        int restoredFiles,
        int deletedFiles,
        int failedFiles,
        String authorityPlanId,
        String operationId
) {
    public CheckpointRestoreResult {
        checkpointId = checkpointId == null ? "" : checkpointId;
        message = message == null ? "" : message;
        authorityPlanId = authorityPlanId == null
                ? ""
                : authorityPlanId;
        operationId = operationId == null ? "" : operationId;
        if (authorityPlanId.isEmpty() != operationId.isEmpty()
                || !authorityPlanId.isEmpty()
                        && (!authorityPlanId.matches("[0-9a-f]{64}")
                                || !operationId.matches("[0-9a-f]{64}"))) {
            throw new IllegalArgumentException(
                    "checkpoint restore authority identities must be an exact SHA-256 pair");
        }
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
                0,
                "",
                "");
    }

    public static CheckpointRestoreResult success(
            String checkpointId,
            int restoredFiles,
            int deletedFiles,
            String authorityPlanId,
            String operationId
    ) {
        return new CheckpointRestoreResult(
                true,
                checkpointId,
                "Checkpoint restored.",
                restoredFiles,
                deletedFiles,
                0,
                authorityPlanId,
                operationId);
    }

    public static CheckpointRestoreResult failure(String checkpointId, String message) {
        return new CheckpointRestoreResult(
                false, checkpointId, message, 0, 0, 0, "", "");
    }

    public static CheckpointRestoreResult failure(
            String checkpointId,
            String message,
            String authorityPlanId,
            String operationId
    ) {
        return new CheckpointRestoreResult(
                false,
                checkpointId,
                message,
                0,
                0,
                0,
                authorityPlanId,
                operationId);
    }

    public static CheckpointRestoreResult partial(
            String checkpointId,
            String message,
            int restoredFiles,
            int deletedFiles,
            int failedFiles
    ) {
        return new CheckpointRestoreResult(
                false,
                checkpointId,
                message,
                restoredFiles,
                deletedFiles,
                failedFiles,
                "",
                "");
    }

    public static CheckpointRestoreResult partial(
            String checkpointId,
            String message,
            int restoredFiles,
            int deletedFiles,
            int failedFiles,
            String authorityPlanId,
            String operationId
    ) {
        return new CheckpointRestoreResult(
                false,
                checkpointId,
                message,
                restoredFiles,
                deletedFiles,
                failedFiles,
                authorityPlanId,
                operationId);
    }
}
