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
}
