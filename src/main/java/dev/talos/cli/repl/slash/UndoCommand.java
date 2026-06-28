package dev.talos.cli.repl.slash;

import dev.talos.cli.repl.Context;
import dev.talos.runtime.ApprovalDiffPreview;
import dev.talos.runtime.ApprovalGate;
import dev.talos.runtime.ApprovalResponse;
import dev.talos.runtime.Result;
import dev.talos.runtime.checkpoint.CheckpointCaptureResult;
import dev.talos.runtime.checkpoint.CheckpointConfig;
import dev.talos.runtime.checkpoint.CheckpointDetail;
import dev.talos.runtime.checkpoint.CheckpointRestoreResult;
import dev.talos.runtime.checkpoint.CheckpointService;
import dev.talos.runtime.checkpoint.CheckpointSummary;
import dev.talos.runtime.policy.ProtectedContentPolicy;
import dev.talos.runtime.trace.LocalTurnTraceCapture;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * {@code /undo} - approval-gated restore of the newest checkpoint (T795).
 *
 * <p>This replaces the pre-T795 implementation, which popped an in-memory
 * per-file stack and wrote workspace files directly with NO approval gate,
 * NO checkpoint, and NO protected-path classification (the trust hole the
 * 2026-06-10 evaluation flagged, pinned by T787). Undo now flows through
 * the governed checkpoint machinery: the approval detail shows what will
 * be restored (with capped redacted diffs), a safety checkpoint of the
 * current state is captured FIRST so {@code /undo} is itself undoable
 * ({@code /undo} twice = redo), and the restore is traced best-effort.
 */
public final class UndoCommand implements Command {

    private static final int PREVIEW_DIFF_FILE_LIMIT = 3;

    private final Path workspace;
    private final CheckpointService checkpointService;

    public UndoCommand(Path workspace, CheckpointService checkpointService) {
        this.workspace = workspace;
        this.checkpointService = checkpointService;
    }

    @Override
    public CommandSpec spec() {
        return new CommandSpec("undo", List.of(),
                "/undo", "Undo the last checkpointed change (gated restore).",
                CommandGroup.SECURITY);
    }

    @Override
    public Result execute(String args, Context ctx) {
        if (checkpointService == null) {
            return new Result.Error("/undo requires the checkpoint service.", 500);
        }
        if (ctx != null && !CheckpointConfig.from(ctx.cfg()).enabled()) {
            return new Result.Info("Checkpointing is disabled (checkpoint.enabled=false)"
                    + " - /undo has nothing to restore.");
        }
        List<CheckpointSummary> summaries = checkpointService.listSummaries(workspace);
        if (summaries.isEmpty()) {
            return new Result.Info("Nothing to undo.\n");
        }
        CheckpointSummary newest = summaries.get(0);

        ApprovalGate gate = ctx == null ? null : ctx.approvalGate();
        if (gate == null) {
            return new Result.Error("/undo requires an approval gate.", 500);
        }

        CheckpointDetail detail = checkpointService.describe(workspace, newest.id())
                .orElse(new CheckpointDetail(newest, List.of()));
        ApprovalResponse response = gate.approveFull(
                "undo: restore checkpoint " + newest.id(),
                approvalDetail(detail));
        if (!response.isApproved()) {
            return new Result.Info("Undo cancelled. No file changed.");
        }

        // Safety first: capture the CURRENT state of every affected path so
        // this undo is itself undoable. A failed safety capture aborts -
        // never a destructive restore without the way back.
        List<String> affectedPaths = detail.entries().stream()
                .map(CheckpointDetail.Entry::relativePath)
                .filter(path -> !path.isBlank())
                .toList();
        CheckpointCaptureResult safety = checkpointService.captureBeforeRestore(
                workspace, ctx.cfg(), affectedPaths, "undo of " + newest.id(), "", 0);
        if (!safety.success()) {
            return new Result.Error("Undo aborted - the pre-undo safety checkpoint failed: "
                    + safety.message() + " No file changed.", 500);
        }

        CheckpointRestoreResult restore = checkpointService.restore(workspace, newest.id());
        LocalTurnTraceCapture.recordCheckpointRestore(newest.id(), restore.success(),
                restore.restoredFiles(), restore.deletedFiles(), restore.message());
        if (!restore.success()) {
            return new Result.Error("Undo failed: " + restore.message()
                    + " The pre-undo state is saved as " + safety.checkpointId() + ".", 500);
        }
        return new Result.Ok("Undid checkpoint " + newest.id()
                + " (" + restore.restoredFiles() + " restored, "
                + restore.deletedFiles() + " deleted)."
                + " Pre-undo state saved as " + safety.checkpointId()
                + " - /undo again to redo.");
    }

    /** What the user reads before approving: time, trigger, files, capped diffs. */
    private String approvalDetail(CheckpointDetail detail) {
        CheckpointSummary summary = detail.summary();
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .withZone(ZoneId.systemDefault());
        StringBuilder sb = new StringBuilder();
        sb.append("Restore the newest checkpoint ").append(summary.id()).append('\n');
        sb.append("    captured: ").append(summary.createdAt().equals(java.time.Instant.EPOCH)
                ? "(time unknown)" : format.format(summary.createdAt()));
        sb.append(" | turn ").append(summary.turnNumber() < 0 ? "?" : summary.turnNumber());
        sb.append(" | ").append(summary.trigger()).append('\n');

        int diffsRendered = 0;
        for (CheckpointDetail.Entry entry : detail.entries()) {
            sb.append("    ").append(entry.relativePath());
            if (!entry.existedBefore()) {
                sb.append("  (will be DELETED - did not exist at capture)\n");
                continue;
            }
            if (!"FILE".equals(entry.entryType())) {
                sb.append("  (").append(entry.entryType().toLowerCase(java.util.Locale.ROOT))
                        .append(")\n");
                continue;
            }
            sb.append("  (restored)\n");
            if (diffsRendered >= PREVIEW_DIFF_FILE_LIMIT) continue;
            ApprovalDiffPreview.Preview diff = checkpointService
                    .blob(workspace, summary.id(), entry.blobSha256())
                    .map(blob -> ApprovalDiffPreview.forRestore(workspace, entry.relativePath(), blob))
                    .orElse(null);
            if (diff == null || diff.skipped() || diff.text().isBlank()) continue;
            diffsRendered++;
            sb.append("    diff (+").append(diff.added())
                    .append(" -").append(diff.removed()).append("):\n");
            for (String line : diff.text().split("\n", -1)) {
                sb.append("    ").append(line).append('\n');
            }
        }
        sb.append("    a safety checkpoint of the current state is captured first"
                + " - /undo again restores it (redo)");
        return ProtectedContentPolicy.sanitizeText(sb.toString());
    }
}
