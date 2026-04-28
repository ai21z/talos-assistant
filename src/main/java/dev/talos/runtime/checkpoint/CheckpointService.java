package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
import dev.talos.tools.ToolCall;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public final class CheckpointService {

    private final CheckpointStore store;

    public CheckpointService() {
        this(new FileBundleCheckpointStore(CheckpointConfig.defaultRoot()));
    }

    public CheckpointService(CheckpointStore store) {
        this.store = Objects.requireNonNull(store, "store must not be null");
    }

    public CheckpointCaptureResult captureBeforeMutation(
            Path workspace,
            Config config,
            ToolCall call,
            String traceId,
            int turnNumber
    ) {
        CheckpointConfig cfg = CheckpointConfig.from(config);
        if (!cfg.enabled()) {
            return CheckpointCaptureResult.skipped("Checkpointing is disabled.");
        }
        return store.captureBeforeMutation(workspace, config, call, traceId, turnNumber);
    }

    public CheckpointRestoreResult restore(Path workspace, String checkpointId) {
        return store.restore(workspace, checkpointId);
    }

    public List<String> listIds(Path workspace) {
        return store.listIds(workspace);
    }
}
