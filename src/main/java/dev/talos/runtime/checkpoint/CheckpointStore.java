package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.List;

public interface CheckpointStore {
    CheckpointCaptureResult captureBeforeMutation(
            Path workspace,
            Config config,
            ToolCall call,
            String traceId,
            int turnNumber);

    default CheckpointCaptureResult captureBeforeOperation(
            Path workspace,
            Config config,
            WorkspaceOperationPlan plan,
            String traceId,
            int turnNumber
    ) {
        return CheckpointCaptureResult.failure("Bundle checkpoint capture is not supported by this store.");
    }

    CheckpointRestoreResult restore(Path workspace, String checkpointId);

    default List<String> listIds(Path workspace) {
        return List.of();
    }

    /** T793: summaries sorted newest-first by {@code createdAt} (id tiebreak). */
    default List<CheckpointSummary> listSummaries(Path workspace) {
        return List.of();
    }

    /** T793: summary plus manifest entries for one checkpoint, if it exists. */
    default java.util.Optional<CheckpointDetail> describe(Path workspace, String checkpointId) {
        return java.util.Optional.empty();
    }

    /** T793: raw captured bytes of one blob (for restore diff previews). */
    default java.util.Optional<byte[]> blob(Path workspace, String checkpointId, String blobSha256) {
        return java.util.Optional.empty();
    }

    /**
     * T793: capture the CURRENT state of the given workspace-relative paths
     * before a restore overwrites them — the safety checkpoint that makes
     * {@code /undo} itself undoable.
     */
    default CheckpointCaptureResult captureBeforeRestore(
            Path workspace,
            Config config,
            List<String> relativePaths,
            String trigger,
            String traceId,
            int turnNumber
    ) {
        return CheckpointCaptureResult.failure("Restore-safety capture is not supported by this store.");
    }
}
