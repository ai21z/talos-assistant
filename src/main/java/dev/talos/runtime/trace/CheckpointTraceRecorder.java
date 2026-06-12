package dev.talos.runtime.trace;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Records checkpoint summary state and checkpoint trace events together. */
final class CheckpointTraceRecorder {
    private CheckpointTraceRecorder() {}

    static void record(
            LocalTurnTrace.Builder builder,
            String status,
            String checkpointId,
            String reason,
            int capturedFiles
    ) {
        if (builder == null) return;
        String safeStatus = safe(status);
        String safeId = safe(checkpointId);
        builder.checkpoint(safeStatus, safeId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("status", safeStatus);
        data.put("checkpointId", safeId);
        data.put("capturedFiles", capturedFiles);
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason.strip());
        }
        builder.event(TurnTraceEvent.simple("CHECKPOINT_" + (safeStatus.isBlank() ? "RECORDED" : safeStatus),
                Instant.now().toString(),
                data));
    }

    /** T793: restore outcome event (counts only, no content). */
    static void recordRestore(
            LocalTurnTrace.Builder builder,
            String checkpointId,
            boolean success,
            int restoredFiles,
            int deletedFiles,
            String reason
    ) {
        if (builder == null) return;
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("checkpointId", safe(checkpointId));
        data.put("restoredFiles", restoredFiles);
        data.put("deletedFiles", deletedFiles);
        if (reason != null && !reason.isBlank()) {
            data.put("reason", reason.strip());
        }
        builder.event(TurnTraceEvent.simple(
                success ? "CHECKPOINT_RESTORED" : "CHECKPOINT_RESTORE_FAILED",
                Instant.now().toString(),
                data));
    }

    private static String safe(String value) {
        return value == null ? "" : value.strip();
    }
}
