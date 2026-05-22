package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.Result;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;

import java.nio.file.Path;
import java.util.List;

public final class CheckpointCommand implements Command {

    private final Path workspace;
    private final CheckpointService checkpointService;

    public CheckpointCommand(Path workspace, CheckpointService checkpointService) {
        this.workspace = workspace;
        this.checkpointService = checkpointService;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("checkpoint", List.of("restore"),
                "/checkpoint [list|restore <id>]", "Manage local mutation checkpoints.",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isBlank() || "list".equalsIgnoreCase(trimmed)) {
            List<String> ids = checkpointService.listIds(workspace);
            if (ids.isEmpty()) return new Result.Info("No checkpoints found for this workspace.");
            return new Result.Info("Checkpoints:\n  " + String.join("\n  ", ids));
        }

        String[] parts = trimmed.split("\\s+", 2);
        if (!"restore".equalsIgnoreCase(parts[0]) || parts.length < 2 || parts[1].isBlank()) {
            return new Result.Error("Usage: /checkpoint [list|restore <id>]", 200);
        }

        String checkpointId = parts[1].trim();
        ApprovalGate gate = ctx == null ? null : ctx.approvalGate();
        if (gate == null) {
            return new Result.Error("Checkpoint restore requires an approval gate.", 500);
        }
        ApprovalResponse approval = gate.approveFull(
                "restore checkpoint: " + checkpointId,
                "Restore files captured by checkpoint " + checkpointId
                        + " in workspace " + workspace);
        if (!approval.isApproved()) {
            return new Result.Info("Checkpoint restore cancelled. No file changed.");
        }

        CheckpointRestoreResult restore = checkpointService.restore(workspace, checkpointId);
        if (!restore.success()) {
            return new Result.Error("Checkpoint restore failed: " + restore.message(), 500);
        }
        return new Result.Ok("Checkpoint restored: " + checkpointId
                + " (" + restore.restoredFiles() + " restored, "
                + restore.deletedFiles() + " deleted)");
    }
}
