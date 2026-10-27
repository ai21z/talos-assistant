package dev.talos.runtime.checkpoint;

import dev.talos.core.Config;
import dev.talos.runtime.workspace.WorkspaceOperationPlan;
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

    public CheckpointCaptureResult captureBeforeOperation(
            Path workspace,
            Config config,
            WorkspaceOperationPlan plan,
            String traceId,
            int turnNumber
    ) {
        if (plan == null
                || plan.authorityPlanId().isBlank()
                || plan.operationId().isBlank()) {
            return CheckpointCaptureResult.failure(
                    "Workspace-operation checkpoint requires typed authority identities.");
        }
        CheckpointConfig cfg = CheckpointConfig.from(config);
        if (!cfg.enabled()) {
            return CheckpointCaptureResult.skipped(
                    "Checkpointing is disabled.",
                    plan.authorityPlanId(),
                    plan.operationId());
        }
        CheckpointCaptureResult result = store.captureBeforeOperation(
                workspace,
                config,
                plan,
                traceId,
                turnNumber);
        if (result == null
                || !plan.authorityPlanId().equals(result.authorityPlanId())
                || !plan.operationId().equals(result.operationId())) {
            String detail = result == null || result.message().isBlank()
                    ? "checkpoint store omitted the typed authority binding"
                    : result.message();
            return CheckpointCaptureResult.failure(
                    "Checkpoint result did not retain the typed workspace-operation identity: "
                            + detail,
                    plan.authorityPlanId(),
                    plan.operationId());
        }
        return result;
    }

    public CheckpointRestoreResult restore(Path workspace, String checkpointId) {
        return store.restore(workspace, checkpointId);
    }

    public CheckpointRestoreResult restore(
            Path workspace,
            String checkpointId,
            String authorityPlanId,
            String operationId
    ) {
        if (!isSha256Identity(authorityPlanId)
                || !isSha256Identity(operationId)) {
            return CheckpointRestoreResult.failure(
                    checkpointId,
                    "Workspace-operation checkpoint restore requires exact typed authority identities.");
        }
        CheckpointRestoreResult result = store.restore(
                workspace,
                checkpointId,
                authorityPlanId,
                operationId);
        if (result == null
                || !authorityPlanId.equals(result.authorityPlanId())
                || !operationId.equals(result.operationId())) {
            return CheckpointRestoreResult.failure(
                    checkpointId,
                    "Checkpoint restore result did not retain the expected typed workspace-operation identity.",
                    authorityPlanId,
                    operationId);
        }
        return result;
    }

    private static boolean isSha256Identity(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    public List<String> listIds(Path workspace) {
        return store.listIds(workspace);
    }

    public List<CheckpointSummary> listSummaries(Path workspace) {
        return store.listSummaries(workspace);
    }

    public java.util.Optional<CheckpointDetail> describe(Path workspace, String checkpointId) {
        return store.describe(workspace, checkpointId);
    }

    public java.util.Optional<byte[]> blob(Path workspace, String checkpointId, String blobSha256) {
        return store.blob(workspace, checkpointId, blobSha256);
    }

    /**
     * T793: capture the current state of the given paths before a restore
     * overwrites them. Honors the same enabled/disabled config gate as the
     * mutation captures.
     */
    public CheckpointCaptureResult captureBeforeRestore(
            Path workspace,
            Config config,
            List<String> relativePaths,
            String trigger,
            String traceId,
            int turnNumber
    ) {
        CheckpointConfig cfg = CheckpointConfig.from(config);
        if (!cfg.enabled()) {
            return CheckpointCaptureResult.skipped("Checkpointing is disabled.");
        }
        return store.captureBeforeRestore(workspace, config, relativePaths, trigger, traceId, turnNumber);
    }
}
