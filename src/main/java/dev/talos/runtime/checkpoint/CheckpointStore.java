package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
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

    CheckpointRestoreResult restore(Path workspace, String checkpointId);

    default List<String> listIds(Path workspace) {
        return List.of();
    }
}
