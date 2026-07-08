package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ApprovalDiffPreview;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointDetail;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.CheckpointSummary;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

public final class CheckpointCommand implements Command {

    /** Per-checkpoint diff budget for {@code show}: files rendered with a diff. */
    private static final int SHOW_DIFF_FILE_LIMIT = 3;

    private final Path workspace;
    private final CheckpointService checkpointService;

    public CheckpointCommand(Path workspace, CheckpointService checkpointService) {
        this.workspace = workspace;
        this.checkpointService = checkpointService;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("checkpoint", List.of("restore"),
                "/checkpoint [list|show <id>|restore <id>]", "Manage local mutation checkpoints.",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isBlank() || "list".equalsIgnoreCase(trimmed)) {
            List<CheckpointSummary> summaries = checkpointService.listSummaries(workspace);
            if (summaries.isEmpty()) return new Result.Info("No checkpoints found for this workspace.");
            return new Result.Info(renderTimeline(summaries, ZoneId.systemDefault()));
        }

        String[] parts = trimmed.split("\\s+", 2);
        String sub = parts[0].toLowerCase(java.util.Locale.ROOT);
        String id = parts.length > 1 ? parts[1].trim() : "";

        if ("show".equals(sub)) {
            if (id.isBlank()) {
                return new Result.Error("Usage: /checkpoint show <id>", 200);
            }
            return checkpointService.describe(workspace, id)
                    .<Result>map(detail -> new Result.Info(renderDetail(detail, ZoneId.systemDefault())))
                    .orElseGet(() -> new Result.Error("Checkpoint not found: " + id, 200));
        }

        if (!"restore".equals(sub) || id.isBlank()) {
            return new Result.Error("Usage: /checkpoint [list|show <id>|restore <id>]", 200);
        }

        // The restore approval bytes below are frozen for the wave (T787 pin);
        // the rich previewed path is /undo (T795).
        String checkpointId = id;
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

        CheckpointDetail detail = checkpointService.describe(workspace, checkpointId).orElse(null);
        if (detail == null || detail.entries().isEmpty()) {
            return new Result.Error("Checkpoint restore aborted - checkpoint details are unavailable. "
                    + "No file changed.", 500);
        }
        List<String> affectedPaths = detail.entries().stream()
                .map(CheckpointDetail.Entry::relativePath)
                .filter(path -> !path.isBlank())
                .toList();
        CheckpointCaptureResult safety = checkpointService.captureBeforeRestore(
                workspace, ctx.cfg(), affectedPaths, "restore of " + checkpointId, "", 0);
        if (!safety.success() || safety.skipped()) {
            return new Result.Error("Checkpoint restore aborted - the pre-restore safety checkpoint failed: "
                    + safety.message() + " No file changed.", 500);
        }

        CheckpointRestoreResult restore = checkpointService.restore(workspace, checkpointId);
        if (!restore.success()) {
            return new Result.Error("Checkpoint restore failed: " + restore.message()
                    + " The pre-restore state is saved as " + safety.checkpointId() + ".", 500);
        }
        return new Result.Ok("Checkpoint restored: " + checkpointId
                + " (" + restore.restoredFiles() + " restored, "
                + restore.deletedFiles() + " deleted)."
                + " Pre-restore state saved as " + safety.checkpointId() + ".");
    }

    /** T794: the timeline - newest first, with time, turn, trigger, and size. */
    static String renderTimeline(List<CheckpointSummary> summaries, ZoneId zone) {
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        StringBuilder sb = new StringBuilder("Checkpoints (newest first):\n");
        for (CheckpointSummary summary : summaries) {
            sb.append("  ").append(summary.id())
                    .append(" | ").append(summary.createdAt().equals(java.time.Instant.EPOCH)
                            ? "(time unknown)"
                            : format.format(summary.createdAt()))
                    .append(" | turn ").append(summary.turnNumber() < 0 ? "?" : summary.turnNumber())
                    .append(" | ").append(summary.trigger())
                    .append(" | ").append(summary.fileCount()).append(" file(s), ")
                    .append(humanBytes(summary.byteCount()))
                    .append('\n');
        }
        sb.append("Use /checkpoint show <id> for diffs, /undo to restore the newest.");
        return sb.toString();
    }

    /** T794: per-file stats plus capped diffs of captured content vs the CURRENT files. */
    private String renderDetail(CheckpointDetail detail, ZoneId zone) {
        CheckpointSummary summary = detail.summary();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(zone);
        StringBuilder sb = new StringBuilder();
        sb.append(summary.id()).append('\n');
        sb.append("  captured: ").append(summary.createdAt().equals(java.time.Instant.EPOCH)
                ? "(time unknown)" : format.format(summary.createdAt()));
        sb.append(" | turn ").append(summary.turnNumber() < 0 ? "?" : summary.turnNumber());
        sb.append(" | ").append(summary.trigger()).append('\n');

        int diffsRendered = 0;
        int diffsSkippedByBudget = 0;
        for (CheckpointDetail.Entry entry : detail.entries()) {
            sb.append("  ").append(entry.relativePath());
            if (!entry.existedBefore()) {
                sb.append("  (did not exist at capture - restore DELETES it)\n");
                continue;
            }
            if (!"FILE".equals(entry.entryType())) {
                sb.append("  (").append(entry.entryType().toLowerCase(java.util.Locale.ROOT)).append(")\n");
                continue;
            }
            sb.append("  (").append(humanBytes(entry.sizeBytes())).append(" captured)\n");
            if (diffsRendered >= SHOW_DIFF_FILE_LIMIT) {
                diffsSkippedByBudget++;
                continue;
            }
            ApprovalDiffPreview.Preview diff = checkpointService
                    .blob(workspace, summary.id(), entry.blobSha256())
                    .map(blob -> ApprovalDiffPreview.forRestore(workspace, entry.relativePath(), blob))
                    .orElse(null);
            if (diff == null) {
                sb.append("    (captured blob unavailable)\n");
                continue;
            }
            diffsRendered++;
            if (diff.skipped()) {
                sb.append("    (diff unavailable: ").append(diff.skippedReason()).append(")\n");
            } else if (!diff.note().isBlank() && diff.text().isBlank()) {
                sb.append("    (").append(diff.note()).append(")\n");
            } else {
                sb.append("    restore diff (+").append(diff.added())
                        .append(" -").append(diff.removed()).append("):\n");
                for (String line : diff.text().split("\n", -1)) {
                    sb.append("    ").append(line).append('\n');
                }
            }
        }
        if (diffsSkippedByBudget > 0) {
            sb.append("  (").append(diffsSkippedByBudget)
                    .append(" more file diff(s) not shown - restore previews cap at ")
                    .append(SHOW_DIFF_FILE_LIMIT).append(" files)\n");
        }
        return sb.toString().stripTrailing();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        return String.format(java.util.Locale.ROOT, "%.1f KiB", bytes / 1024.0);
    }
}
